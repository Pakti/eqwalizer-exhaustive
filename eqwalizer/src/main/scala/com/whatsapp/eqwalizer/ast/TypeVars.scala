/* Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.
 *
 * This source code is licensed under the Apache 2.0 license found in
 * the LICENSE file in the root directory of this source tree.
 */

package com.whatsapp.eqwalizer.ast

import com.whatsapp.eqwalizer.ast.Types.*

object TypeVars {
  type Var = Int

  class Instantiate {
    private var shift = 0

    def instantiate(ft: FunType): (List[Var], FunType) =
      if (ft.forall.isEmpty) (Nil, ft)
      else {
        val oldVars = ft.forall
        val newVars = (shift until (shift + oldVars.size)).toList
        val subst = oldVars.lazyZip(newVars).toMap
        val res = FunType(Nil, ft.argTys.map(substVars(subst)), substVars(subst)(ft.resTy))
        shift = shift + oldVars.size
        (newVars, res)
      }
  }

  enum ElimMode {
    case Promote, Demote

    def switch: ElimMode = this match {
      case Promote => Demote
      case Demote  => Promote
    }

    def toType: Type = this match {
      case Promote => AnyType
      case Demote  => NoneType
    }
  }

  def freeVars(ty: Type): Set[Var] = ty match {
    case VarType(n)     => Set(n)
    case FreeVarType(n) => Set(n)
    case _              => children(ty).flatMap(freeVars).toSet
  }

  /** note: returns Nil for record types because they can't have type vars
    */
  def children(ty: Type): List[Type] = ty match {
    case FunType(_, argTys, resTy)    => resTy :: argTys
    case AnyArityFunType(resTy)       => resTy :: Nil
    case TupleType(argTys)            => argTys
    case UnionType(tys)               => tys.toList
    case RemoteType(_, tys)           => tys
    case MapType(props, kType, vType) => kType :: vType :: props.values.map(_.tp).toList
    case ListType(ty)                 => ty :: Nil
    case RefinedRecordType(_, fields) => fields.toList.map(_._2)
    case BoundedDynamicType(bound)    => bound :: Nil
    case _                            => Nil
  }

  def conformForalls(ft1: FunType, ft2: FunType): Option[(FunType, FunType)] =
    if (ft1.forall.size != ft2.forall.size || ft1.argTys.size != ft2.argTys.size) None
    else Some(ft1, ft2)

  private def substVars(subst: Map[Var, Var])(t: Type): Type = t match {
    case vt: VarType =>
      subst.get(vt.n).map(n => VarType(n)(vt.name)).getOrElse(vt)
    case vt: FreeVarType =>
      subst.get(vt.n).map(n => FreeVarType(n)(vt.name)).getOrElse(vt)
    case bv: BoundVarType =>
      subst.get(bv.lvl).map(n => VarType(n)(bv.name)).getOrElse(bv)
    case FunType(forall, args, resType) =>
      FunType(forall, args.map(substVars(subst)), substVars(subst)(resType))
    case AnyArityFunType(resType) =>
      AnyArityFunType(substVars(subst)(resType))
    case TupleType(params) =>
      TupleType(params.map(substVars(subst)))
    case ListType(elemT) =>
      ListType(substVars(subst)(elemT))
    case UnionType(params) =>
      UnionType(params.map(substVars(subst)))
    case RemoteType(id, params) =>
      RemoteType(id, params.map(substVars(subst)))
    case MapType(props, kt, vt) =>
      MapType(
        props.map { case (key, Prop(req, tp)) => (key, Prop(req, substVars(subst)(tp))) },
        substVars(subst)(kt),
        substVars(subst)(vt),
      )
    case RefinedRecordType(recType, fields) =>
      RefinedRecordType(recType, fields.map(f => f._1 -> substVars(subst)(f._2)))
    case BoundedDynamicType(bound) =>
      BoundedDynamicType(substVars(subst)(bound))
    case _ =>
      t
  }

  private def containsVars(ty: Type, tv: Set[Var]): Boolean = ty match {
    case VarType(n)     => tv(n)
    case FreeVarType(n) => tv(n)
    case ty             => TypeVars.children(ty).exists(containsVars(_, tv))
  }

  def promote(ty: Type, vars: Set[Var]): Type =
    elimTypeVars(ty, ElimMode.Promote, vars)

  def demote(ty: Type, vars: Set[Var]): Type =
    elimTypeVars(ty, ElimMode.Demote, vars)

  private def elimTypeVars(ty: Type, mode: ElimMode, vars: Set[Var]): Type = {
    def elim(t: Type): Type = elimTypeVars(t, mode, vars)

    ty match {
      case FunType(forall, args, resType) =>
        val args1 = args.map(elimTypeVars(_, mode.switch, vars))
        FunType(forall, args1, elim(resType))
      case AnyArityFunType(resType) =>
        AnyArityFunType(elim(resType))
      case TupleType(params) =>
        TupleType(params.map(elim))
      case ListType(elemT) =>
        ListType(elim(elemT))
      case UnionType(params) =>
        UnionType(params.map(elim))
      case RemoteType(id, params) =>
        val variances = Variance.paramVariances(id)
        val elimmedParams = params.lazyZip(variances).map {
          case (param, Variance.Constant | Variance.Covariant) => elimTypeVars(param, mode, vars)
          case (param, Variance.Contravariant)                 => elimTypeVars(param, mode.switch, vars)
          case (param, Variance.Invariant) =>
            if (containsVars(param, vars)) mode.toType
            else param
        }
        RemoteType(id, elimmedParams)
      case VarType(v) if vars.contains(v) =>
        mode.toType
      case FreeVarType(v) if vars.contains(v) =>
        mode.toType
      case vt: VarType =>
        vt
      case vt: FreeVarType =>
        vt
      case MapType(props, kt, vt) =>
        MapType(props.map { case (key, Prop(req, tp)) => (key, Prop(req, elim(tp))) }, elim(kt), elim(vt))
      case BoundedDynamicType(bound) =>
        BoundedDynamicType(elim(bound))
      case _ =>
        ty
    }
  }
}
