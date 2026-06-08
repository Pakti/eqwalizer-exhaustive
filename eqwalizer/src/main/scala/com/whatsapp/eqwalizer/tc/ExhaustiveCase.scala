/* Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.
 *
 * This source code is licensed under the Apache 2.0 license found in
 * the root directory of this source tree.
 */

package com.whatsapp.eqwalizer.tc

import com.whatsapp.eqwalizer.ast.Exprs.*
import com.whatsapp.eqwalizer.ast.Guards.*
import com.whatsapp.eqwalizer.ast.Pats.*
import com.whatsapp.eqwalizer.ast.Types.*
import com.whatsapp.eqwalizer.ast.Id
import com.whatsapp.eqwalizer.tc.TcDiagnostics.NonExhaustiveCase

final class ExhaustiveCase(pipelineContext: PipelineContext) {
  private lazy val module = pipelineContext.module
  private lazy val subtype = pipelineContext.subtype
  private lazy val util = pipelineContext.util
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

  def check(c: Case, selType: Type): Unit =
    uncovered(c, selType) match {
      case Some(uncovered) if uncovered.nonEmpty =>
        diagnosticsInfo.add(NonExhaustiveCase(c.pos, toType(uncovered)))
      case _ =>
        ()
    }

  private def uncovered(c: Case, selType: Type): Option[List[Type]] = {
    var remaining = simpleAlternatives(selType).map(_.distinct).getOrElse(return None)
    val selAlias = c.expr match {
      case Var(n)              => Some(n)
      case Match(PatVar(n), _) => Some(n)
      case _                   => None
    }
    for (clause <- c.clauses) {
      clause.pats match {
        case pat :: Nil =>
          val PatternCover(patTy, aliases) = simplePatternCover(pat).getOrElse(return None)
          val guardTy = simpleGuardCover(clause.guards, aliases ++ selAlias).getOrElse(return None)
          val covered = remaining.filter(alt => subtype.subType(alt, patTy) && subtype.subType(alt, guardTy))
          remaining = remaining.filterNot(covered.contains)
        case _ =>
          return None
      }
    }
    Some(remaining)
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
