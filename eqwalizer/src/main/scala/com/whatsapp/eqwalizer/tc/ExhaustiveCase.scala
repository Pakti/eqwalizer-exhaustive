/* Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.
 *
 * This source code is licensed under the Apache 2.0 license found in
 * the root directory of this source tree.
 */

package com.whatsapp.eqwalizer.tc

import com.whatsapp.eqwalizer.ast.Exprs.*
import com.whatsapp.eqwalizer.ast.Forms.{FunDecl, FunSpec}
import com.whatsapp.eqwalizer.ast.Guards.*
import com.whatsapp.eqwalizer.ast.Pats.*
import com.whatsapp.eqwalizer.ast.Show.show
import com.whatsapp.eqwalizer.ast.Specifier.*
import com.whatsapp.eqwalizer.ast.Types.*
import com.whatsapp.eqwalizer.ast.{Id, Pos}
import com.whatsapp.eqwalizer.tc.TcDiagnostics.NonExhaustiveCase
import com.whatsapp.eqwalizer.util.Diagnostic.Diagnostic

final class ExhaustiveCase(pipelineContext: PipelineContext) {
  private lazy val module = pipelineContext.module
  private lazy val subtype = pipelineContext.subtype
  private lazy val util = pipelineContext.util
  private lazy val instantiate = pipelineContext.instantiate
  private lazy val diagnosticsInfo = pipelineContext.diagnosticsInfo
  private implicit val pipelineCtx: PipelineContext = pipelineContext

  private case class NonExhaustiveFunction(pos: Pos, id: String, uncovered: Type) extends Diagnostic {
    override val msg: String = s"Function $id does not handle: ${show(uncovered)}"
    override val errorName: String = "non_exhaustive_function"
    override val erroneousExpr: Option[Expr] = None
  }

  private case class NonExhaustiveFunctionText(pos: Pos, id: String, uncovered: String) extends Diagnostic {
    override val msg: String = s"Function $id does not handle: $uncovered"
    override val errorName: String = "non_exhaustive_function"
    override val erroneousExpr: Option[Expr] = None
  }

  private case class NonExhaustiveCaseText(pos: Pos, uncovered: String) extends Diagnostic {
    override val msg: String = s"Case expression does not handle: $uncovered"
    override val errorName: String = "non_exhaustive_case"
    override val erroneousExpr: Option[Expr] = None
  }

  private case class SkippedExhaustivenessCheck(pos: Pos, subject: String, reason: String) extends Diagnostic {
    override val msg: String = s"Skipped exhaustiveness check for $subject: $reason"
    override val errorName: String = "skipped_exhaustiveness_check"
    override val erroneousExpr: Option[Expr] = None
  }

  private sealed trait CoverageResult
  private case class Covered(uncovered: List[Type], uncoveredText: Option[String] = None) extends CoverageResult
  private case class Unsupported(reason: String) extends CoverageResult

  private sealed trait BinaryPart { def rendered: String }
  private case object EmptyBinary extends BinaryPart { val rendered = "<<>>" }
  private case object NonEmptyBinary extends BinaryPart { val rendered = "<<_, _/binary>>" }
  private val allBinaryParts: Set[BinaryPart] = Set(EmptyBinary, NonEmptyBinary)
  private val maxProductArity = 4
  private val maxProductCells = 128

  private val simpleUnaryPredicates: Map[String, Type] =
    Map(
      "is_atom" -> AtomType,
      "is_binary" -> BinaryType,
      "is_bitstring" -> BinaryType,
      "is_boolean" -> UnionType(Set(falseType, trueType)),
      "is_float" -> floatType,
      "is_function" -> AnyFunType,
      "is_integer" -> NumberType,
      "is_list" -> ListType(AnyType),
      "is_number" -> NumberType,
      "is_pid" -> PidType,
      "is_port" -> PortType,
      "is_reference" -> ReferenceType,
      "is_map" -> MapType(Map(), AnyType, AnyType),
      "is_tuple" -> AnyTupleType,
    )

  def checkFun(f: FunDecl, spec: FunSpec): Unit = {
    val (_, ft) = instantiate.instantiate(spec.ty)
    val FunType(_, argTys, _) = ft
    checkFunctionClauses(f, argTys)
    for (clause <- f.clauses) {
      val env = clauseEnv(clause, argTys).getOrElse(Map.empty)
      checkBody(clause.body, env)
    }
  }

  def check(c: Case, selType: Type): Unit =
    coverageFor(
      selType,
      c.clauses,
      clause => clause.pats.headOption,
      selectorAliases(c.expr),
    ) match {
      case Covered(_, Some(uncovered)) =>
        diagnosticsInfo.add(NonExhaustiveCaseText(c.pos, uncovered))
      case Covered(uncovered, None) if uncovered.nonEmpty =>
        diagnosticsInfo.add(NonExhaustiveCase(c.pos, toType(uncovered)))
      case Unsupported(reason) =>
        diagnosticsInfo.add(SkippedExhaustivenessCheck(c.pos, "case expression", reason))
      case _ =>
        ()
    }

  private def checkFunctionClauses(f: FunDecl, argTys: List[Type]): Unit =
    functionCoverage(f, argTys) match {
      case Covered(_, Some(uncovered)) =>
        diagnosticsInfo.add(NonExhaustiveFunctionText(f.pos, f.id.toString, uncovered))
      case Covered(uncovered, None) if uncovered.nonEmpty =>
        diagnosticsInfo.add(NonExhaustiveFunction(f.pos, f.id.toString, toType(uncovered)))
      case Unsupported(reason) =>
        diagnosticsInfo.add(SkippedExhaustivenessCheck(f.pos, s"function ${f.id}", reason))
      case _ =>
        ()
    }

  private def checkBody(body: Body, env: Env): Unit =
    body.exprs.foreach(checkExpr(_, env))

  private def checkExpr(expr: Expr, env: Env): Unit =
    expr match {
      case c @ Case(selector, clauses) =>
        selectorType(selector, env) match {
          case Some(selType) =>
            tupleSelectorCatchAllCoverage(selector, clauses) match {
              case Some(Covered(_, _)) =>
                ()
              case Some(Unsupported(reason)) =>
                diagnosticsInfo.add(SkippedExhaustivenessCheck(c.pos, "case expression", reason))
              case None =>
                check(c, selType)
            }
          case None =>
            diagnosticsInfo.add(
              SkippedExhaustivenessCheck(c.pos, "case expression", "selector type is not known")
            )
        }
        checkExpr(selector, env)
        clauses.foreach(clause => checkBody(clause.body, env))
      case Block(body) =>
        checkBody(body, env)
      case Match(PatVar(_), value) =>
        checkExpr(value, env)
      case Match(_, value) =>
        checkExpr(value, env)
      case Tuple(elems) =>
        elems.foreach(checkExpr(_, env))
      case LocalCall(_, args) =>
        args.foreach(checkExpr(_, env))
      case RemoteCall(_, args) =>
        args.foreach(checkExpr(_, env))
      case DynCall(fun, args) =>
        checkExpr(fun, env)
        args.foreach(checkExpr(_, env))
      case Lambda(clauses) =>
        clauses.foreach(clause => checkBody(clause.body, env))
      case UnOp(_, arg) =>
        checkExpr(arg, env)
      case BinOp(_, arg1, arg2) =>
        checkExpr(arg1, env)
        checkExpr(arg2, env)
      case LComprehension(template, _) =>
        checkExpr(template, env)
      case BComprehension(template, _) =>
        checkExpr(template, env)
      case MComprehension(kTemplate, vTemplate, _) =>
        checkExpr(kTemplate, env)
        checkExpr(vTemplate, env)
      case Binary(elems) =>
        elems.foreach(elem => checkExpr(elem.expr, env))
      case Catch(arg) =>
        checkExpr(arg, env)
      case TryCatchExpr(tryBody, catchClauses, afterBody) =>
        checkBody(tryBody, env)
        catchClauses.foreach(clause => checkBody(clause.body, env))
        afterBody.foreach(checkBody(_, env))
      case TryOfCatchExpr(tryBody, tryClauses, catchClauses, afterBody) =>
        checkBody(tryBody, env)
        tryClauses.foreach(clause => checkBody(clause.body, env))
        catchClauses.foreach(clause => checkBody(clause.body, env))
        afterBody.foreach(checkBody(_, env))
      case Receive(clauses) =>
        clauses.foreach(clause => checkBody(clause.body, env))
      case ReceiveWithTimeout(clauses, timeout, timeoutBody) =>
        clauses.foreach(clause => checkBody(clause.body, env))
        checkExpr(timeout, env)
        checkBody(timeoutBody, env)
      case Maybe(body) =>
        checkBody(body, env)
      case MaybeElse(body, elseClauses) =>
        checkBody(body, env)
        elseClauses.foreach(clause => checkBody(clause.body, env))
      case MaybeMatch(_, arg) =>
        checkExpr(arg, env)
      case RecordCreate(_, fields) =>
        fields.foreach(field => checkExpr(field.value, env))
      case RecordUpdate(value, _, fields) =>
        checkExpr(value, env)
        fields.foreach(field => checkExpr(field.value, env))
      case RecordSelect(value, _, _) =>
        checkExpr(value, env)
      case MapCreate(kvs) =>
        kvs.foreach { case (k, v) =>
          checkExpr(k, env)
          checkExpr(v, env)
        }
      case MapUpdate(map, kvs) =>
        checkExpr(map, env)
        kvs.foreach { case (k, v) =>
          checkExpr(k, env)
          checkExpr(v, env)
        }
      case TypeCast(value, _, _) =>
        checkExpr(value, env)
      case _ =>
        ()
    }

  private def clauseEnv(clause: Clause, argTys: List[Type]): Option[Env] =
    if (clause.pats.size != argTys.size) None
    else {
      val entries = clause.pats.lazyZip(argTys).flatMap {
        case (PatVar(v), ty) => Some(v -> ty)
        case _              => None
      }
      Some(entries.toMap)
    }

  private def selectorType(expr: Expr, env: Env): Option[Type] =
    expr match {
      case Var(v) =>
        env.get(v)
      case Tuple(elems) =>
        val elemTys = elems.map(selectorType(_, env))
        if (elemTys.forall(_.isDefined)) Some(TupleType(elemTys.flatten))
        else None
      case LocalCall(id, _) =>
        Some(instantiatedResultType(util.getFunType(module, id)))
      case RemoteCall(id, _) =>
        Some(instantiatedResultType(util.getFunType(id)))
      case DynCall(fun, args) =>
        selectorType(fun, env).flatMap(funResultType(_, args.size))
      case _ =>
        None
    }

  private def instantiatedResultType(ft: FunType): Type = {
    val (_, instantiated) = instantiate.instantiate(ft)
    instantiated.resTy
  }

  private def funResultType(ty: Type, arity: Int): Option[Type] =
    ty match {
      case ft: FunType if ft.argTys.size == arity =>
        Some(instantiatedResultType(ft))
      case AnyArityFunType(resTy) =>
        Some(resTy)
      case RemoteType(rid, args) =>
        funResultType(util.getTypeDeclBody(rid, args), arity)
      case BoundedDynamicType(bound) =>
        funResultType(bound, arity)
      case UnionType(tys) =>
        val resultTys = tys.toList.flatMap(funResultType(_, arity))
        if (resultTys.isEmpty) None else Some(subtype.join(resultTys))
      case _ =>
        None
    }

  private def functionCoverage(f: FunDecl, argTys: List[Type]): CoverageResult = {
    if (f.clauses.isEmpty || argTys.isEmpty) Covered(Nil)
    else if (f.clauses.exists(_.pats.size != argTys.size))
      Unsupported("clause arity does not match the function spec")
    else {
      multiArgumentCatchAllCoverage(f, argTys.size) match {
        case Some(result) =>
          result
        case None if argTys.size > 1 =>
          productSpace(argTys) match {
            case Right(cells) =>
              productCoverage(f, cells)
            case Left(productReason) =>
              singleInterestCoverage(f, argTys) match {
                case Unsupported("function clauses are not in the supported single-interesting-argument form") =>
                  Unsupported(productReason)
                case other =>
                  other
              }
          }
        case None =>
          singleInterestCoverage(f, argTys)
      }
    }
  }

  private def singleInterestCoverage(f: FunDecl, argTys: List[Type]): CoverageResult = {
    val attempts = argTys.indices.toList.flatMap { idx =>
      if (otherArgumentsAreVariablesOrWildcards(f.clauses, idx))
        Some(idx -> coverageFor(argTys(idx), f.clauses, clause => clause.pats.lift(idx), Set.empty))
      else None
    }
    attempts.collectFirst { case (_, covered: Covered) => covered } match {
      case Some(covered) =>
        covered
      case None =>
        attempts.collectFirst { case (idx, Unsupported(reason)) =>
          Unsupported(s"argument ${idx + 1}: $reason")
        }.getOrElse(Unsupported("function clauses are not in the supported single-interesting-argument form"))
    }
  }

  private def productSpace(argTys: List[Type]): Either[String, List[List[Type]]] =
    if (argTys.size > maxProductArity) Left(s"product-space arity ${argTys.size} exceeds limit $maxProductArity")
    else {
      val alternatives = argTys.map(simpleAlternatives)
      if (!alternatives.forall(_.isDefined)) Left("product space includes an argument type outside the supported flat-union subset")
      else {
        val dimensions = alternatives.flatten
        val cellCount = dimensions.map(_.size).product
        if (cellCount > maxProductCells) Left(s"product space has $cellCount cells, above limit $maxProductCells")
        else Right(cartesianProduct(dimensions))
      }
    }

  private def cartesianProduct[T](dimensions: List[List[T]]): List[List[T]] =
    dimensions.foldRight(List(List.empty[T])) { (dimension, acc) =>
      for {
        value <- dimension
        rest <- acc
      } yield value :: rest
    }

  private def productCoverage(f: FunDecl, cells: List[List[Type]]): CoverageResult = {
    var remaining = cells
    var unsupported: Option[String] = None
    for (clause <- f.clauses if unsupported.isEmpty) {
      productCoveredCells(clause, remaining) match {
        case Some(covered) =>
          remaining = remaining.filterNot(covered.contains)
        case None =>
          unsupported = Some("product-space clause is outside the supported pattern subset")
      }
    }
    unsupported match {
      case Some(reason) =>
        Unsupported(reason)
      case None if remaining.isEmpty =>
        Covered(Nil)
      case None =>
        Covered(Nil, Some(remaining.map(renderProductCell).mkString(" | ")))
    }
  }

  private def productCoveredCells(clause: Clause, cells: List[List[Type]]): Option[List[List[Type]]] = {
    if (clause.guards.nonEmpty) None
    else {
      val covers = clause.pats.map(simplePatternCover)
      if (!covers.forall(_.isDefined)) None
      else {
        val patternCovers = covers.flatten
        Some(cells.filter(cell => productCellCovered(cell, patternCovers)))
      }
    }
  }

  private def productCellCovered(cell: List[Type], patternCovers: List[PatternCover]): Boolean =
    cell.size == patternCovers.size && cell.lazyZip(patternCovers).forall { case (cellTy, cover) =>
      subtype.subType(cellTy, cover.ty)
    }

  private def renderProductCell(cell: List[Type]): String =
    cell.map(show).mkString("(", ", ", ")")

  private def multiArgumentCatchAllCoverage(f: FunDecl, arity: Int): Option[CoverageResult] =
    if (arity <= 1 || !finalUnguardedFunctionCatchAll(f.clauses, arity)) None
    else {
      val nonFinalClauses = f.clauses.dropRight(1)
      if (nonFinalClauses.forall(supportedMultiArgumentClause(_, arity))) Some(Covered(Nil))
      else Some(Unsupported("multi-argument function has non-final clause outside the supported pattern subset"))
    }

  private def finalUnguardedFunctionCatchAll(clauses: List[Clause], arity: Int): Boolean =
    clauses.lastOption.exists(clause =>
      clause.guards.isEmpty && clause.pats.size == arity && clause.pats.forall(isAnyPattern)
    )

  private def supportedMultiArgumentClause(clause: Clause, arity: Int): Boolean =
    clause.pats.size == arity && clause.pats.forall(pat => simplePatternCover(pat).isDefined)

  private def tupleSelectorCatchAllCoverage(selector: Expr, clauses: List[Clause]): Option[CoverageResult] =
    selector match {
      case Tuple(elems) if finalUnguardedCatchAll(clauses, clause => clause.pats.headOption) =>
        val arity = elems.size
        val nonFinalClauses = clauses.dropRight(1)
        if (nonFinalClauses.forall(supportedTupleSelectorClause(_, arity))) Some(Covered(Nil))
        else Some(Unsupported("tuple selector case has non-final pattern outside the supported tuple-pattern subset"))
      case _ =>
        None
    }

  private def supportedTupleSelectorClause(clause: Clause, arity: Int): Boolean =
    clause.pats match {
      case pat :: Nil => supportedTupleSelectorPattern(pat, arity)
      case _          => false
    }

  private def supportedTupleSelectorPattern(pat: Pat, arity: Int): Boolean =
    pat match {
      case PatTuple(elems) if elems.size == arity =>
        simplePatternCover(pat).isDefined
      case _ =>
        false
    }

  private def otherArgumentsAreVariablesOrWildcards(clauses: List[Clause], selectedIndex: Int): Boolean =
    clauses.forall { clause =>
      clause.pats.zipWithIndex.forall { case (pat, idx) =>
        idx == selectedIndex || isAnyPattern(pat)
      }
    }

  private def isAnyPattern(pat: Pat): Boolean =
    pat match {
      case PatWild() | PatVar(_) => true
      case _                     => false
    }

  private def coverageFor(
      selType: Type,
      clauses: List[Clause],
      selectedPattern: Clause => Option[Pat],
      extraAliases: Set[String],
  ): CoverageResult =
    if (hasUnguardedCatchAll(clauses, selectedPattern)) Covered(Nil)
    else
      binaryCoverageFor(selType, clauses, selectedPattern) match {
        case Some(result) =>
          result
        case None =>
          simpleAlternatives(selType).map(_.distinct) match {
            case None =>
              Unsupported("scrutinee type is outside the supported flat-union subset")
            case Some(alternatives) =>
              var remaining = alternatives
              var unsupported: Option[String] = None
              for (clause <- clauses if unsupported.isEmpty) {
                selectedPattern(clause) match {
                  case None =>
                    unsupported = Some("clause shape is unsupported")
                  case Some(pat) =>
                    simplePatternCover(pat) match {
                      case None =>
                        unsupported = Some("pattern is outside the supported subset")
                      case Some(PatternCover(patTy, aliases)) =>
                        simpleGuardCover(clause.guards, aliases ++ extraAliases) match {
                          case None =>
                            unsupported = Some("guard is outside the supported subset")
                          case Some(guardTy) =>
                            val covered = remaining.filter(alt => subtype.subType(alt, patTy) && subtype.subType(alt, guardTy))
                            remaining = remaining.filterNot(covered.contains)
                        }
                    }
                }
              }
              unsupported match {
                case Some(reason) => Unsupported(reason)
                case None         => Covered(remaining)
              }
          }
      }

  private def binaryCoverageFor(
      selType: Type,
      clauses: List[Clause],
      selectedPattern: Clause => Option[Pat],
  ): Option[CoverageResult] = {
    val selectedPatterns = clauses.flatMap(selectedPattern)
    if (!isBinaryScrutinee(selType) || !selectedPatterns.exists(_.isInstanceOf[PatBinary])) None
    else {
      var remaining = allBinaryParts
      var unsupported: Option[String] = None
      for (clause <- clauses if unsupported.isEmpty) {
        if (clause.guards.nonEmpty) unsupported = Some("binary-pattern guards are outside the supported subset")
        else {
          selectedPattern(clause) match {
            case None =>
              unsupported = Some("clause shape is unsupported")
            case Some(pat) =>
              binaryPatternCover(pat) match {
                case None =>
                  unsupported = Some("binary pattern is outside the supported subset")
                case Some(covered) =>
                  remaining = remaining -- covered
              }
          }
        }
      }
      unsupported match {
        case Some(reason) =>
          Some(Unsupported(reason))
        case None if remaining.isEmpty =>
          Some(Covered(Nil))
        case None =>
          Some(Covered(Nil, Some(remaining.toList.map(_.rendered).sorted.mkString(" | "))))
      }
    }
  }

  private def isBinaryScrutinee(t: Type): Boolean =
    t match {
      case BinaryType =>
        true
      case RemoteType(rid, args) =>
        isBinaryScrutinee(util.getTypeDeclBody(rid, args))
      case _ =>
        false
    }

  private def binaryPatternCover(pat: Pat): Option[Set[BinaryPart]] =
    pat match {
      case PatBinary(Nil) =>
        Some(Set(EmptyBinary))
      case PatBinary(PatBinaryElem(pat, None, spec) :: Nil) if isAnyPattern(pat) && isOpenBinarySpecifier(spec) =>
        Some(allBinaryParts)
      case PatBinary(first :: tail :: Nil) if isAnyByteSegment(first) && isOpenBinaryTail(tail) =>
        Some(Set(NonEmptyBinary))
      case _ =>
        None
    }

  private def isAnyByteSegment(elem: PatBinaryElem): Boolean =
    isAnyPattern(elem.pat) && isByteIntegerSpecifier(elem.specifier) && isImplicitOrEightBitSize(elem.size)

  private def isOpenBinaryTail(elem: PatBinaryElem): Boolean =
    isAnyPattern(elem.pat) && elem.size.isEmpty && isOpenBinarySpecifier(elem.specifier)

  private def isByteIntegerSpecifier(spec: com.whatsapp.eqwalizer.ast.Specifier): Boolean =
    spec match {
      case SignedIntegerSpecifier | UnsignedIntegerSpecifier => true
      case _                                                 => false
    }

  private def isOpenBinarySpecifier(spec: com.whatsapp.eqwalizer.ast.Specifier): Boolean =
    spec match {
      case BinarySpecifier | BytesSpecifier => true
      case _                                => false
    }

  private def isImplicitOrEightBitSize(size: Option[Expr]): Boolean =
    size match {
      case None                  => true
      case Some(IntLit(Some(8))) => true
      case _                     => false
    }

  private def hasUnguardedCatchAll(clauses: List[Clause], selectedPattern: Clause => Option[Pat]): Boolean =
    clauses.exists(clause => clause.guards.isEmpty && selectedPattern(clause).exists(isAnyPattern))

  private def finalUnguardedCatchAll(clauses: List[Clause], selectedPattern: Clause => Option[Pat]): Boolean =
    clauses.lastOption.exists(clause => clause.guards.isEmpty && selectedPattern(clause).exists(isAnyPattern))

  private def selectorAliases(expr: Expr): Set[String] =
    expr match {
      case Var(n)              => Set(n)
      case Match(PatVar(n), _) => Set(n)
      case _                   => Set.empty
    }

  private case class PatternCover(ty: Type, aliases: Set[String])

  private def simplePatternCover(pat: Pat): Option[PatternCover] =
    pat match {
      case PatWild() =>
        Some(PatternCover(AnyType, Set.empty))
      case PatVar(n) =>
        Some(PatternCover(AnyType, Set(n)))
      case PatAtom(s) =>
        Some(PatternCover(AtomLitType(s), Set.empty))
      case PatNil() =>
        Some(PatternCover(NilType, Set.empty))
      case PatInt() | PatNumber() =>
        Some(PatternCover(NumberType, Set.empty))
      case PatTuple(elems) =>
        val elemCovers = elems.map(simplePatternCover)
        if (elemCovers.forall(_.isDefined))
          Some(PatternCover(TupleType(elemCovers.flatten.map(_.ty)), Set.empty))
        else None
      case PatRecord(recName, Nil, None) =>
        Some(PatternCover(RecordType(recName)(module), Set.empty))
      case PatMatch(PatVar(alias), pat1) =>
        simplePatternCover(pat1).map(cover => cover.copy(aliases = cover.aliases + alias))
      case PatMatch(pat1, PatVar(alias)) =>
        simplePatternCover(pat1).map(cover => cover.copy(aliases = cover.aliases + alias))
      case _ =>
        None
    }

  private def simpleGuardCover(guards: List[Guard], aliases: Set[String]): Option[Type] =
    guards match {
      case Nil =>
        Some(AnyType)
      case Guard(List(test)) :: Nil =>
        simpleTestCover(test, aliases)
      case _ =>
        None
    }

  private def simpleTestCover(test: Test, aliases: Set[String]): Option[Type] =
    test match {
      case TestCall(Id(pred, 1), List(TestVar(v))) if aliases(v) && simpleUnaryPredicates.isDefinedAt(pred) =>
        Some(simpleUnaryPredicates(pred))
      case TestCall(Id("is_record", 2), List(TestVar(v), TestAtom(recName))) if aliases(v) =>
        Some(RecordType(recName)(module))
      case _ =>
        None
    }

  private def simpleAlternatives(t: Type): Option[List[Type]] =
    t match {
      case RemoteType(rid, args) =>
        simpleAlternatives(util.getTypeDeclBody(rid, args))
      case UnionType(ts) if ts.size <= 10 =>
        val expanded = ts.toList.map(simpleAlternatives)
        if (expanded.forall(_.isDefined)) Some(expanded.flatten.flatten)
        else None
      case BoundedDynamicType(_) | DynamicType | AnyType =>
        None
      case AtomLitType(_) | AtomType | NilType | NumberType | BinaryType | PidType | PortType | ReferenceType | AnyFunType =>
        Some(List(t))
      case t: TupleType =>
        Some(List(t))
      case r: RecordType =>
        Some(List(r))
      case r: RefinedRecordType =>
        Some(List(r))
      case ListType(_) =>
        Some(List(t))
      case MapType(_, _, _) =>
        Some(List(t))
      case _ =>
        None
    }

  private def toType(tys: List[Type]): Type =
    tys match {
      case Nil       => NoneType
      case ty :: Nil => ty
      case _         => UnionType(tys.toSet)
    }
}