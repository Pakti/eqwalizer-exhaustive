package com.whatsapp.eqwalizer

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.whatsapp.eqwalizer.ast.Types.*
import com.whatsapp.eqwalizer.ast.{Subst, TypeVars}
import com.whatsapp.eqwalizer.tc.PipelineContext

class UpstreamTypesSuite extends munit.FunSuite {
  test("upstream integer and float types remain distinct") {
    val subtype = PipelineContext("regression").subtype
    assert(!subtype.subType(IntegerType, FloatType))
    assert(!subtype.subType(FloatType, IntegerType))
    assert(subtype.subType(IntegerType, UnionType(Set(IntegerType, FloatType))))
    assert(subtype.subType(FloatType, UnionType(Set(IntegerType, FloatType))))
  }
  test("unions absorb any and discard none") {
    assertEquals(UnionType(Set(AnyType, IntegerType)), AnyType)
    assertEquals(UnionType(Set(NoneType, IntegerType)), IntegerType)
  }
  test("intersections absorb none and discard any") {
    assertEquals(InterType(Set(NoneType, IntegerType)), NoneType)
    assertEquals(InterType(Set(AnyType, IntegerType)), IntegerType)
  }
  test("upstream none-aware product constructors") {
    assertEquals(TupleType_*(List(NoneType)), NoneType)
    assertEquals(ConsType_*(IntegerType, NoneType), NoneType)
    assertEquals(MapType_*(Map(AtomKey("a") -> MapProp(true, NoneType)), NoneType, NoneType), NoneType)
  }
  test("substitution traverses new cons and intersection types") {
    val v = FreeVarType(0)("A")
    val ty = ConsType(v, InterType(Set(v, AtomType)))
    assertEquals(Subst.subst(Map(0 -> IntegerType), ty), ConsType(IntegerType, InterType(Set(IntegerType, AtomType))))
  }
  test("instantiation uses target bound variables and forall count") {
    val ty = FunType(1, List(BoundVarType(0)("A")), BoundVarType(0)("A"))
    val (_, ft) = new TypeVars.Instantiate().instantiate(ty)
    assertEquals(ft.forall, 0)
    assertEquals(ft.argTys, List(FreeVarType(0)("A")))
    assertEquals(ft.resTy, FreeVarType(0)("A"))
  }
  test("target map-key array encoding round trips") {
    val ty: Type = MapType(Map(AtomKey("a") -> MapProp(true, IntegerType)), NoneType, NoneType)
    assertEquals(readFromString[Type](writeToString[Type](ty)), ty)
  }
  test("new intersection and cons codecs round trip") {
    val ty: Type = InterType(Set(ConsType(IntegerType, NilType), ListType(IntegerType)))
    assertEquals(readFromString[Type](writeToString[Type](ty)), ty)
  }
}
