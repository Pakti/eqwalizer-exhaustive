package com.whatsapp.eqwalizer

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToArray}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.whatsapp.eqwalizer.ast.{Id, Variance}
import com.whatsapp.eqwalizer.ast.Forms.TypeDecl
import com.whatsapp.eqwalizer.ast.Types.*
import com.whatsapp.eqwalizer.tc.PipelineContext

/** Behaviors restored by main@353831eb and retained by the October upstream model. */
class CorrectedMainSuite extends munit.FunSuite {
  private given JsonValueCodec[(TypeDecl, List[Variance])] = JsonCodecMaker.make(
    CodecMakerConfig
      .withAllowRecursiveTypes(true)
      .withDiscriminatorFieldName(None)
      .withFieldNameMapper(JsonCodecMaker.enforce_snake_case)
  )

  test("type lookup decodes declaration and supplied variances as a tuple") {
    val variable = FreeVarType(0)("A")
    val declaration = TypeDecl(Id("identity", 1), List(variable), variable)
    // Deliberately unlike the variance inferred from the identity body: metadata
    // must be read from the server, not reconstructed by the old compatibility code.
    val reply = (declaration, List(Variance.Contravariant))
    val result = IpcFixtures.replies(List("GetTypeDeclReply" -> writeToArray(reply))) {
      ELPProxy.typeDecl("corrected_main_tuple", declaration.id)
    }
    assertEquals(result, Some(reply))
    assertEquals(ELPProxy.typeDecl("corrected_main_tuple", declaration.id), Some(reply))
  }

  test("equal unsolved free variables satisfy constraints without a special case") {
    val ctx = PipelineContext("corrected_main_constraints")
    val variable = FreeVarType(7)("A")
    assert(ctx.constraints.satisfiable(Set.empty, Set.empty, List(variable -> variable)))
    assert(!ctx.constraints.satisfiable(Set.empty, Set.empty, List(variable -> FreeVarType(8)("B"))))
  }
}
