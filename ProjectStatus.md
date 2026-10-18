# Project status: eqwalizer-strict

Last reviewed against the code: 2026-10-18.

Current upgrade branch: `upgrade/elp-2026-10-14-corrected-main`.
Fork baseline: `353831ebdf45bd3d0f264d8ffd3cac6d9d83aa45`.
Upstream baseline: ELP tag `2026-10-14`, commit
`b36ba37346a8e6122fd309420cb67886ccc5871c`.

The customizations originated on feature branches and were merged into the fork's
`main`. The upgraded implementation is on the branch above; this document does
not imply that the upgrade has been merged into `main`.
See the [upgrade report](docs/upstream-upgrade-2026-10-14.md) for history,
conflict resolutions, removed compatibility code, and future upgrade procedures.

## Current status

This standalone fork adds three features to upstream eqWAlizer:

- Experimental exhaustiveness diagnostics for `case` expressions and function clauses.
- Private constructors for ordinary Erlang records, enforced through source comments.
- Per-function suppression of skipped-exhaustiveness diagnostics through source comments.

The checker builds independently with Java 11+, sbt 1.10.10 and Scala 3.6.4. The
assembled JAR is `eqwalizer/target/scala-3.6.4/eqwalizer-strict.jar`. Analyzing real
Erlang projects still requires ELP to supply ASTs, specs, types and other IPC data.
See the [README](README.md) for the executable-wrapper setup.

Validation includes 58 Scala regression tests, JAR assembly, five synthetic
process/IPC tests, and the fork-owned [literate Erlang feature suite](tests/exhaustiveness/README.md):
45 source-based examples in eight modules, run through real ELP `2026-10-14` and
OTP 27.3.4.1. Nine Python tests cover the source suite's runner. The maintainer
also reported that the upgraded checker works locally. The complete upstream
ELP/Erlang suite has not been run here; targeted feature tests do not replace it.

## Exhaustiveness checking

### Activation and pipeline

`Pipeline.checkFun` runs ordinary checking through the unified elaborator, then
runs the custom exhaustiveness pass:

```scala
ctx.elab.checkFun(f, spec)
if (ctx.exhaustiveCaseChecking) ctx.exhaustiveCase.checkFun(f, spec)
ctx.diagnosticsInfo.popErrors()
```

`PipelineContext` enables the pass by default:

```scala
val exhaustiveCaseChecking: Boolean =
  options.exhaustiveCaseChecking.getOrElse(true)
```

Scala callers can disable it with `Options(exhaustiveCaseChecking = Some(false))`.
There is no `eqwalizer.config` or `EQWALIZER_EXHAUSTIVE_CASE_CHECKING` switch wired
into this fork. No configuration entry is needed to enable the default behavior.
Overloaded specs take a separate checking path and do not run the custom pass.
Unspecced functions use dynamic argument/result types, which usually cause
coverage analysis to skip unless a supported catch-all handles them.

The pass trusts declared specs even when ordinary checking reports body type
errors. It does not require ordinary checking to succeed. It is experimental:
unsupported coverage usually produces `skipped_exhaustiveness_check`, but the
coarse approximations and traversal limitations below mean that absence of a
warning is not a general proof of exhaustiveness.

### Alternatives and limits

The implementation is in
[ExhaustiveCase.scala](eqwalizer/src/main/scala/com/whatsapp/eqwalizer/tc/ExhaustiveCase.scala).
It expands supported aliases/unions into a list of abstract alternatives, then
removes alternatives wholly covered by supported patterns and guards.

`simpleAlternatives` currently accepts:

- Atom literals and broad `atom()`.
- `integer()`, `float()`, `binary()`, `pid()`, `port()`, `reference()` and `fun()`.
- Nil, tuple types, ordinary record/refined-record types, list types and map types.
- Remote aliases whose bodies can be expanded into this subset.
- Unions with at most 10 immediate members, recursively expanded.

A broad type such as `atom()` or `integer()` is represented as one abstract
alternative, not enumerated into its possible values. Tuple fields, list contents
and map entries are not generally expanded into separate coverage cells.
`AnyType` (`any()`/`term()`), `DynamicType`, bounded dynamic types, intersection
types, cons types and native-record types are not accepted by this helper.
Catch-all paths can still accept some otherwise unsupported domains.

### Function clauses

Function coverage chooses these paths in order:

1. For arity greater than one, a supported final unguarded catch-all across every
   argument proves coverage without enumerating a product. All clauses must match
   the spec arity, and non-final patterns must be supported simple patterns.
   Non-final guards may be unsupported; they are ignored in this path.
2. Otherwise, multi-argument functions attempt product-space checking. Each
   argument must expand through `simpleAlternatives`; there are caps of four
   arguments and 128 abstract cells. Broad atom/integer types are accepted as
   abstract dimensions. Clauses with guards are unsupported in this path.
3. If building the product fails, the checker tries a single-interesting-argument
   path: one selected argument is analyzed while every other position must be a
   variable or wildcard in every clause. Single-argument functions use this path
   directly. Supported simple guards can refine the selected argument.

Zero-argument functions and empty clause lists do not produce function-coverage
warnings. Arity mismatches skip. A successful product construction followed by an
unsupported guarded clause skips; it does not fall back to guard refinement.

### Case expressions and selector types

The custom pass recognizes selector types for:

```text
Var(V), when V is in its small environment
Tuple(Elems), when every element type is known
LocalCall(F, Args), from the declared result type
RemoteCall(M:F, Args), from the declared result type
DynCall(FunExpr, Args), from a recognized function type and matching arity
```

Dynamic-call result lookup also handles aliases, bounded dynamic function types,
any-arity function result types, and unions with recognized function members.
It does not implement intersection-based function result selection.

Call selector typing instantiates the declared spec; it does not specialize the
result from actual arguments or callbacks. A helper declared to return `any()`
still supplies `any()`, even if a predicate argument suggests a narrower result.

The initial environment binds only top-level variable patterns in function
clauses to their spec argument types. It does not propagate bindings introduced
by body matches or refine environments for nested clauses/lambdas. Arbitrary
selector expressions do not use the full elaborator for inferred types.

For an ordinary recognized selector, an unguarded variable/wildcard clause can
prove coverage before inspecting other patterns. Tuple selector expressions have
a separate final-catch-all path: preceding clauses must have one supported tuple
pattern of the selector's arity. That path ignores preceding guards. Selector
typing must still succeed before this tuple-specific path runs.

### Patterns and guards

Supported simple patterns are:

```text
_ and variables
atom literals
nil
integer and float literal patterns, coarsely modeled as integer() | float()
tuples whose elements are supported patterns
ordinary record patterns with no explicit fields or generic field pattern
match aliases with a variable on either side of a supported pattern
```

Tuple/tagged-tuple alternatives are supported, but coverage uses whole-alternative
subtype tests rather than general tuple-space subtraction. Record field-pattern
refinement is outside this simple pattern model.

For ordinary case coverage and the single-interesting-argument path, a guard must
be exactly one supported test over the selected variable or a recognized alias:

```text
is_atom       is_binary     is_bitstring   is_boolean
is_float      is_function  is_integer     is_list
is_number     is_pid       is_port        is_reference
is_map        is_tuple
is_record(X, RecordName)
```

`is_integer` uses `IntegerType`, `is_float` uses `FloatType`, and `is_number` uses
their union. `is_record/2` requires a literal record name. `is_record/3`, multiple
tests/guard alternatives, comparisons and arbitrary expressions are unsupported
for custom refinement. Upstream's ordinary typechecker may support these forms
independently. Product-space guard refinement is not implemented.

### Binary coverage

A separate narrow path splits a `binary()` domain into:

```text
<<>>
<<_, _/binary>>
```

It recognizes empty binaries, an open binary variable/wildcard tail, and a
nonempty byte followed by an open binary tail, such as `<<First, Rest/binary>>`.
The first segment must use a signed/unsigned integer specifier and an implicit
or literal eight-bit size. Open tails accept `Binary` or `Bytes` specifiers.
The binary domain can also be reached through a remote alias.

This path does not support guards, arbitrary sizes, UTF segments, general
bitstring layouts or exact lengths beyond empty versus nonempty. It emits a skip
for unsupported patterns/guards it encounters. The ordinary unguarded catch-all
check runs before this binary-specific analysis.

### Diagnostics and suppression

The custom diagnostic codes are:

| Code | Meaning |
| --- | --- |
| `non_exhaustive_case` | Supported analysis leaves case alternatives uncovered. |
| `non_exhaustive_function` | Supported analysis leaves function inputs uncovered. |
| `skipped_exhaustiveness_check` | Coverage cannot be analyzed by the selected path. |

Reasons include unknown selector types, unsupported patterns/guards/domains,
clause arity mismatches, and product-space limits. Custom diagnostics omit the
structured Rust payload while retaining their code, message and range; the target
ELP protocol permits an optional structured diagnostic.

To suppress skipped-check diagnostics for one function in full-module checking:

```erlang
% eqwalizer_strict:disable_error skipped_exhaustiveness_check
-spec f(term()) -> ok.
f(_) -> ok.
```

The comment must be immediately followed by the `-spec` line; the parser supports
continuation lines beginning with whitespace. Only
`skipped_exhaustiveness_check` is accepted by this directive. Suppression is keyed
by function name and arity; it does not disable missing-case diagnostics or turn
off checking globally. The example above illustrates syntax; its catch-all does
not itself need suppression.

### Known exhaustiveness limitations

- Integer/float literal patterns are modeled as covering the whole numeric union,
  not individual values. This can miss non-exhaustive numeric matches; do not
  treat a warning-free result as a numeric exhaustiveness proof.
- Variable patterns are treated as catch-alls syntactically. Repeated variables
  impose Erlang equality constraints that the custom coverage model does not track.
- Domains are abstract buckets. The checker cannot generally subtract partially
  covered tuple/list/map alternatives and may report missing coverage when several
  narrower patterns jointly cover a bucket.
- There is no cycle guard in remote-alias expansion in this pass. Recursive aliases
  are not a supported coverage domain.
- Traversal visits selected expression forms, not every AST node. For example,
  `If`, `Cons`, and new native-record expression forms have no traversal case, and
  comprehension qualifiers/binary size expressions are not visited. Nested cases
  inside unvisited positions can be missed without a skipped-check diagnostic.
- Environment propagation, overloaded specs, general guards/bitstrings, and
  predicate-driven call-result specialization remain unsupported as described above.

## Private constructors and strict directives

The active source-comment contract is:

```erlang
% eqwalizer_strict:private_constructor my_api
-record(my_type, {value}).
```

The comment must be immediately followed by `-record(...)`. The owner must be an
explicit unquoted atom matching the parser's simple atom syntax; `?MODULE` is not
expanded by this source scanner. Place the directive in each checked module's
source or an included header visible through its converted `File` forms.

The owner module may create and update `#my_type{}`. Other modules that load the
directive receive `private_constructor_violation` when constructing/updating it.
Pattern matching and field access remain allowed. Multiple owners for a record
produce `invalid_private_constructor` when construction/update is checked. This
feature applies to ordinary records, not upstream's new native records.

`RawEqwalizerStrictAttributes` scans distinct source paths listed by converted
`File` forms. Malformed directives produce `invalid_eqwalizer_strict_attribute`;
unreadable paths produce `unreadable_eqwalizer_strict_file`. The scanner reads raw
UTF-8 source and uses line patterns, not a complete Erlang parser. It accepts
comment directives rather than `-eqwalizer_strict(...)` attributes.

`Pipeline.checkForms` loads and merges directives, applies owners during checking,
and filters disabled diagnostics per function. The `ipc-check-funs` entry point
calls `Pipeline.checkFunction` directly and does not load source comments; this
is an existing difference from full-module checking. The retained
`PrivateConstructorsManifest` helper has no callers in the active pipeline.

## Current AST and protocol compatibility

The target model uses:

- An integer `FunType.forall` count with `BoundVarType`/`FreeVarType`.
- Type-declaration lookup replies containing `(TypeDecl, List[Variance])`, with
  variance metadata supplied by ELP.
- The target map codecs, including array encoding for type-map properties.
- Target `Specifier` names such as `UnsignedInteger`, `Binary` and `Bytes`.
- Intersection, cons and native-record forms in the ordinary typechecker, even
  where the custom exhaustiveness pass does not support them.

The historical ELP `3a65019` list-forall/VarType/object-map backports are obsolete.
Compatibility with that old ELP revision is not maintained. Upstream's precise
`maps:merge/2` behavior is unconditional; the old `custom_maps_merge` flag is gone.
The former serialized private-constructor attribute is not part of the active
comment-based contract.

## Build and validation

From the repository root, with Java, sbt and Python 3 available:

```sh
make test
make build
make smoke
make jar-path
make feature-test         # uses your compatible ELP/OTP installation
make feature-test-runner  # Python-only tests of the annotation/JSON adapter
```

`make test` runs 58 Scala tests covering the supported exhaustiveness paths and
limits, selector calls, numeric separation, directives, private construction and
allowed reads/patterns, pipeline activation, type/codec changes, type-declaration
variance replies and free-variable constraints. `make smoke` assembles the JAR and
runs five process tests using synthetic ELP replies, checking diagnostics,
suppression and private-constructor enforcement. The original CI workflow runs
these standalone Scala/IPC checks. The separate feature workflow additionally runs real ELP source
checks using a pinned prebuilt release; neither builds an external ELP checkout
or a native image.

The fork's behavioral examples now live in executable Erlang source rather than
being duplicated here. Each `%% @test` region contains an explanation and exact
expected diagnostics (or `%% @expect-clean`):

| Feature | Literate source |
| --- | --- |
| Atom/case/function coverage, tuples, products, catch-alls and limits | [exhaustiveness.erl](tests/exhaustiveness/src/exhaustiveness.erl) |
| Binary empty/nonempty coverage | [binary_coverage.erl](tests/exhaustiveness/src/binary_coverage.erl) |
| Local, remote and dynamic selectors | [selector_calls.erl](tests/exhaustiveness/src/selector_calls.erl) |
| Supported and unsupported guard refinements | [simple_guards.erl](tests/exhaustiveness/src/simple_guards.erl) |
| Per-function suppression | [strict_directives.erl](tests/exhaustiveness/src/strict_directives.erl) |
| Invalid directive diagnostics | [invalid_directives.erl](tests/exhaustiveness/src/invalid_directives.erl) |
| Private constructors through shared headers | [box_owner.erl](tests/exhaustiveness/src/box_owner.erl), [box_client.erl](tests/exhaustiveness/src/box_client.erl), [private_box.hrl](tests/exhaustiveness/include/private_box.hrl) |

The source suite is isolated under `tests/exhaustiveness/`, with its own Python runner
and [CI workflow](.github/workflows/features.yml); the imported Scala sources and
existing tests are unchanged. It uses actual ELP parsing and this fork's JAR,
checks ordinary diagnostics as well as custom ones, and verifies a temporary
missing-case probe in every module to reject unexecuted examples. Numeric and
repeated-variable limitations are explicit current-behavior examples rather than
assertions of soundness. See the [feature-suite README](tests/exhaustiveness/README.md)
for Linux Mint prerequisites, ELP/OTP versions, annotation syntax and debugging.

For broader local integration, use this fork's JAR through ELP's executable
override and run real projects and upstream ELP fixtures as described in the README.

## Remaining work

- Address numeric-literal and repeated-variable coverage limitations.
- Extend traversal and environment propagation, with targeted regressions.
- Add product-space guard refinement and broader pattern/bitstring support.
- Add a runtime/configuration switch if needed; the current Scala option is not
  exposed through ELP configuration or an environment variable.
- Decide a default policy for skipped-check noise beyond the existing per-function
  suppression directive.
- Extend the source-based suite as features change, and run the broader upstream
  ELP suite separately. Spec-driven call-result specialization and support for
  overloaded specs remain separate feature work.
