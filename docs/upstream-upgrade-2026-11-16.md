# Synchronization with ELP 2026-11-16

## Exact revisions

- Fork starting point: `c9ccceeab15a09c10bb211e03e8f50f88a330933` (`main`,
  tag `elp-2026-10-14`).
- Upgrade branch: `upgrade/elp-2026-11-16`.
- Previous upstream: `b36ba37346a8e6122fd309420cb67886ccc5871c`, tag `2026-10-14`.
- New upstream: `45a9286a9ebb09c04b1daa77834ca9833fb4b0dd`, tag `2026-11-16`.
- New upstream `eqwalizer/` tree: `2dd55815808728178f7430a3a5dabfd53ebe1adf`.

The tag was verified against the supplied commit prefix. As in the October
upgrade, the ELP `eqwalizer/` subtree maps to this repository's root. The Scala
project remains at `eqwalizer/`; no ELP monorepo history or Rust application code
is merged into the fork. The [October report](upstream-upgrade-2026-10-14.md)
documents the original migration and missing shared Git ancestry.

## Imported upstream changes

Only two files changed inside the upstream subtree across four commits:

| Commit | Change |
| --- | --- |
| `cc2024b21d684e24cc3a0c4a8d1676e907b724e7` | Add the `orddict:append_list/3` override with list-valued dictionary input and output. |
| `8bca89de0574ad9128916b6c653c55c608e3c7be` | Use OTP-owned `re:mp()` and `re:options()` types instead of duplicated regex input definitions. Upstream explicitly no longer supports OTP 26. |
| `0dd9916fe04135b1cd7e3b670934f09c54da04f3` | Remove the redundant simple-pattern fast path from ordinary clause-coverage checking. |
| `86a70ae15306fd66549c43bdb6ec5ba1e1e71dd7` | Use the updated selector environment when deriving case-pattern propositions. |

The delta was produced and applied with:

```sh
git diff --binary \
  b36ba37346a8e6122fd309420cb67886ccc5871c:eqwalizer \
  45a9286a9ebb09c04b1daa77834ca9833fb4b0dd:eqwalizer \
  > /tmp/eqwalizer-2026-11-16.patch
git apply --3way /tmp/eqwalizer-2026-11-16.patch
```

Both files applied cleanly. No custom code needed adaptation: exhaustiveness
checking, strict source comments, skipped-check suppression, private record
constructors and custom diagnostic serialization retain their previous source.
Scala, sbt, Java minimum and Scala-library dependencies are unchanged.

ELP itself has additional Rust database, dependency and CLI changes, including
its `eetf` dependency update. Comparing the serialized type definitions and IPC
message definitions found no changes to their files between these tags. The
actual November ELP build and source tests below verify the integration path;
an unchanged Scala subtree alone would not establish ELP compatibility.

## Validation and reproducibility

Validated on Linux x86_64 with Java 21, Scala 3.6.4, sbt 1.10.10, OTP 27.3.4.1,
rebar3 3.24.0 and Rust 1.100.0:

- 58 Scala regression tests passed.
- JAR assembly passed.
- All 49 literate Erlang examples passed through real ELP built from the exact
  new tag, with all nine module execution probes verified.
- Nine Python runner tests passed.
- Five synthetic process/IPC tests passed.
- The support library was compiled separately with rebar3.

The four new examples in
[release_compatibility.erl](../tests/exhaustiveness/src/release_compatibility.erl)
exercise ordered-dictionary list preservation, rejection of an incorrect scalar
result, and the compiled-regex and regex-options types. Existing Erlang fixtures
and Scala tests were retained unchanged.

The November release page had no prebuilt ELP assets at validation time. To avoid
using an October executable for November integration checks, ELP was built from
an exact Git archive of `45a9286`. The same source build is now pinned in the
separate feature CI workflow. The local build emitted upstream Rust warnings
but completed successfully; no upstream source or lockfile was patched.

To reproduce the ELP build with Java, Rust and matching OTP/rebar3 available:

```sh
# In this fork:
make build
# In a separate ELP checkout at the exact SHA above:
ELP_EQWALIZER_PATH=/absolute/path/to/eqwalizer-strict.jar \
SOURCE_DATE_EPOCH=1794844273 \
CARGO_PROFILE_DEV_DEBUG=0 \
CARGO_BUILD_JOBS=4 \
cargo build --locked -p elp --bin elp
# Back in this fork:
ELP=/absolute/path/to/elp/target/debug/elp make test test-runner smoke
```

The build-time JAR override avoids a GraalVM/native-image build. The source-test
runner supplies its own runtime checker wrapper, so it tests this fork rather
than ELP's stock checker. Normal OTP discovery is retained; the optional
`OTP_SOURCE` override remains available for a separate matching source tree.

## Limits and next steps

The full upstream Rust/Erlang snapshot suite, Buck loading, daemon/LSP behavior,
native-image packaging and other OTP/platform combinations were not validated
here. The new GitHub Actions workflow has been configured to reproduce the
source build; its execution on GitHub is separate from local validation.

This synchronization does not address the previously documented custom-checker
limitations (numeric literal approximation, repeated variables, traversal gaps,
overloaded specs, or product-space guards). See [ProjectStatus.md](../ProjectStatus.md)
and the literate fixtures for the current behavior.

Future deltas should use `45a9286:eqwalizer` as the content baseline. Keep ELP-side
protocol/CLI/project-loading changes under review even when the imported subtree
changes little or not at all. Merge/tag/publication remain separate from this
upgrade branch.
