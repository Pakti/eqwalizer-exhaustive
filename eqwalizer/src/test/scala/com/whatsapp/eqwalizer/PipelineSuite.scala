package com.whatsapp.eqwalizer

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToArray}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.whatsapp.eqwalizer.ast.{Id, Pos}
import com.whatsapp.eqwalizer.ast.Exprs.*
import com.whatsapp.eqwalizer.ast.Forms.*
import com.whatsapp.eqwalizer.ast.Pats.*
import com.whatsapp.eqwalizer.ast.Types.*
import com.whatsapp.eqwalizer.tc.{Options, PipelineContext}

class PipelineSuite extends munit.FunSuite {
  private val pos = Pos.TextRange(0, 1)
  private given JsonValueCodec[RecDecl] = JsonCodecMaker.make(
    CodecMakerConfig
      .withAllowRecursiveTypes(true)
      .withDiscriminatorFieldName(None)
      .withFieldNameMapper(JsonCodecMaker.enforce_snake_case)
  )
  private given JsonValueCodec[FunSpec] = JsonCodecMaker.make(
    CodecMakerConfig
      .withAllowRecursiveTypes(true)
      .withDiscriminatorFieldName(None)
      .withFieldNameMapper(JsonCodecMaker.enforce_snake_case)
  )
  private def withRecord[A](body: => A): A =
    IpcFixtures.replies(List("GetRecDeclReply" -> writeToArray(RecDecl("box", Nil, false))))(body)

  for (
    (module, owners, expected) <- List(
      ("owner", Set("owner"), List.empty[String]),
      ("client", Set("owner"), List("private_constructor_violation")),
      ("conflict", Set("a", "b"), List("invalid_private_constructor")),
      ("public", Set.empty[String], List.empty[String]),
    )
  ) {
    for (update <- List(false, true)) {
      test(s"private constructor: $module, update=$update") {
        val uniqueModule = module + (if (update) "_update" else "_create")
        val actualOwners = owners.map(o => if (o == module) uniqueModule else o)
        val ctx = PipelineContext(uniqueModule, Options(privateConstructorOwners = Map("box" -> actualOwners)))
        val expr = if (update) RecordUpdate(Var("R")(pos), "box", Nil)(pos) else RecordCreate("box", Nil)(pos)
        withRecord { ctx.elab.elabExpr(expr, Map("R" -> RecordType("box")(uniqueModule))) }
        assertEquals(ctx.diagnosticsInfo.popErrors().map(_.errorName), expected)
      }
    }
  }
  test("record pattern matching remains allowed outside owner") {
    val ctx = PipelineContext("pattern_client", Options(privateConstructorOwners = Map("box" -> Set("owner"))))
    withRecord { ctx.elabPat.elabPat(PatRecord("box", Nil, None)(pos), RecordType("box")("pattern_client"), Map.empty) }
    assertEquals(ctx.diagnosticsInfo.popErrors(), Nil)
  }
  test("record field access remains allowed outside owner") {
    val ctx = PipelineContext("field_client", Options(privateConstructorOwners = Map("box" -> Set("owner"))))
    val record = RecDecl("box", List(RecField("value", AnyType, None, false)), false)
    IpcFixtures.replies(List("GetRecDeclReply" -> writeToArray(record))) {
      ctx.elab.elabExpr(RecordSelect(Var("R")(pos), "box", "value")(pos), Map("R" -> RecordType("box")("field_client")))
    }
    assertEquals(ctx.diagnosticsInfo.popErrors(), Nil)
  }
  for (enabled <- List(true, false)) {
    test(s"normal pipeline invokes exhaustiveness with enabled=$enabled") {
      val module = s"pipeline_$enabled"
      val id = Id("f", 1)
      val ty = UnionType(Set(AtomLitType("a"), AtomLitType("b")))
      val spec = FunSpec(id, FunType(0, List(ty), AtomLitType("ok")))
      val fun = FunDecl(id, List(Clause(List(PatAtom("a")(pos)), Nil, Body(List(AtomLit("ok")(pos))))(pos)))(pos)
      val errors = IpcFixtures.replies(
        List(
          "GetFunSpecReply" -> writeToArray(spec),
          "GetOverloadedFunSpecReply" -> Array.emptyByteArray,
        )
      ) { Pipeline.checkFunction(module, fun, Options(exhaustiveCaseChecking = Some(enabled))) }
      assertEquals(errors.count(_.errorName == "non_exhaustive_function"), if (enabled) 1 else 0)
    }
  }
}
