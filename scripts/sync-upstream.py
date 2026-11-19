#!/usr/bin/env python3
"""Review an ELP revision and optionally generate/apply its checker-only delta."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

# Deliberately conservative: these paths warrant review even without Scala changes.
INTEGRATION = ("crates/eqwalizer/", "crates/types_db/", "crates/elp/",
               "crates/ide/", "crates/project_model/", "crates/elp_project_model/",
               "crates/ide_db/", "crates/erlang_service/", "crates/base_db/",
               "crates/hir/", "crates/syntax/", "erlang_service/")


def git(root, *args, data=None):
    result = subprocess.run(["git", "-C", str(root), *args], input=data,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors="replace").strip() or
                           "git command failed: " + " ".join(args))
    return result.stdout


def revision(root, ref):
    return git(root, "rev-parse", "--verify", "--end-of-options", ref + "^{commit}").decode().strip()


def paths(root, *args):
    return set(git(root, *args, "-z").decode().rstrip("\0").split("\0")) - {""}


def review(root, baseline, candidate):
    if baseline.get("schema_version") != 1:
        raise RuntimeError("Unsupported upstream.json schema_version")
    old = revision(root, baseline["commit"])
    new = revision(root, candidate)
    prefix = baseline["subtree"]
    old_tree = git(root, "rev-parse", old + ":" + prefix).decode().strip()
    if old_tree != baseline["tree"]:
        raise RuntimeError("Baseline subtree tree does not match upstream.json")
    new_tree = git(root, "rev-parse", new + ":" + prefix).decode().strip()
    changed = paths(root, "diff", "--name-only", "--no-renames", old_tree, new_tree)
    owned = paths(root, "ls-tree", "-r", "--name-only", old_tree)
    local = paths(root, "diff", "--name-only", "--no-renames", old_tree, "HEAD") & owned
    outside = paths(root, "diff", "--name-only", "--no-renames", old, new)
    outside = {p for p in outside if not p.startswith(prefix + "/")}
    report = dict(baseline_commit=old, candidate_commit=new, candidate_tree=new_tree,
                  subtree_unchanged=old_tree == new_tree,
                  upstream_commits=git(root, "log", "--reverse", "--format=%H %s", old + ".." + new).decode().splitlines(),
                  subtree_changes=sorted(changed), fork_modified_upstream_files=sorted(local),
                  overlapping_files=sorted(changed & local),
                  integration_changes=sorted(p for p in outside if p.startswith(INTEGRATION)),
                  other_outside_changes=sorted(p for p in outside if not p.startswith(INTEGRATION)))
    patch = git(root, "diff", "--binary", "--full-index", "--no-color", "--no-ext-diff", "--no-textconv", "--src-prefix=a/", "--dst-prefix=b/", "--no-renames", old_tree, new_tree)
    return report, patch


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate", help="ELP commit or tag already fetched into this Git repository")
    parser.add_argument("--fetch", action="store_true", help="fetch baseline and candidate from the recorded upstream")
    parser.add_argument("--patch", type=Path, help="write a binary subtree-to-subtree patch (never overwrite a file)")
    parser.add_argument("--apply", action="store_true", help="apply with git apply --3way on a clean non-main branch")
    parser.add_argument("--json", action="store_true", help="emit the complete machine-readable report")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    try:
        baseline = json.loads((root / "upstream.json").read_text())
        if args.fetch:
            for ref in (baseline["commit"], args.candidate):
                if ref.startswith("-"):
                    raise RuntimeError("Revision cannot start with '-'")
                git(root, "fetch", "--no-tags", baseline["repository"], ref)
            candidate = "FETCH_HEAD"
        else:
            candidate = args.candidate
        report, patch = review(root, baseline, candidate)
        if args.apply:
            branch = git(root, "symbolic-ref", "--short", "HEAD").decode().strip()
            if branch in ("main", "master") or git(root, "status", "--porcelain"):
                raise RuntimeError("--apply requires a clean working tree on an integration branch")
            git(root, "merge-base", "--is-ancestor", report["baseline_commit"], report["candidate_commit"])
        if args.patch:
            with args.patch.open("xb") as stream:
                stream.write(patch)
        if args.json:
            print(json.dumps(report, indent=2))
        else:
            print(f"Baseline:  {report['baseline_commit']}\nCandidate: {report['candidate_commit']}")
            print("Subtree unchanged: " + str(report["subtree_unchanged"]))
            for key in ("upstream_commits", "subtree_changes", "fork_modified_upstream_files", "overlapping_files",
                        "integration_changes", "other_outside_changes"):
                print(f"\n{key.replace('_', ' ')} ({len(report[key])}):")
                for path in report[key]:
                    print("  " + path)
        if args.apply and patch:
            # Conflicts deliberately remain in the index/worktree for manual resolution.
            git(root, "apply", "--3way", data=patch)
            if not args.json:
                print("Applied subtree delta. Review and validate before advancing upstream.json.")
        return 0
    except (RuntimeError, OSError, ValueError, KeyError) as error:
        print(f"sync-upstream: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
