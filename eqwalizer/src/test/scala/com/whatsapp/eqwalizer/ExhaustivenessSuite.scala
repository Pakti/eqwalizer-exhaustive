package com.whatsapp.eqwalizer

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToArray}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.whatsapp.eqwalizer.ast.{Id, Pos, RemoteId, Specifier}
import com.whatsapp.eqwalizer.ast.Exprs.*
import com.whatsapp.eqwalizer.ast.Forms.{FunDecl, FunSpec}
import com.whatsapp.eqwalizer.ast.Guards.*
import com.whatsapp.eqwalizer.ast.Pats.*
import com.whatsapp.eqwalizer.ast.Types.*
import com.whatsapp.eqwalizer.tc.PipelineContext
import com.whatsapp.eqwalizer.util.Diagnostic.Diagnostic

class ExhaustivenessSuite extends munit.FunSuite {
  private val pos = Pos.TextRange(0, 1)
  private val abc = UnionType(Set(AtomLitType("a"), AtomLitType("b"), AtomLitType("c")))
  private def atom(s: String): Pat = PatAtom(s)(pos)
  private def variable(s: String): Pat = PatVar(s)(pos)
  private def wild: Pat = PatWild()(pos)
  private def clause(pats: List[Pat], guards: List[Guard] = Nil, body: List[Expr] = List(AtomLit("ok")(pos))): Clause =
    Clause(pats, guards, Body(body))(pos)
  private def errors(args: List[Type], clauses: List[Clause]): List[Diagnostic] = {
    val ctx = PipelineContext("regression")
    val id = Id("f", args.size)
    ctx.exhaustiveCase.checkFun(FunDecl(id, clauses)(pos), FunSpec(id, FunType(0, args, AtomLitType("ok"))))
    ctx.diagnosticsInfo.popErrors()
  }
  private def codes(ds: List[Diagnostic]): List[String] = ds.map(_.errorName)
  private def guard(pred: String, name: String): List[Guard] =
    List(Guard(List(TestCall(Id(pred, 1), List(TestVar(name)(pos)))(pos))))

  test("enabled by default") { assert(PipelineContext("regression").exhaustiveCaseChecking) }
  test("missing atom in function clauses") {
    val ds = errors(List(abc), List(clause(List(atom("a"))), clause(List(atom("b")))))
    assertEquals(codes(ds), List("non_exhaustive_function"))
    assert(ds.head.msg.contains("'c'"))
  }
  test("complete atom function") {
    assertEquals(errors(List(abc), List("a", "b", "c").map(a => clause(List(atom(a))))), Nil)
  }
  test("missing atom in case expression") {
    val expr = Case(Var("X")(pos), List(clause(List(atom("a"))), clause(List(atom("b")))))(pos)
    val ds = errors(List(abc), List(clause(List(variable("X")), body = List(expr))))
    assertEquals(codes(ds), List("non_exhaustive_case"))
    assert(ds.head.msg.contains("'c'"))
  }
  test("single interesting argument with dynamic other argument") {
    val ds = errors(List(abc, AnyType), List("a", "b").map(a => clause(List(atom(a), wild))))
    assertEquals(codes(ds), List("non_exhaustive_function"))
  }
  test("unsupported guards before final catch-all") {
    val comparison = Guard(List(TestBinOp(">", TestVar("X")(pos), TestVar("Y")(pos))(pos)))
    assertEquals(
      errors(
        List(IntegerType, IntegerType),
        List(
          clause(List(variable("X"), variable("Y")), List(comparison)),
          clause(List(wild, wild)),
        ),
      ),
      Nil,
    )
  }
  test("guarded final clause does not prove catch-all") {
    assert(
      codes(errors(List(abc, abc), List(clause(List(wild, wild), guard("is_atom", "X")))))
        .contains("skipped_exhaustiveness_check")
    )
  }
  test("product space reports missing combination") {
    val ab = UnionType(Set(AtomLitType("a"), AtomLitType("b")))
    val ds = errors(List(ab, ab), List(clause(List(atom("a"), wild)), clause(List(atom("b"), atom("a")))))
    assertEquals(codes(ds), List("non_exhaustive_function"))
    assert(ds.head.msg.contains("('b', 'b')"))
  }
  test("complete product without catch-all") {
    val ab = UnionType(Set(AtomLitType("a"), AtomLitType("b")))
    assertEquals(errors(List(ab, ab), List("a", "b").map(a => clause(List(atom(a), wild)))), Nil)
  }
  test("product arity limit remains conservative") {
    val ds = errors(List.fill(5)(abc), List(clause(List.fill(5)(atom("a")))))
    assertEquals(codes(ds), List("skipped_exhaustiveness_check"))
    assert(ds.head.msg.contains("limit 4"))
  }
  test("product cell limit remains conservative") {
    val ds = errors(
      List.fill(4)(UnionType((1 to 4).map(n => AtomLitType(s"a$n")).toSet)),
      List(clause(List.fill(4)(atom("a1")))),
    )
    assertEquals(codes(ds), List("skipped_exhaustiveness_check"))
    assert(ds.head.msg.contains("above limit 128"))
  }
  test("product-space guards are still unsupported") {
    val ds = errors(List(abc, abc), List(clause(List(variable("X"), atom("a")), guard("is_atom", "X"))))
    assertEquals(codes(ds), List("skipped_exhaustiveness_check"))
  }
  test("tagged tuple alternatives") {
    val ty =
      UnionType(Set(TupleType(List(AtomLitType("left"), AnyType)), TupleType(List(AtomLitType("right"), AnyType))))
    val pats = List("left", "right").map(tag => PatTuple(List(atom(tag), wild))(pos))
    assertEquals(errors(List(ty), pats.map(p => clause(List(p)))), Nil)
  }
  test("tuple selector with final catch-all") {
    val expr = Case(
      Tuple(List(Var("X")(pos), Var("Y")(pos)))(pos),
      List(
        clause(List(PatTuple(List(atom("a"), atom("b")))(pos))),
        clause(List(wild)),
      ),
    )(pos)
    assertEquals(errors(List(abc, abc), List(clause(List(variable("X"), variable("Y")), body = List(expr)))), Nil)
  }
  for (
    (pred, ty) <- List(
      "is_atom" -> AtomType,
      "is_integer" -> IntegerType,
      "is_float" -> FloatType,
      "is_binary" -> BinaryType,
    )
  ) {
    test(s"$pred guard covers its alternative") {
      assertEquals(errors(List(ty), List(clause(List(variable("X")), guard(pred, "X")))), Nil)
    }
  }
  test("is_number covers the upstream integer/float union") {
    assertEquals(
      errors(List(UnionType(Set(IntegerType, FloatType))), List(clause(List(variable("X")), guard("is_number", "X")))),
      Nil,
    )
  }
  test("is_integer does not cover floats") {
    val ds =
      errors(List(UnionType(Set(IntegerType, FloatType))), List(clause(List(variable("X")), guard("is_integer", "X"))))
    assertEquals(codes(ds), List("non_exhaustive_function"))
    assert(ds.head.msg.contains("float()"))
  }
  private def nonEmptyBinary: Pat = PatBinary(
    List(
      PatBinaryElem(variable("First"), None, Specifier.UnsignedInteger)(pos),
      PatBinaryElem(variable("Rest"), None, Specifier.Binary)(pos),
    )
  )(pos)
  test("nonempty binary leaves empty binary uncovered") {
    val ds = errors(List(BinaryType), List(clause(List(nonEmptyBinary))))
    assertEquals(codes(ds), List("non_exhaustive_function"))
    assert(ds.head.msg.contains("<<>>"))
  }
  test("empty and nonempty binary cover binary") {
    assertEquals(errors(List(BinaryType), List(clause(List(PatBinary(Nil)(pos))), clause(List(nonEmptyBinary)))), Nil)
  }
  test("unsupported binary sizes skip") {
    val pat = PatBinary(List(PatBinaryElem(wild, Some(Var("Size")(pos)), Specifier.UnsignedInteger)(pos)))(pos)
    assertEquals(codes(errors(List(BinaryType), List(clause(List(pat))))), List("skipped_exhaustiveness_check"))
  }
  test("dynamic selector skips rather than inventing missing cases") {
    assertEquals(codes(errors(List(DynamicType), List(clause(List(atom("a")))))), List("skipped_exhaustiveness_check"))
  }
  test("new intersection type skips conservatively") {
    val ty = InterType(Set(AtomType, IntegerType))
    assertEquals(codes(errors(List(ty), List(clause(List(atom("a")))))), List("skipped_exhaustiveness_check"))
  }
  test("dynamic function selector uses the declared return type") {
    val expr = Case(DynCall(Var("F")(pos), Nil)(pos), List(clause(List(atom("a"))), clause(List(atom("b")))))(pos)
    val ds = errors(List(FunType(0, Nil, abc)), List(clause(List(variable("F")), body = List(expr))))
    assertEquals(codes(ds), List("non_exhaustive_case"))
  }
  private given JsonValueCodec[FunSpec] = JsonCodecMaker.make(
    CodecMakerConfig.withDiscriminatorFieldName(None).withFieldNameMapper(JsonCodecMaker.enforce_snake_case)
  )
  for (local <- List(true, false)) {
    test(s"declared call result selector, local=$local") {
      val id = Id(if (local) "local_selector" else "remote_selector", 0)
      val call = if (local) LocalCall(id, Nil)(pos) else RemoteCall(RemoteId("other", id.name, 0), Nil)(pos)
      val expr = Case(call, List(clause(List(atom("a"))), clause(List(atom("b")))))(pos)
      val replies = (if (local)
                       List(
                         "GetAstBytesReply" ->
                           """{"module":"regression","exports":[],"imports":{},"invalids":[]}""".getBytes("UTF-8")
                       )
                     else Nil) ++
        List("GetFunSpecReply" -> writeToArray(FunSpec(id, FunType(0, Nil, abc))))
      val ds = IpcFixtures.replies(replies) { errors(List(abc), List(clause(List(variable("X")), body = List(expr)))) }
      assertEquals(codes(ds), List("non_exhaustive_case"))
    }
  }
  test("is_record/2 guard on a whole argument") {
    val guards = List(Guard(List(TestCall(Id("is_record", 2), List(TestVar("R")(pos), TestAtom("box")(pos)))(pos))))
    assertEquals(errors(List(RecordType("box")("regression")), List(clause(List(variable("R")), guards))), Nil)
  }
}
