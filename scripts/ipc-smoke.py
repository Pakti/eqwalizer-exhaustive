#!/usr/bin/env python3
"""Exercise the assembled checker with synthetic ELP replies, without Erlang/ELP.

Run after `cd eqwalizer && sbt assembly`. This checks the actual process, protocol,
pipeline, source directives and diagnostic serialization, not ELP integration.
"""
import json
import os
from pathlib import Path
import queue
import subprocess
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "eqwalizer/target/scala-3.6.4/eqwalizer-strict.jar"
POS = {"TextRange": {"start_byte": 0, "end_byte": 1}}


def node(kind, **fields):
    return {kind: {"pos": POS, **fields}}


def clause(pat, expr=None):
    return {"pos": POS, "pats": [pat], "guards": [],
            "body": {"exprs": [expr or node("AtomLit", s="ok")]}}


def check(source, arg_type, clauses, return_type=None):
    with tempfile.TemporaryDirectory(prefix="eqwalizer-ipc-") as tmp:
        path = Path(tmp) / "smoke.erl"
        path.write_text(source)
        forms = [node("Module", name="smoke"), node("File", file=str(path), start=0),
                 node("FunDecl", id="f/1", clauses=clauses)]
        spec = {"id": "f/1", "ty": {"forall": 0,
                "arg_tys": [arg_type], "res_ty": return_type or {"AtomLitType": {"atom": "ok"}}}}
        process = subprocess.Popen(["java", "-jar", str(JAR), "ipc", "smoke"],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, env={**os.environ, "EQWALIZER_MODE": "elp_cli"})
        lines = queue.Queue()
        def read_lines():
            for line in process.stdout:
                lines.put(line)
            lines.put(b"")
        reader = threading.Thread(target=read_lines, daemon=True)
        reader.start()
        def line():
            result = lines.get(timeout=20)
            if not result:
                raise AssertionError("Checker exited before completing the protocol: " + process.stderr.read().decode())
            return result
        def send(value):
            process.stdin.write(json.dumps(value).encode() + b"\n")
            process.stdin.flush()
        def payload(reply, value):
            data = json.dumps(value).encode() if value is not None else b""
            send({reply: {"len": len(data)}})
            if line() != b"\n":
                raise AssertionError("Missing payload acknowledgement")
            if data:
                process.stdin.write(data)
                process.stdin.flush()
        diagnostics = None
        try:
            while True:
                request = json.loads(line())
                kind, args = next(iter(request.items()))
                if kind == "EnteringModule":
                    send("ELPEnteringModule")
                elif kind in ("EqwalizingStart", "EqwalizingDone", "Dependencies"):
                    continue
                elif kind == "GetAstBytes":
                    value = forms if args["format"] == "ConvertedForms" else {
                        "module": "smoke", "exports": [], "imports": {}, "invalids": []}
                    payload("GetAstBytesReply", value)
                elif kind == "GetFunSpec":
                    payload("GetFunSpecReply", spec)
                elif kind == "GetOverloadedFunSpec":
                    payload("GetOverloadedFunSpecReply", None)
                elif kind == "GetRecDecl":
                    payload("GetRecDeclReply", {"name": "box", "fields": [], "refinable": False})
                elif kind == "Done":
                    if "smoke" in args.get("diagnostics", {}):
                        diagnostics = args["diagnostics"]["smoke"]
                        send("ELPExitingModule")
                    else:
                        break
                else:
                    raise AssertionError(f"Unexpected request: {request}")
            process.stdin.close()
            assert process.wait(timeout=20) == 0
            stderr = process.stderr.read().decode()
            assert not stderr, stderr
            assert diagnostics is not None
            return diagnostics
        finally:
            if process.poll() is None:
                process.kill()
            process.wait()
            process.stdin.close()
            process.stdout.close()
            process.stderr.close()
            reader.join(timeout=1)


class CheckerProtocol(unittest.TestCase):
    def test_missing_case_is_unstructured(self):
        ty = {"UnionType": {"tys": [{"AtomLitType": {"atom": a}} for a in ["a", "b", "c"]]}}
        result = check("-module(smoke).\n", ty, [clause(node("PatAtom", s=a)) for a in ["a", "b"]])
        self.assertEqual([d["code"] for d in result], ["non_exhaustive_function"])
        self.assertIn("'c'", result[0]["message"])
        self.assertNotIn("diagnostic", result[0])

    def test_skipped_check_is_reported(self):
        result = check("-module(smoke).\n", "AnyType", [clause(node("PatAtom", s="a"))])
        self.assertEqual([d["code"] for d in result], ["skipped_exhaustiveness_check"])

    def test_source_directive_suppresses_skipped_check(self):
        source = "% eqwalizer_strict:disable_error skipped_exhaustiveness_check\n-spec f(term()) -> ok.\n"
        self.assertEqual(check(source, "AnyType", [clause(node("PatAtom", s="a"))]), [])

    def test_private_constructor_comment_is_enforced(self):
        source = "% eqwalizer_strict:private_constructor owner\n-record(box, {}).\n"
        result = check(source, "AnyType", [clause(node("PatWild"), node("RecordCreate", rec_name="box", fields=[]))], "AnyType")
        self.assertEqual([d["code"] for d in result], ["private_constructor_violation"])
        self.assertNotIn("diagnostic", result[0])

    def test_owner_can_construct(self):
        source = "% eqwalizer_strict:private_constructor smoke\n-record(box, {}).\n"
        self.assertEqual(check(source, "AnyType", [clause(node("PatWild"), node("RecordCreate", rec_name="box", fields=[]))], "AnyType"), [])


if __name__ == "__main__":
    unittest.main()
