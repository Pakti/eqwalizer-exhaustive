# Working on this eqWAlizer fork

## Read first and establish the current state

- Read [ProjectStatus.md](ProjectStatus.md), the latest report in
  `docs/upstream-upgrade-*.md`, and [the source-test guide](tests/exhaustiveness/README.md).
- Inspect the current branch, working-tree changes and remote refs before editing.
  Preserve user changes and local commits. Do not assume an earlier chat's refs,
  tools, caches or environment processes are still current.
- In a managed cloud task, use the existing isolated checkout. Do not create a
  separate worktree unless the user asks for one.
- Follow the user's instructions for creating branches, merging, tagging and
  pushing. These are separate actions; successful validation alone does not
  authorize publication. Do not force-push or rewrite primary-branch history.

## Upstream ownership and synchronization

This repository is a standalone customized fork of
`https://github.com/WhatsApp/erlang-language-platform`. The upstream **`eqwalizer/`
subtree maps to this repository's root**. Consequently upstream's Scala project
`eqwalizer/eqwalizer/` maps to our `eqwalizer/`.

As of 2026-11-19, the recorded baseline is:

- Upstream tag: `2026-11-19`.
- Full upstream commit: `08c6a40b9f09d66e69e23d0154a30e4582ce4f07`.
- Upstream subtree tree: `bca61becd0e95e83ac9446375b566007c54d6323`.
- Fork release tag: `elp-2026-11-19`.
- Report: [November 19 synchronization](docs/upstream-upgrade-2026-11-19.md).

Verify this information against the repository and latest report before the next
upgrade. Update it when the baseline changes. A future machine-readable baseline
file should become the authority instead of duplicating independently maintained
pins across docs and CI.

For an upgrade:

1. Fetch the fork's current `main`; preserve and account for pending upgrade
   branches. The November 16 branch was still unmerged when November 19 began.
2. Create the requested integration branch from current `main`. Verify the new
   upstream tag's full commit, not just its abbreviated hash or release date.
3. Inspect commits and the subtree delta before applying it. Generate the delta
   between the last imported upstream commit and the target, not between the
   customized fork and upstream:

   ```sh
   git diff --binary OLD_SHA:eqwalizer NEW_SHA:eqwalizer > /tmp/eqwalizer-upstream.patch
   git apply --3way /tmp/eqwalizer-upstream.patch
   ```

4. Review overlapping custom hooks, deletions, AST changes, build dependencies and
   support-library specs. Preserve feature intent rather than choosing one side
   mechanically. Review the resulting fork-to-target diff for accidental drift.
5. Validate against the matching ELP revision, update active documentation and CI
   pins, and report exact commits, checks and any unvalidated workflows.

The original repository became a submodule and then a snapshot inside ELP.
Ordinary subtree splitting did not recover shared fork ancestry, and `--onto`
produced the same unrelated extracted history. See the October report for the
investigation. Do not repeat that experiment routinely, invent ancestry, merge
the entire monorepo, or rebase the fork's full historical customization series.

Checker-source synchronization and ELP compatibility are distinct. Also inspect:

- `crates/eqwalizer/` and `crates/types_db/src/eqwalizer/` for IPC/AST changes.
- `erlang_service/` for parsing and abstract-code conversion.
- ELP CLI/reporting/conversion for diagnostic JSON changes.
- Project-model, OTP discovery and include-resolution changes.

An unchanged subtree or conflict-free merge does not establish compatibility.

## Preserve the custom features

- Exhaustiveness is enabled by default for ordinary function specs; overloaded
  specs use a different path. Preserve diagnostics, documented skips, and limits.
- Private constructors apply to ordinary records through raw source comments:

  ```erlang
  % eqwalizer_strict:private_constructor owner_module
  -record(record_name, {value}).
  ```

  The directive must immediately precede the record in the raw file. Shared
  headers let clients see it. Owner construction/update is allowed; client
  construction/update is rejected; pattern matching and field reads are allowed.
- `% eqwalizer_strict:disable_error skipped_exhaustiveness_check` must immediately
  precede its `-spec`. It is function-scoped and does not suppress missing cases.
- The scanner reads raw source, not macro-expanded AST. Macros are not expanded
  in comments; intervening macro lines break adjacency. Treat the scanner as its
  documented small parser, not a complete Erlang parser/preprocessor.
- Full-module checking loads comments; `ipc-check-funs` does not currently do so.
  Do not silently change that behavior while refactoring integration hooks.
- Custom diagnostics omit unsupported structured Rust payloads. Keep their codes,
  positions and messages stable unless a behavior change is intended.
- `PrivateConstructorsManifest` currently has no active callers. Do not assume
  that its environment variable enables enforcement, or wire it in as cleanup.

## November 19 protocol and build lessons

- Clause patterns are `SPat`/`DPat` wrappers. Custom dynamic-pattern coverage is
  unsupported and skips even with a static catch-all. Dynamic variable patterns
  must not inherit narrower static argument types for body inspection.
- List/map comprehensions contain lists of templates; visit all templates and
  each map key/value. Source tests on OTP 27 validate the new static wire shapes;
  AST-level tests cover dynamic patterns and multiple templates. They do not
  establish native OTP 29 parsing/runtime support.
- After upstream AST/protocol changes, run `make clean` before building/testing.
  Incremental compilation previously retained an obsolete generated JSON codec
  even while structural unit tests passed. Real ELP integration caught it.
- Keep `eqwalizer/.jvmopts` (`-Xss8m`) for clean recursive codec derivation. Standard
  sbt reads it; direct `java -jar sbt-launch.jar` requires equivalent JVM options.

## Testing and tools

Run commands from the repository root:

| Command | Purpose |
| --- | --- |
| `make build` | Assemble `eqwalizer/target/scala-3.6.4/eqwalizer-strict.jar`. |
| `make test-upstream` | Existing Scala tests, including fork-added tests; not the complete upstream ELP suite. |
| `make test` | Build, run the Scala suite, and run the real Erlang-source suite. |
| `make test-runner` | Python-only tests of annotations and diagnostic matching. |
| `make smoke` | Build and run synthetic process/IPC checks. |

Prefer literate `.erl` acceptance examples under `tests/exhaustiveness/` for
custom features. Keep them separate from imported code/tests. Use Scala tests
for internal invariants and AST forms not parsable by the tested OTP runtime.
Keep the existing coverage while changing test organization.

Each source example has `%% @test`, expectations or `%% @expect-clean`, and
`%% @end`. All unexpected diagnostics fail, including ordinary upstream errors.
The runner adds a temporary missing-case probe per module; never weaken these
probes or turn missing tools/empty output into passing tests.

Some examples characterize known limitations (numeric literals, repeated
variables). Their current warning-free results are not desired correctness
guarantees. Classifying them separately changes test policy, not checker behavior;
fixing the limitations must deliberately update code, expectations and prose.

Use the matching ELP revision through `ELP=/path/to/elp`. The runner overrides
ELP's checker with our JAR. A stock ELP executable without that override does not
test our fork. Java 11+, sbt 1.10.10, Scala 3.6.4 and Python 3.9+ are the current
prerequisites; check version pins when upgrading.

Do not require the system `erlang-src` package or a particular installed-source
layout. Use `erl`/`escript` from the user's working ELP setup and leave default OTP
discovery intact. `OTP_SOURCE`/`--otp-source` optionally supplies a separate
matching source tree through the temporary manifest; do not modify the runtime.

If release binaries are unavailable for the desired OTP version, an exact-source
ELP build is valid. Use its lockfile and supported build-time JAR override to avoid
GraalVM; see the latest report/feature CI. Verify release checksums when using
binary assets. An ELP version banner may reflect the tagged commit's date rather
than the release date: verify source SHA provenance instead of inferring it from
the banner alone.

Report clean-build, Scala, source, runner, synthetic IPC and support-library
results separately. Do not claim full upstream Rust/Erlang snapshots, Buck,
daemon/LSP, native packaging, another OTP major or GitHub CI passed unless run.

## Reducing future synchronization work

Favor small integration calls into fork-owned helpers over large modifications
to `Pipeline`, `Elab`, `Util` and diagnostic registries. Keep the necessary
diagnostic serialization adapter explicit. Refactor only with behavior-preserving
regressions; do not change diagnostic timing, filtering, ownership or options as
an incidental cleanup.

The custom traversal still has `case _ => ()`. Replacing it with explicit cases
for all existing expression variants can preserve runtime behavior while making
new variants fail compilation under `-Werror`. Explicitly retain intentionally
unvisited forms initially. Traversing additional forms or propagating new
environments is a separate behavioral change requiring source regressions.

Track pinned metadata and integration-risk paths; do not expand into unrelated
upstream services or refactoring during a synchronization. Baseline tooling,
documentation, monitoring and test classification do not themselves change
checker runtime behavior. Hook refactoring should preserve it; new traversal or
coverage capabilities deliberately change it.
