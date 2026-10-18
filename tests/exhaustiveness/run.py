#!/usr/bin/env python3
"""Run the fork's literate Erlang examples through ELP and our assembled JAR.

Only Python's standard library is used. Nothing is generated in the checkout.
The ELP-facing adapter and expectation language live here, outside upstream code.
"""
import argparse
from dataclasses import dataclass, field
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
DEFAULT_JAR = ROOT / "eqwalizer/target/scala-3.6.4/eqwalizer-strict.jar"
PROBE = "eqs_feature_execution_probe"


class Failure(Exception):
    pass


@dataclass
class Example:
    path: Path
    name: str
    start: int
    end: int = 0
    expected: list = field(default_factory=list)
    clean: bool = False


def examples(path):
    """Explicit regions avoid a home-grown Erlang function parser."""
    found, current = [], None
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        match = re.match(r"\s*%%\s+@([a-z][a-z-]*)\b(.*)$", line)
        if not match:
            continue
        tag, rest = match.groups()
        rest = rest.strip()
        location = f"{path.name}:{number}"
        if tag not in {"test", "expect", "expect-clean", "end"}:
            raise Failure(f"{location}: unknown annotation @{tag}")
        if tag == "test":
            if current or not rest or any(x.name == rest for x in found):
                raise Failure(f"{location}: nested, empty or duplicate @test")
            current = Example(path, rest, number)
        elif current is None:
            raise Failure(f"{location}: @{tag} outside @test")
        elif tag == "end":
            if rest or (not current.expected and not current.clean):
                raise Failure(f"{location}: example needs @expect or @expect-clean")
            current.end = number
            found.append(current)
            current = None
        elif tag == "expect-clean":
            if rest or current.expected or current.clean:
                raise Failure(f"{location}: conflicting @expect-clean")
            current.clean = True
        else:
            parts = shlex.split(rest)
            if current.clean or not (len(parts) == 1 or len(parts) == 3 and parts[1] == "contains"):
                raise Failure(f"{location}: expected CODE [contains \"message fragment\"]")
            if not re.fullmatch(r"[a-z][a-z0-9_]*", parts[0]):
                raise Failure(f"{location}: invalid diagnostic code")
            current.expected.append((parts[0], parts[2] if len(parts) == 3 else ""))
    if current:
        raise Failure(f"{path.name}: unclosed @test {current.name}")
    if not found:
        raise Failure(f"{path.name}: no executable examples")
    return found


def diagnostics(stdout):
    """Pinned ELP's --format json emits one Arc diagnostic per line."""
    result = []
    for number, line in enumerate(stdout.splitlines(), 1):
        if not line.strip():
            continue
        try:
            item = json.loads(line)
        except ValueError as error:
            raise Failure(f"ELP stdout line {number} is not JSON: {line}") from error
        if not isinstance(item, dict) or not all(k in item for k in ("path", "line", "name", "description")):
            raise Failure(f"Unrecognized ELP diagnostic schema: {item!r}")
        if type(item["line"]) is not int or item["line"] < 1:
            raise Failure(f"Invalid diagnostic line: {item!r}")
        if not all(isinstance(item[k], str) for k in ("path", "name", "description")):
            raise Failure(f"Invalid diagnostic text fields: {item!r}")
        item["feature_code"] = item["name"].removeprefix("eqWAlizer: ")
        result.append(item)
    return result


def compare(expected, actual):
    """Consume exact counts; match specific fragments before code-only assertions."""
    remaining = list(actual)
    problems = []
    for code, fragment in sorted(expected, key=lambda pair: not bool(pair[1])):
        match = next((d for d in remaining if d["feature_code"] == code and fragment in d["description"]), None)
        if match is None:
            problems.append(f"missing {code}" + (f" containing {fragment!r}" if fragment else ""))
        else:
            remaining.remove(match)
    problems.extend(f"unexpected {d['feature_code']} at line {d['line']}: {d['description']}" for d in remaining)
    return problems


def executable(value, name):
    path = shutil.which(value)
    if not path:
        raise Failure(f"Missing {name}: {value!r}. See tests/exhaustiveness/README.md for prerequisites.")
    return str(Path(path).resolve())


def run(command, cwd, env, timeout):
    try:
        return subprocess.run(command, cwd=cwd, env=env, text=True, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        raise Failure(f"Timed out after {timeout}s: {shlex.join(command)}\n{error.stderr or ''}") from error


def stage(fixtures, work):
    shutil.copytree(fixtures / "src", work / "src")
    if (fixtures / "include").exists():
        shutil.copytree(fixtures / "include", work / "include")
    else:
        (work / "include").mkdir()
    probes, modules = {}, []
    for path in sorted((work / "src").glob("*.erl")):
        source = path.read_text(encoding="utf-8")
        module = re.search(r"^-module\(([a-z][a-zA-Z0-9_]*)\)\.", source, re.MULTILINE)
        if not module or module[1] != path.stem or PROBE in source:
            raise Failure(f"{path.name}: expected matching simple -module and no reserved probe name")
        examples(path)
        modules.append(module[1])
        # Every module must demonstrably execute our exhaustiveness pass, including
        # modules whose examples expect no diagnostics. The probe is staged only.
        export = f"-export([{PROBE}/1]).\n"
        source = source[:module.end()] + "\n" + export + source[module.end():]
        source += f"\n-spec {PROBE}(a | b) -> ok.\n{PROBE}(a) -> ok.\n"
        path.write_text(source, encoding="utf-8")
        probes[path.name] = len(source.splitlines())
    if not modules:
        raise Failure("No Erlang fixture modules found")
    # Re-read after inserting the export attribute so line scopes match staged code.
    tests = [test for path in sorted((work / "src").glob("*.erl")) for test in examples(path)]
    manifest = {"apps": [{"name": "eqwalizer_strict_features", "dir": ".",
                           "src_dirs": ["src"], "include_dirs": ["include"]}], "deps": []}
    (work / "project.json").write_text(json.dumps(manifest), encoding="utf-8")
    return tests, probes, modules


def execute(args, work):
    elp = executable(args.elp, "ELP")
    java = executable(args.java, "Java")
    erl = executable(args.erl, "Erlang/OTP (erl)")
    escript = executable(args.escript, "Erlang escript")
    jar = args.jar.resolve()
    if not jar.is_file():
        raise Failure(f"Checker JAR does not exist: {jar}. Run make build first.")
    version = run([elp, "--version"], work, os.environ, 20)
    if version.returncode:
        raise Failure(f"Cannot run ELP: {version.stderr}")
    print(version.stdout.strip())
    otp = run([erl, "-noshell", "-eval",
               'io:format("OTP ~s~n", [erlang:system_info(otp_release)]), '
               'case filelib:is_file(filename:join(code:lib_dir(erts, src), "erlang.erl")) '
               'of true -> halt(0); false -> halt(2) end.'], work, os.environ, 20)
    if otp.returncode:
        raise Failure(f"Erlang runtime and OTP sources are required (install erlang-src):\n{otp.stderr}")
    print(otp.stdout.strip())
    print(f"Checker: {jar}")
    tests, probes, modules = stage(args.fixtures.resolve(), work)
    wrapper = work / "checker"
    wrapper.write_text("#!" + sys.executable + "\n" + """import json, os, sys
with open(os.environ['EQS_FEATURE_INVOCATIONS'], 'a') as log:
    log.write(json.dumps(sys.argv[1:]) + '\\n')
os.execv(os.environ['EQS_FEATURE_JAVA'], [os.environ['EQS_FEATURE_JAVA'], '-jar', os.environ['EQS_FEATURE_JAR'], *sys.argv[1:]])
""", encoding="utf-8")
    wrapper.chmod(0o755)
    env = {**os.environ, "ELP_EQWALIZER_PATH": str(wrapper), "EQS_FEATURE_JAR": str(jar),
           "EQS_FEATURE_JAVA": java, "EQS_FEATURE_INVOCATIONS": str(work / "invocations.jsonl")}
    command = [elp, "eqwalize", "--project", str(work / "project.json"), "--no-connect",
               "--format", "json", "--erl", erl, "--escript", escript, *modules]
    result = run(command, work, env, args.timeout)
    (work / "stdout.jsonl").write_text(result.stdout, encoding="utf-8")
    (work / "stderr.log").write_text(result.stderr, encoding="utf-8")
    # Without --bail-on-error, expected diagnostic output must still exit zero.
    if result.returncode:
        raise Failure(f"ELP exited {result.returncode}:\n{result.stderr[:4000]}\n{result.stdout[:4000]}\n"
                      "Use --keep-workdir to retain full logs.")
    if not (work / "invocations.jsonl").is_file():
        raise Failure("ELP did not invoke our checker override")
    actual = diagnostics(result.stdout)
    errors = []
    scoped = {test.path.name for test in tests}
    for item in actual:
        path = Path(item["path"])
        resolved = path.resolve() if path.is_absolute() else (work / path).resolve()
        if resolved.parent != work / "src" or path.name not in scoped:
            errors.append(f"Unexpected diagnostic file: {item}")
        item["feature_file"] = path.name
    for filename, probe_line in probes.items():
        found = [d for d in actual if d["feature_file"] == filename and d["line"] == probe_line
                 and d["feature_code"] == "non_exhaustive_function"
                 and PROBE in d["description"] and "'b'" in d["description"]]
        if len(found) != 1:
            errors.append(f"{filename}: expected exactly one execution-probe diagnostic, got {len(found)}")
        for item in found:
            actual.remove(item)
    for test in tests:
        found = [d for d in actual if d["feature_file"] == test.path.name and test.start <= d["line"] <= test.end]
        problems = compare(test.expected, found)
        label = f"{test.path.name}: {test.name}"
        print(("FAIL " if problems else "PASS ") + label)
        errors.extend(f"{label}: {problem}" for problem in problems)
        for item in found:
            actual.remove(item)
    errors.extend(f"Diagnostic outside any example: {item}" for item in actual)
    if errors:
        raise Failure("\n".join(errors))
    print(f"Passed {len(tests)} Erlang-source examples in {len(modules)} modules; all execution probes verified.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--elp", default=os.environ.get("ELP", "elp"))
    parser.add_argument("--erl", default=os.environ.get("ERL", "erl"))
    parser.add_argument("--escript", default=os.environ.get("ESCRIPT", "escript"))
    parser.add_argument("--java", default="java")
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--fixtures", type=Path, default=HERE)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--keep-workdir", action="store_true", help="Keep staged sources and ELP logs for diagnosis")
    args = parser.parse_args()
    work = Path(tempfile.mkdtemp(prefix="eqwalizer-source-features-")).resolve()
    try:
        execute(args, work)
        return 0
    except (Failure, OSError, ValueError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    finally:
        if args.keep_workdir:
            print(f"Work directory: {work}")
        else:
            shutil.rmtree(work)


if __name__ == "__main__":
    sys.exit(main())
