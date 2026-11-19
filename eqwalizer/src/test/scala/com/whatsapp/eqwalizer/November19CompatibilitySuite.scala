package com.whatsapp.eqwalizer

import com.whatsapp.eqwalizer.ast.{Id, Pos}
import com.whatsapp.eqwalizer.ast.Exprs.*
import com.whatsapp.eqwalizer.ast.Forms.{FunDecl, FunSpec}
import com.whatsapp.eqwalizer.ast.Pats.*
import com.whatsapp.eqwalizer.ast.Types.*
import com.whatsapp.eqwalizer.tc.PipelineContext

class November19CompatibilitySuite extends munit.FunSuite {
  private val pos = Pos.TextRange(0, 1)
  private val ab = UnionType(Set(AtomLitType("a"), AtomLitType("b")))
  private def clause(pat: ClausePat, expr: Expr): Clause = Clause(List(pat), Nil, Body(List(expr)))(pos)
  private def static(pat: Pat): ClausePat = SPat(pat)(pos)
  private def missingCase: Case = Case(
    Var("X")(pos),
    List(
      clause(static(PatAtom("a")(pos)), AtomLit("ok")(pos))
    ),
  )(pos)
  private def check(expr: Expr, pat: ClausePat = SPat(PatVar("X")(pos))(pos)): List[String] = {
    val ctx = PipelineContext("november19")
    val id = Id("f", 1)
    ctx.exhaustiveCase.checkFun(FunDecl(id, List(clause(pat, expr)))(pos), FunSpec(id, FunType(0, List(ab), AnyType)))
    ctx.diagnosticsInfo.popErrors().map(_.errorName)
  }

  test("dynamic function patterns skip instead of using static spec coverage") {
    assertEquals(check(AtomLit("ok")(pos), DPat(PatVar("X")(pos))(pos)), List("skipped_exhaustiveness_check"))
  }
  test("a dynamic variable does not inherit the narrower static argument type") {
    assertEquals(
      check(missingCase, DPat(PatVar("X")(pos))(pos)),
      List("skipped_exhaustiveness_check", "skipped_exhaustiveness_check"),
    )
  }
  test("a static case catch-all does not bypass the dynamic-pattern skip") {
    val expr = Case(
      Var("X")(pos),
      List(
        clause(DPat(PatAtom("a")(pos))(pos), AtomLit("ok")(pos)),
        clause(static(PatWild()(pos)), AtomLit("ok")(pos)),
      ),
    )(pos)
    assertEquals(check(expr), List("skipped_exhaustiveness_check"))
  }
  test("the tuple catch-all path also respects dynamic-pattern skips") {
    val expr = Case(
      Tuple(List(Var("X")(pos)))(pos),
      List(
        clause(DPat(PatTuple(List(PatAtom("a")(pos)))(pos))(pos), AtomLit("ok")(pos)),
        clause(static(PatWild()(pos)), AtomLit("ok")(pos)),
      ),
    )(pos)
    assertEquals(check(expr), List("skipped_exhaustiveness_check"))
  }
  test("all list-comprehension templates are traversed") {
    assertEquals(check(LComprehension(List(AtomLit("ok")(pos), missingCase), Nil)(pos)), List("non_exhaustive_case"))
  }
  test("all map-comprehension keys and values are traversed") {
    // Distinct source ranges prevent the normal diagnostic deduplication from
    // merging the two intentionally identical missing-case messages.
    val missingKey = Case(missingCase.expr, missingCase.clauses)(Pos.TextRange(10, 11))
    val templates = List((AtomLit("first")(pos), missingCase), (missingKey, AtomLit("last")(pos)))
    assertEquals(check(MComprehension(templates, Nil)(pos)), List("non_exhaustive_case", "non_exhaustive_case"))
  }
}
