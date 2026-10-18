"""Tests of the fork-only annotation/diagnostic adapter, not checker ASTs."""
import json
from pathlib import Path
import tempfile
import unittest

from run import Failure, compare, diagnostics, examples, stage


class Expectations(unittest.TestCase):
    def parse(self, text):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "example.erl"
            path.write_text(text, encoding="utf-8")
            return examples(path)

    def test_regions_preserve_lines_and_message_fragments(self):
        tests = self.parse('%% @test example\n%% @expect non_exhaustive_case contains "missing c"\nf() -> ok.\n%% @end\n')
        self.assertEqual((tests[0].start, tests[0].end), (1, 4))
        self.assertEqual(tests[0].expected, [("non_exhaustive_case", "missing c")])

    def test_empty_or_malformed_expectations_fail(self):
        for source in ["", "%% @test empty\n%% @end\n", "%% @test unclosed\n",
                       "%% @expect-clean\n", "%% @test a\n%% @test nested\n",
                       "%% @test a\n%% @expect-clean\n%% @expect error\n%% @end\n",
                       "%% @test a\n%% @expect-clen\n%% @end\n"]:
            with self.subTest(source=source), self.assertRaises(Failure):
                self.parse(source)

    def test_clean_does_not_ignore_unexpected_diagnostics(self):
        actual = [{"feature_code": "skipped_exhaustiveness_check", "line": 7, "description": "skipped"}]
        self.assertTrue(compare([], actual))

    def test_diagnostic_counts_are_exact(self):
        item = {"feature_code": "non_exhaustive_case", "line": 7, "description": "missing c"}
        self.assertFalse(compare([("non_exhaustive_case", "missing c")], [item]))
        self.assertTrue(compare([("non_exhaustive_case", "missing c")], []))
        self.assertTrue(compare([("non_exhaustive_case", "missing c")], [item, item]))
        self.assertTrue(compare([("non_exhaustive_case", "missing b")], [item]))

    def test_json_adapter_uses_name_not_generic_elp_code(self):
        line = json.dumps({"path": "src/a.erl", "line": 3, "code": "ELP",
                           "name": "eqWAlizer: non_exhaustive_case", "description": "missing c"})
        self.assertEqual(diagnostics(line)[0]["feature_code"], "non_exhaustive_case")

    def test_non_json_and_unrecognized_schemas_fail(self):
        for output in ["no modules checked", "{}", "[]", '{"type":"progress"}']:
            with self.subTest(output=output), self.assertRaises(Failure):
                diagnostics(output)

    def test_staging_adds_probe_without_changing_original_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            fixtures, work = Path(tmp) / "fixtures", Path(tmp) / "work"
            (fixtures / "src").mkdir(parents=True)
            work.mkdir()
            source = '-module(example).\n%% @test clean\n%% @expect-clean\nf() -> ok.\n%% @end\n'
            path = fixtures / "src/example.erl"
            path.write_text(source, encoding="utf-8")
            tests, probes, modules = stage(fixtures, work)
            self.assertEqual(path.read_text(encoding="utf-8"), source)
            self.assertEqual(modules, ["example"])
            self.assertEqual(tests[0].start, 4)
            self.assertGreater(probes["example.erl"], tests[0].end)


if __name__ == "__main__":
    unittest.main()
