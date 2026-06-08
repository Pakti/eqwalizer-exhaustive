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
import com.whatsapp.eqwalizer.ast.Types.*
import com.whatsapp.eqwalizer.ast.Id
import com.whatsapp.eqwalizer.tc.TcDiagnostics.NonExhaustiveCase

final class ExhaustiveCase(pipelineContext: PipelineContext) {
  private lazy val module = pipelineContext.module
  private lazy val subtype = pipelineContext.subtype
  private lazy val util = pipelineContext.util
  private lazy val instantiate = pipelineContext.instantiate
  private lazy val diagnosticsInfo = pipelineContext.diagnosticsInfo
  private implicit val pipelineCtx: PipelineContext = pipelineContext

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
    for (clause <- f.clauses) {
      val env = clauseEnv(clause, argTys).getOrElse(Map.empty)
      checkBody(clause.body, env)
    }
  }

  def check(c: Case, selType: Type): Unit =
    uncovered(c, selType) match {
      case Some(uncovered) if uncovered.nonEmpty =>
        diagnosticsInfo.add(NonExhaustiveCase(c.pos, toType(uncovered)))
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
      case Case(_, clauses) =>
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

  private def uncovered(c: Case, selType: Type): Option[List[Type]] =
    simpleAlternatives(selType).map(_.distinct).flatMap { alternatives =>
      var remaining = alternatives
      val selAlias = c.expr match {
        case Var(n)              => Some(n)
        case Match(PatVar(n), _) => Some(n)
        case _                   => None
      }
      val supported = c.clauses.forall {
        case Clause(pat :: Nil, guards, _) =>
          simplePatternCover(pat).flatMap { case PatternCover(patTy, aliases) =>
            simpleGuardCover(guards, aliases ++ selAlias).map { guardTy =>
              val covered = remaining.filter(alt => subtype.subType(alt, patTy) && subtype.subType(alt, guardTy))
              remaining = remaining.filterNot(covered.contains)
            }
          }.isDefined
        case _ =>
          false
      }
      if (supported) Some(remaining) else None
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
