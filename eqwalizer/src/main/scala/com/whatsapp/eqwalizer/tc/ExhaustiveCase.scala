/* Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.
 *
 * This source code is licensed under the Apache 2.0 license found in
 * the LICENSE file in the root directory of this source tree.
 */

package com.whatsapp.eqwalizer.tc

import com.whatsapp.eqwalizer.ast.Exprs.*
import com.whatsapp.eqwalizer.ast.Forms.{FunDecl, FunSpec}
import com.whatsapp.eqwalizer.ast.Guards.*
import com.whatsapp.eqwalizer.ast.Pats.*
import com.whatsapp.eqwalizer.ast.Show.show
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

  private case class SkippedExhaustivenessCheck(pos: Pos, subject: String, reason: String) extends Diagnostic {
    override val msg: String = s"Skipped exhaustiveness check for $subject: $reason"
    override val errorName: String = "skipped_exhaustiveness_check"
    override val erroneousExpr: Option[Expr] = None
  }

  private sealed trait CoverageResult
  private case class Covered(uncovered: List[Type]) extends CoverageResult
  private case class Unsupported(reason: String) extends CoverageResult

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
      case Covered(uncovered) if uncovered.nonEmpty =>
        diagnosticsInfo.add(NonExhaustiveCase(c.pos, toType(uncovered)))
      case Unsupported(reason) =>
        diagnosticsInfo.add(SkippedExhaustivenessCheck(c.pos, "case expression", reason))
      case _ =>
        ()
    }

  private def checkFunctionClauses(f: FunDecl, argTys: List[Type]): Unit =
    functionCoverage(f, argTys) match {
      case Covered(uncovered) if uncovered.nonEmpty =>
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
      case c @ Case(Var(v), _) if env.contains(v) =>
        check(c, env(v))
        c.clauses.foreach(clause => checkBody(clause.body, env))
      case c @ Case(Var(v), clauses) =>
        diagnosticsInfo.add(SkippedExhaustivenessCheck(c.pos, "case expression", s"selector variable $v has no known type"))
        clauses.foreach(clause => checkBody(clause.body, env))
      case c @ Case(_, clauses) =>
        diagnosticsInfo.add(
          SkippedExhaustivenessCheck(c.pos, "case expression", "selector is not a variable with a known type")
        )
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

  private def functionCoverage(f: FunDecl, argTys: List[Type]): CoverageResult = {
    if (f.clauses.isEmpty || argTys.isEmpty) Covered(Nil)
    else if (f.clauses.exists(_.pats.size != argTys.size))
      Unsupported("clause arity does not match the function spec")
    else {
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

  private def hasUnguardedCatchAll(clauses: List[Clause], selectedPattern: Clause => Option[Pat]): Boolean =
    clauses.exists(clause => clause.guards.isEmpty && selectedPattern(clause).exists(isAnyPattern))

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
      case AtomLitType(_) | NilType | NumberType | BinaryType | PidType | PortType | ReferenceType | AnyFunType =>
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
