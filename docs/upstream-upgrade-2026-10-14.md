# Upgrade to ELP 2026-10-14

## Scope and provenance

- Fork starting point: `a214469c9601bf8b58f6af439936e2e9978ab03a` (`origin/main`).
- Upgrade branch: `upgrade/elp-2026-10-14`. Existing history is preserved.
- Upstream: `WhatsApp/erlang-language-platform`, tag `2026-10-14`, exact commit
  `b36ba37346a8e6122fd309420cb67886ccc5871c`.
- Imported subtree: `eqwalizer/`, whose tree is
  `7fa7f707b101acc02542fd98dc92127c4e5b85eb`. Inside ELP the sbt project is
  `eqwalizer/eqwalizer/`; here it remains `eqwalizer/`.
- Archived standalone endpoint: `WhatsApp/eqwalizer`
  `01f151ca206bb019db0999a2c9c8c8aaae79bbab`.
- Fork/archive merge base: `854896722776ed3b6c3bad5f7fff0b1ee028d97e`.

No Rust/ELP application code was imported or modified. All production Scala and
support-library changes are local to the standalone eqWAlizer repository.

## History investigation and integration strategy

ELP originally used an eqwalizer Git submodule. Immediately before migration its
gitlink pointed to `88258fddd372f93b82a2e64fb88a558de0669f98`. ELP commit
`fada2643dffdfacddd16e20152056f98b4eb0796` replaced the gitlink with repository
contents. This was a snapshot import with additional changes, not a merge of the
original repository's commit ancestry. Comparing that import with the former
submodule version shows changes in 12 files, including CI removal, numeric and
AST changes, and checker fixes. Thus the import must not be treated as an
identical copy of the archived endpoint.

The archived endpoint is nevertheless an appropriate common *content baseline*:
it is the last standalone upstream snapshot of the same project/layout, contains
the former submodule commit in its ancestry, and its source has been compared
directly with the imported/current trees. The archive's subsequent numeric,
tuple, guard, maps:fold and setelement changes are represented in the target.
The diff to the target explicitly includes migration-time differences as well as
later changes. The archive is not a Git ancestor of this fork's current tip;
that is why a baseline-to-target content delta was used instead of pretending
there was shared migration ancestry.

Experiments:

```sh
git subtree split --prefix=eqwalizer b36ba37346a8e6122fd309420cb67886ccc5871c
git subtree split --prefix=eqwalizer \
  --onto=88258fddd372f93b82a2e64fb88a558de0669f98 \
  b36ba37346a8e6122fd309420cb67886ccc5871c
```

Both produce `3056fb4472951a7a5845dfb6ae1245f7ed4aae44`. Its first extracted
commit is `d5abf3d4` (the snapshot import), and `git merge-base` finds no common
ancestor with the fork. `--onto` does not invent the missing link. A normal
ancestry merge therefore has no useful migration base; merging unrelated
histories would create unnecessary add/add conflicts.

The chosen fallback generated a binary-capable delta from the archived root to
`b36ba37:eqwalizer`, then used `git apply --3way`. The first attempt could not
delete the fork-modified `Check.scala` and `ElabGuard.scala`. Applying non-deletion
changes with three-way merging and resolving those two deletions explicitly
preserved the useful merges while allowing architectural adaptation. No commits
were rebased, and no ELP monorepo ancestry was merged.

## Imported upstream changes

- Unified ordinary checking into `Elab`; removed `Check` and `ElabGuard`.
- Added intersection types, cons types, none-aware constructors, and native
  record AST/types/IPC support.
- Updated subtype/overlap/meet logic, occurrence analysis, guard narrowing, map
  propositions, tuple handling, and constraints.
- Incorporated fixes for lambda environments, dynamic calls, maybe/receive/catch
  expressions, record matching, map updates and maps:filter behavior.
- Updated bundled OTP specs/types and eqwalizer_support behavior, including the
  built-in dynamic type and `elp:ignore`.
- Retained upstream license notices and supplied LICENSE-APACHE and LICENSE-MIT.

The Java requirement (11+), Scala 3.6.4, sbt 1.10.10, config 1.4.3 and
jsoniter-scala 2.33.3 are unchanged. Only test dependencies were added: MUnit
1.0.4 and the existing jsoniter macros version in Test scope.

## Customization inventory and conflict resolutions

| Customization | Result |
| --- | --- |
| Exhaustiveness enabled by default | Preserved after `Elab.checkFun`; explicit Scala option still disables it. |
| Atom unions, tagged tuples, ordinary/simple guards, aliases | Preserved and adapted to target AST/types. |
| Function clauses, single interesting argument, product spaces | Preserved, with arity 4 / 128-cell caps. Product-space guards still skip. |
| Final catch-all with unsupported earlier guards | Preserved. |
| Empty/nonempty binary checking | Preserved using target Specifier enum names. |
| Local/remote/dynamic call selector typing | Preserved using declared result types. |
| Private record constructors | Preserved on creation/update in the unified elaborator; owner allowed, other modules rejected, conflicting owners diagnosed. Pattern matching/field access remain allowed. Applies to ordinary records, not the new native records. |
| Strict source comments and header scanning | Preserved in the normal module-checking pipeline, including unreadable-source diagnostics. |
| Per-function `disable_error skipped_exhaustiveness_check` | Preserved. |
| Custom diagnostics without structured Rust payloads | Preserved; target Rust declares `diagnostic: Option<StructuredDiagnostic>`. |
| `eqwalizer-strict` project/JAR identity | Preserved. Standalone Buck output depth retained rather than importing ELP's extra directory level. |
| Old list-forall/VarType/object-map AST backports | Superseded by target counted-forall, BoundVarType/FreeVarType and target map codecs. No ELP 3a65019 compatibility claim. |
| Collapsed NumberType and old numeric/tuple/setelement compatibility behavior | Superseded by upstream integer/float distinctions and current tuple/map behavior. `is_integer` no longer treats floats as covered. |
| `custom_maps_merge` compatibility flag | Removed in favor of upstream's unconditional precise maps:merge handler. |
| Legacy dynamic_cast/fix_me support APIs | Removed as upstream did; migrate consumers if they still call them. |
| Old EqwalizerPrivateConstructor serialized form | Removed: the original active enforcement already read source comments/options, not this inert AST variant. No converter changes needed. |
| PrivateConstructorsManifest helper | Retained unchanged. It had no callers in the original active pipeline; it is not newly wired into enforcement. |
| Stale external ELP/native-image CI | Replaced with standalone Scala and synthetic IPC validation; fixed JAR name in Make targets. |

The principal conflicts were in Pipeline, AST/types/type variables, constraints,
Elab, ElabApplyCustom, ElabPat, Narrow, Occurrence, Subtype and diagnostics. The
target supplies the core typechecker and protocol; the custom hooks and diagnostics
were retained explicitly. This avoids carrying the old checking architecture or
old serialization backports alongside the new implementation.

## Validation

Validated on Linux with Java 21:

```sh
make test        # 56 Scala tests, all passed
make smoke       # builds JAR, then 5 synthetic process/IPC tests, all passed
make jar-path    # eqwalizer/target/scala-3.6.4/eqwalizer-strict.jar
```

The Scala tests cover exhaustiveness paths/limits, private constructors including
updates and allowed reads/patterns, strict directive parsing, the normal function
pipeline hook/disable option, numeric separation, intersection/cons constructors,
substitution/instantiation and target codec round trips. Lookup-dependent unit
tests supply deterministic IPC replies; they do not run ELP.

The process tests launch the assembled JAR and perform the real message/byte
handshake with synthetic replies, exercise the full module pipeline and source
comment loading, and assert serialized diagnostics and suppression. They are not
empty-input readiness checks and are not full Erlang integration tests.

Upstream's Erlang fixtures and Rust snapshot runners live outside the extracted
subtree (`test/test_projects/eqwalizer_tests` and
`crates/elp/src/resources/test/eqwalizer_tests`). They were inspected but not run,
as agreed: full ELP/Erlang integration remains a local validation step. GraalVM
native packaging, compilation of the support Erlang library, and the GitHub CI
execution itself were also not run here. No unresolved failures remain in the
executed Scala or synthetic IPC checks.

## Local follow-up and future upgrades

1. Use ELP exactly at the pinned target with this fork's JAR/executable override,
   not its stock bundled checker. See the README wrapper instructions.
2. Run the upstream Erlang/snapshot tests and your real projects. The richer type
   model and bundled OTP overrides can change ordinary diagnostics deliberately.
3. Exercise strict comments in actual `.erl`/`.hrl` paths. The checker must be able
   to read those source paths; the synthetic tests do not establish ELP's real
   file mapping. Per-function IPC (`ipc-check-funs`) retains the original behavior:
   it does not load source comments as the full-module pipeline does.
4. Overloaded specs remain outside the custom exhaustiveness pass. Numeric literal
   patterns retain its coarse model; unsupported guards, recursive aliases, and
   large domains retain existing limitations. New intersection, cons and native
   record types skip conservatively unless supported catch-all handling applies.
5. Keep future custom behavior covered by Scala tests and keep protocol adaptations
   separate from checker features. Product-space guard refinement, new flags and
   skipped-warning policy are separate feature work.

For the next upgrade, pin both old and new ELP commits, then generate a delta
between their **subtrees**, using the current target as the old baseline:

```sh
git fetch https://github.com/WhatsApp/erlang-language-platform.git <new-tag>
git diff --binary \
  b36ba37346a8e6122fd309420cb67886ccc5871c:eqwalizer \
  <new-commit>:eqwalizer > /tmp/eqwalizer-upstream.patch
# From a new upgrade branch with a clean checkout:
git apply --3way /tmp/eqwalizer-upstream.patch
```

Review deletions and architectural changes explicitly, then rerun the tests.
Keep the upstream SHA and subtree prefix in each upgrade report/commit. Do not
retry `--onto` as if ancestry had been restored, rebase the fork commit by commit,
or merge the entire ELP repository. A dedicated future vendor-history bridge is
possible but should be introduced explicitly with an audited snapshot mapping.
