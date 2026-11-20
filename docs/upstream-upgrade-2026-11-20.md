# Synchronization with ELP 2026-11-20

## Provenance and imported sources

- Starting fork `main`: `ee8cdbf3c8d7c115580143f9da251f6bca734824` (synchronization tooling and continuation notes).
- Integration branch: `upgrade/elp-2026-11-20`, created from current `main` after
  fetching `origin/main`; there were no outstanding remote changes.
- Previous upstream: `08c6a40b9f09d66e69e23d0154a30e4582ce4f07` (`2026-11-19`).
- Target tag: `2026-11-20`, fetched from WhatsApp/erlang-language-platform and
  resolved to `f299499f3e7eeef7b91be3f96e846076083d590f`.
- Both releases' `eqwalizer/` trees: `bca61becd0e95e83ac9446375b566007c54d6323`.

The complete imported subtree is identical, including Scala sources, build files
and support-library specs. The synchronization tool verified the previous tree,
reported zero subtree changes and zero overlapping fork edits, and generated an
empty upstream-to-upstream delta. Its `--apply` operation therefore required no
source edits. No merge ancestry or monorepo code was introduced. The authoritative
baseline advances in `upstream.json`; checker behavior remains unchanged.

## Review outside the checker subtree

Nine upstream commits follow the previous baseline; their net diff changes 40
files, all outside `eqwalizer/`.

| Changes | Compatibility impact |
| --- | --- |
| `a2ce91289` adds `elp eqwalizer-onboard` and extracts shared CLI typechecking and Buck source-selection helpers. | The normal CLI now uses the extracted typechecking helpers. Validate our source suite with the new ELP; the optional onboarding/opt-in workflow and Buck selection need separate coverage. |
| `33b9bc4c1` fixes OTP discovery for root paths containing non-Latin-1 characters. | Changes the `erl` system-info probe to write native filename bytes. Normal discovery is exercised by the source suite; Unicode-root/locale permutations are not implied by that check. |
| `eb7c64c53` adjusts W0095 for variables passed to macros; `c1b521e92` preserves macro-clause separators in head-mismatch diagnostics. | Native ELP lint diagnostics change, not the serialized checker AST or strict-comment scanner. |
| `f299499f3` adds W0097, suggesting OTP 29 `is_integer/3` for range checks. | A native linter with an OTP-version gate; no new custom exhaustiveness coverage is introduced. |
| `331e4ad96` enables GitHub annotations by default; `1a52dedd0` reverts it. | No net default-output change. |
| `4cc483a09` bumps the VS Code extension; `1137af7d5` adds adoption documentation. | No checker-source impact. |

There are no changes to Rust checker IPC, serialized AST/type definitions, or the
Erlang parsing service between the two tags. The normal CLI's diagnostics still
use the same checker messages and optional structured payloads. Fork hooks remain
confined to the checker module. No work from TODO items #4 or #6 is included; this
review follows #5's integration-monitoring discipline without claiming it complete.

## Validation

On Linux x86_64 with Java 21, OTP 27.3.4.1 and Rust 1.100.0:

- Scala suite: all 64 tests passed; JAR assembly passed.
- Real ELP CLI: all 53 Erlang-source examples passed in ten modules, with all ten
  execution probes verified.
- Python runner tests: nine passed.
- Offline synchronization tests: eight passed.
- Synthetic process/IPC smoke tests: five passed.
- The new `eqwalizer-onboard --help` command was checked; its typechecking and
  source opt-in workflow were not tested.

ELP was built from an exact Git archive of the target commit with
`cargo build --locked -p elp --bin elp`, `CARGO_PROFILE_DEV_DEBUG=0`, two build
jobs, and `ELP_EQWALIZER_PATH` pointing to the fork's assembled JAR. The build
used `SOURCE_DATE_EPOCH=1795172616`, the target commit's timestamp, and reports
`elp 1.1.0+build-2026-11-20`. The source runner also overrides the checker at
runtime, so these examples exercise this fork rather than a stock checker.
Upstream Rust warnings did not prevent the build.

No clean codec regeneration was needed: the complete checker subtree and wire
model are unchanged. The Scala build incrementally recompiled the previously
formatted fork helpers. The support library is also unchanged and was not
recompiled separately for this release.

`upstream.json` now records this release as both imported and tested; CI consumes
its updated ELP commit automatically. Full upstream Rust/Erlang snapshots, Buck,
daemon/LSP updates, the Unicode OTP-root/locale matrix, the onboarding opt-in
workflow, native OTP 29 syntax, packaging and GitHub Actions execution were not
validated here. Merging, tagging and publication remain separate actions; this
synchronization is committed on the integration branch.

## Next synchronization

Read the imported and tested baselines from [upstream.json](../upstream.json),
then use the [synchronization workflow](synchronization.md). Even when the checker
tree is unchanged, review ELP CLI, project loading and diagnostics separately.
