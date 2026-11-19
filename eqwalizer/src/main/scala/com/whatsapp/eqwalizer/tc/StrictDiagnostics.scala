package com.whatsapp.eqwalizer.tc

import com.whatsapp.eqwalizer.ast.Exprs.Expr
import com.whatsapp.eqwalizer.ast.Pos
import com.whatsapp.eqwalizer.ast.Show.show
import com.whatsapp.eqwalizer.ast.Types.Type
import com.whatsapp.eqwalizer.util.Diagnostic.Diagnostic

/** Fork diagnostics use ordinary messages; ELP has no structured variants for them. */
object StrictDiagnostics {
  trait Unstructured extends Diagnostic
  case class PrivateConstructorViolation(pos: Pos, recName: String, owner: String) extends Unstructured {
    override val msg: String =
      s"Cannot construct #$recName{} outside its private constructor owner module $owner"
    def errorName = "private_constructor_violation"
    override def erroneousExpr: Option[Expr] = None
  }
  case class InvalidPrivateConstructor(pos: Pos, recName: String, reason: String) extends Unstructured {
    override val msg: String =
      s"Invalid private constructor declaration for #$recName{}: $reason"
    def errorName = "invalid_private_constructor"
    override def erroneousExpr: Option[Expr] = None
  }
  case class NonExhaustiveCase(pos: Pos, uncovered: Type)(implicit val pipelineContext: PipelineContext)
      extends Unstructured {
    override val msg: String = s"Case expression does not handle: ${show(uncovered)}"
    val errorName = "non_exhaustive_case"
    override val erroneousExpr: Option[Expr] = None
  }
}
