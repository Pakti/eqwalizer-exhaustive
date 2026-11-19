# Synchronization with ELP 2026-11-19

## Provenance and branch history

- Starting `main`: `c9ccceeab15a09c10bb211e03e8f50f88a330933`, still at the
  October release when this task began.
- The validated November 16 upgrade, `235b764f6dbbc9b259c18867eb6d729050bcb93a`,
  was included first, preserving that branch's commits and regression tests.
- Integration branch: `upgrade/elp-2026-11-19`, created from `main`.
- Previous imported upstream: `45a9286a9ebb09c04b1daa77834ca9833fb4b0dd`.
- Target: tag `2026-11-19`, verified to point to
  `08c6a40b9f09d66e69e23d0154a30e4582ce4f07`.
- Target `eqwalizer/` tree: `bca61becd0e95e83ac9446375b566007c54d6323`.

The ELP `eqwalizer/` subtree maps to this repository root. The delta was applied
using `git diff --binary OLD:eqwalizer NEW:eqwalizer` and `git apply --3way`.
All 14 upstream-changed files applied cleanly, including private-constructor
hooks retained in `Elab`. No Rust/ELP application code or monorepo history was
merged into the fork. After validation, the integration branch is merged into
`main` as requested; remote publication is a separate action.

## Upstream changes

Five commits modify the imported subtree:

| Commit | Change |
| --- | --- |
| `476ead213` | Report unconvertible abstract code as a diagnostic rather than aborting other modules. |
| `0a973be7b` | Introduce static/dynamic clause-pattern wrappers (`SPat`, `DPat`), including `P = :?` handling. |
| `1080305e6` | Sharpen bundled OTP overrides for ETS, filelib, ordered dictionaries, regex errors and sockets. |
| `b36d772a0` | Support the OTP 29 `is_integer/3` guard in ordinary checking/narrowing. |
| `e19ceb304` | Support OTP 29 multi-valued list/map comprehensions; templates are now lists. |

The Rust-side serialized AST changes with the Scala model. Unlike November 16,
this release is not wire-compatible with the earlier clause/comprehension shapes.
The pinned ELP source and fork checker must be upgraded together. Java minimum,
Scala 3.6.4, sbt 1.10.10 and Scala dependency versions remain unchanged.

## Fork adaptations

- Ordinary patterns are unwrapped from `SPat` for the existing coverage logic.
- Dynamic patterns are deliberately outside the custom coverage subset. They
  produce `skipped_exhaustiveness_check`, including case/tuple and function
  catch-all paths. Dynamic function-variable patterns supply `DynamicType` to
  body inspection instead of borrowing the narrower static argument spec.
- Every list-comprehension template and every map-comprehension key/value is
  traversed. Existing qualifier/environment limitations remain unchanged.
- Existing Scala fixture helpers and synthetic IPC fixtures now encode static
  clause wrappers. Six new Scala regressions cover the dynamic skip paths,
  dynamic-variable environments, and traversal of later templates.
- Four new literate Erlang examples check comprehension-template case diagnostics
  and the sharpened `orddict:take` and `filelib:safe_relative_path` results.
- Exhaustiveness defaults, private record constructors, strict comment scanning,
  per-function suppression and unstructured custom diagnostics are retained.

No new custom coverage support for `is_integer/3` or dynamic patterns is claimed.
The ordinary upstream checker supports them; our custom coverage pass remains
conservative where it does not model them.

## Clean-build requirement

Incremental compilation retained a previously generated forms decoder despite
the changed nested AST types. Structural tests alone did not expose that stale
decoder; real ELP integration did. Run `make clean` when switching this baseline.

A clean codec derivation also exceeded the compiler's default thread stack.
`eqwalizer/.jvmopts` now supplies `-Xss8m`, recognized by the standard sbt launcher.
The full clean build and tests were rerun successfully with that stack size. If
invoking `sbt-launch.jar` directly, supply `java -Xss8m ... -jar sbt-launch.jar`;
direct Java invocation does not read `.jvmopts` itself.

## Validation

On Linux x86_64 with Java 21, OTP 27.3.4.1, rebar3 3.24.0 and Rust 1.100.0:

- Clean compilation and assembly passed; all 64 Scala tests passed.
- All 53 Erlang-source examples passed in ten modules, with ten execution probes.
- Nine Python runner tests and five synthetic process/IPC tests passed.
- The updated support library compiled separately with rebar3.

ELP was built from an exact Git archive of the target commit, using
`cargo build --locked -p elp --bin elp`, `CARGO_PROFILE_DEV_DEBUG=0`, and the
supported build-time JAR override (`ELP_EQWALIZER_PATH`). The source build and CI
use `SOURCE_DATE_EPOCH=1795033572`, the commit timestamp. Its version banner says
`build-2026-11-18` because the tagged commit predates the November 19 release;
the verified commit SHA, not the banner date, establishes provenance.

The independent feature CI workflow is pinned to that full SHA and builds ELP
for its OTP 27 test configuration. No native image or ELP source patch is needed.
Upstream Rust warnings occurred during the build but did not prevent it.

New OTP 29 syntax is validated at the Scala AST level; native OTP 29 parsing and
execution were not run in this environment. The real-source suite uses OTP 27
syntax, which still exercises the new wire shapes. Full upstream Rust/Erlang
snapshots, Buck, daemon/LSP behavior, other OTP/platform combinations, native
packaging and GitHub Actions execution remain outside this local validation.

## Next synchronization

Use `08c6a40:eqwalizer` as the next subtree baseline, and continue reviewing Rust
AST/IPC and CLI changes outside that subtree. Keep the source/JAR pair aligned,
perform a clean codec build after AST changes, and retain both structural and
real-source checks. See [ProjectStatus.md](../ProjectStatus.md) for the custom
checker's existing limitations.
