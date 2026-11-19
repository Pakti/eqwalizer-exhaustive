package com.whatsapp.eqwalizer.tc

import com.whatsapp.eqwalizer.ast.Pos
import com.whatsapp.eqwalizer.tc.StrictDiagnostics.*

object StrictPrivateConstructors {
  def check(ctx: PipelineContext, pos: Pos, recordName: String): Unit =
    ctx.options.privateConstructorOwners.getOrElse(recordName, Set.empty).toList.sorted match {
      case Nil => ()
      case owner :: Nil if owner != ctx.module =>
        ctx.diagnosticsInfo.add(PrivateConstructorViolation(pos, recordName, owner))
      case _ :: Nil => ()
      case owners =>
        ctx.diagnosticsInfo.add(
          InvalidPrivateConstructor(pos, recordName, s"multiple owner modules: ${owners.mkString(", ")}")
        )
    }
}
