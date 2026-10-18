package com.whatsapp.eqwalizer

import com.whatsapp.eqwalizer.ast.Id
import com.whatsapp.eqwalizer.tc.RawEqwalizerStrictAttributes
import java.nio.file.Files

class StrictDirectivesSuite extends munit.FunSuite {
  private def parse(source: String): RawEqwalizerStrictAttributes = {
    val path = Files.createTempFile("strict-regression", ".erl")
    try {
      Files.writeString(path, source)
      RawEqwalizerStrictAttributes.load(path.toString)
    } finally Files.delete(path)
  }
  test("per-function skipped-check suppression") {
    val result =
      parse("% eqwalizer_strict:disable_error skipped_exhaustiveness_check\n-spec f(atom(), {a, b}) -> ok.\n")
    assertEquals(result.disabledErrors, Set("skipped_exhaustiveness_check" -> Id("f", 2)))
    assertEquals(result.invalids, Nil)
  }
  test("suppression requires adjacent spec") {
    assertEquals(
      parse("% eqwalizer_strict:disable_error skipped_exhaustiveness_check\n\n-spec f() -> ok.\n").invalids
        .map(_.errorName),
      List("invalid_eqwalizer_strict_attribute"),
    )
  }
  test("unknown suppressed error rejected") {
    assert(parse("% eqwalizer_strict:disable_error made_up\n-spec f() -> ok.\n").invalids.nonEmpty)
  }
  test("private owner from record comment") {
    val result = parse("% eqwalizer_strict:private_constructor owner\n-record(box, {value}).\n")
    assertEquals(result.privateConstructorOwners, Map("box" -> Set("owner")))
    assertEquals(result.invalids, Nil)
  }
  test("conflicting owners retained for enforcement diagnostics") {
    val result = parse(
      "% eqwalizer_strict:private_constructor a\n-record(box, {}).\n% eqwalizer_strict:private_constructor b\n-record(box, {}).\n"
    )
    assertEquals(result.privateConstructorOwners, Map("box" -> Set("a", "b")))
  }
  test("attributes are not accepted as comment directives") {
    assert(parse("-eqwalizer_strict({private_constructor, box}).\n").invalids.nonEmpty)
  }
  test("unreadable source gets explicit diagnostic") {
    val path = Files.createTempFile("strict-deleted", ".erl")
    Files.delete(path)
    assertEquals(
      RawEqwalizerStrictAttributes.load(path.toString).invalids.map(_.errorName),
      List("unreadable_eqwalizer_strict_file"),
    )
  }
}
