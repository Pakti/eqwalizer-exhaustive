# Synchronizing the standalone checker fork

[`upstream.json`](../upstream.json) is the authoritative imported baseline. It
records the upstream repository, full commit, release tag, imported subtree and
its tree hash. `tested_elp` separately records the ELP commit/release and OTP
version used for integration validation. CI reads its repository, ELP commit and
OTP version from this file; its build timestamp comes from that ELP commit.
Historical upgrade reports retain their original provenance.

Upstream `eqwalizer/` maps to our repository root. Never apply a diff from our
customized tree to upstream: that would remove the fork's features. Compare the
**two upstream subtrees**, then apply that delta to the fork using Git's three-way
merge. No shared commit ancestry, synthetic merge parents, subtree split, or
monorepo merge is needed.

## Review a candidate

From the repository root, with Python 3.9+ and Git installed:

```sh
python3 scripts/sync-upstream.py refs/tags/NEW_RELEASE --fetch
```

`--fetch` fetches the recorded baseline and candidate from the recorded upstream
into the current Git object database. It does not change the checkout, branch or
remote configuration. Omit it when the upstream objects are already available;
then a locally resolvable commit or tag is accepted. Prefer a full SHA or explicit
tag ref to an ambiguous abbreviated revision.

The default operation reports:

- Full baseline and target commits, target tree, and upstream commits between them.
- Imported subtree changes, including additions and deletions.
- Existing upstream files modified by the committed fork (`HEAD`).
- Overlap between those files and the candidate's changes.
- Changes to IPC/type definitions, parser/conversion, diagnostics/IDE and project
  loading outside the imported subtree, plus all other outside changes.
- Whether the imported subtree is identical.

Use `--json` for the complete machine-readable report. Integration paths are a
conservative review aid, not a compatibility guarantee; review the remaining
outside changes too. An identical subtree can still require testing a new ELP.
The fork comparison uses committed `HEAD`, so commit relevant local work before
relying on its overlap report.

## Prepare and apply the delta

Start an integration branch from the current fork `main`, accounting for pending
upgrade branches and preserving user changes. Record the candidate's resolved
full SHA from the report, then use it consistently:

```sh
git switch -c upgrade/elp-NEW_RELEASE main
python3 scripts/sync-upstream.py FULL_TARGET_SHA --patch /tmp/eqwalizer-upstream.patch
# Review the report and patch first.
python3 scripts/sync-upstream.py FULL_TARGET_SHA --apply
```

`--patch` exclusively creates a binary patch and refuses to overwrite a file.
`--apply` requires a clean working tree, an attached branch other than `main` or
`master`, and a target descended from the imported upstream commit. It runs
`git apply --3way`, including binary changes and deletions. Conflicts produce a
nonzero exit and remain in the index/worktree for review and resolution; nothing
is automatically discarded or committed. The tool never merges, tags or pushes.
It does not advance the baseline, even when application succeeds.

The underlying operation remains:

```sh
git diff --binary --full-index OLD_SHA:eqwalizer NEW_SHA:eqwalizer > /tmp/delta.patch
git apply --3way /tmp/delta.patch
```

Review overlapping hooks and the final fork-to-target diff. Preserve diagnostic
timing, filtering, options and feature intent. Inspect protocol/AST changes and
run a clean build when they change. Validate with the matching ELP through
`ELP=/path/to/elp make test`, plus `make test-runner test-sync smoke`; see
[AGENTS.md](../AGENTS.md) for build prerequisites and coverage boundaries.

After reviewing the import, update `commit`, `tree` and `release` in
`upstream.json` using the verified tag and report. Advance `tested_elp` only after
actually validating that ELP/OTP combination. Keep imported-source and tested-ELP
claims separate if validation is deferred. Update active documentation, write an
upgrade report, and commit the reviewed result. Merging, tagging and publication
follow the maintainer's instructions.

## Fork integration points

Fork-owned `StrictDirectives` loads/merges source directives and decides
function-scoped suppression. `StrictPrivateConstructors` checks ownership at the
existing record construction/update points. `StrictDiagnostics` owns the custom
record/case diagnostics and the `Unstructured` marker used by the small ELP
serialization adapter; other fork diagnostics opt into that marker too.

Only four upstream production Scala files need fork hooks: `Pipeline`, `Elab`,
`tc/package.scala` (options/context), and `ELPDiagnostics`. `Util` and
`TcDiagnostics` match the imported baseline exactly. Fork implementation and
acceptance tests stay in added files. This refactoring preserves checker behavior;
it does not extend exhaustiveness traversal or change known-limit expectations.

Run `make test-sync` for offline Git fixtures covering review, overlap, a real
three-way merge, binary changes, deletion, conflicts and application safeguards.
