# Project status: exhaustive case checking

Branch:

```text
feature/exhaustive-case-checking
```

Last updated: 2026-06-09.

## Goal

This branch adds an experimental exhaustiveness checker to eqwalizer. The checker covers Erlang `case` expressions and function clauses. It is deliberately conservative: it reports missing cases when it can prove them, and emits a `skipped_exhaustiveness_check` diagnostic when the code shape or type is outside the supported subset.

The implementation was also backported to work with the AST JSON emitted by ELP commit:

```text
WhatsApp/erlang-language-platform@3a65019
```

## Current status

The branch builds and runs against the user's ELP `3a65019` setup after several AST compatibility fixes.

The exhaustiveness checker is currently enabled by default in code:

```scala
val exhaustiveCaseChecking: Boolean =
  options.exhaustiveCaseChecking.getOrElse(true)
```

So no `eqwalizer.config` entry is currently required.

The checker now supports all of the following broad areas:

* `case` exhaustiveness over finite flat unions;
* function-clause exhaustiveness over finite flat unions;
* tuple/tagged tuple patterns;
* simple guard refinements such as `is_atom/1`, `is_binary/1`, and `is_record/2`;
* empty/non-empty `binary()` coverage;
* selector typing for simple calls and dynamic function calls;
* tuple selector expressions such as `case {A, B} of ... end` in a conservative catch-all form;
* multi-argument functions with a final catch-all clause, including non-final clauses with unsupported guards;
* finite product-space checking for multi-argument functions without requiring a final catch-all.

## Main implementation file

```text
eqwalizer/src/main/scala/com/whatsapp/eqwalizer/tc/ExhaustiveCase.scala
```

Other important files touched by this branch include diagnostics, ELP diagnostic conversion, AST models/codecs, type variables, subtype checks, and type mismatch handling.

## Checker architecture

The checker is invoked from the normal pipeline after ordinary function checking:

```scala
ctx.check.checkFun(f, spec)
if (ctx.exhaustiveCaseChecking) ctx.exhaustiveCase.checkFun(f, spec)
ctx.diagnosticsInfo.popErrors()
```

So the checker trusts the function spec even if the body has type errors. It does not require ordinary typechecking to succeed.

At a high level, the checker works by:

1. obtaining a scrutinee or argument type;
2. expanding it into a small finite set of alternatives when possible;
3. processing clauses in order;
4. subtracting alternatives covered by supported patterns and supported guards;
5. reporting remaining alternatives as non-exhaustive;
6. emitting `skipped_exhaustiveness_check` if the shape is unsupported.

Unsupported code should not produce false missing-case diagnostics. One exception is the multi-argument final-catch-all fast path: unsupported non-final guards are ignored there because the final unguarded catch-all already proves that every remaining input is handled.

## AST and diagnostic compatibility for ELP 3a65019

The fork originally expected a newer or different AST JSON shape than ELP `3a65019` emits. The Scala-side decoding was adjusted for that older format, including:

* `FunType.forall` as `List[Int]` rather than an integer count;
* type declarations encoded as JSON objects;
* map type props encoded as JSON objects with string keys;
* ELP binary segment specifier enum names;
* additional external form variants that are ignored or passed through by Scala.

New exhaustiveness diagnostics are emitted as normal diagnostics without unsupported structured payloads, because ELP `3a65019` has a fixed Rust enum for structured diagnostics.

## Case expression support

The checker supports finite flat-union `case` exhaustiveness, simple guard refinements, binary empty/non-empty coverage, and selector typing for:

```text
Var(V)
Tuple(Elems), when all element types are known
LocalCall(F, Args)
RemoteCall(M:F, Args)
DynCall(FunExpr, Args), when FunExpr has a known FunType
```

Tuple selector expressions such as this are accepted through a conservative catch-all fast path:

```erlang
-type t(T) :: none | {some, T}.

-spec apply3(t(fun((A) -> B)), t(A)) -> t(B).
apply3(FnOption, ValueOption) ->
    case {FnOption, ValueOption} of
        {{some, Fun}, {some, Value}} ->
            {some, Fun(Value)};
        _ ->
            none
    end.
```

Important limitation: selector typing trusts the declared result type. It does not specialize a polymorphic or callback-driven function result based on actual arguments. If `get_value/3` is specced as returning `any()`, the selector is still seen as `any()`.

## Function-clause exhaustiveness

The checker supports function-clause exhaustiveness using several paths.

### Single-argument finite unions

```erlang
-type abc() :: a | b | c.

-spec f(abc()) -> ok.
f(a) -> ok;
f(b) -> ok.
```

Expected diagnostic:

```text
Function f/1 does not handle: 'c'
```

### Single-interesting-argument multi-argument functions

The checker supports a conservative multi-argument mode where exactly one argument position is interesting and all other argument positions are variables or wildcards in every clause.

```erlang
-spec f(a | b | c, term()) -> ok.
f(a, _) -> ok;
f(b, _) -> ok.
```

Expected missing case:

```text
c
```

### Multi-argument final catch-all fast path

The checker accepts multi-argument functions where the final clause is an unguarded catch-all across all arguments:

```erlang
-type t(T) :: none | {some, T}.

-spec apply3(t(fun((A) -> B)), t(A)) -> t(B).
apply3({some, Fun}, {some, Value}) ->
    {some, Fun(Value)};
apply3(_, _) ->
    none.
```

This path does not enumerate the product space. It is a conservative proof that the final clause covers every remaining combination.

The fast path requires:

* arity greater than 1;
* all clauses matching the spec arity;
* a final unguarded catch-all clause such as `f(_, _) -> ...`;
* non-final clauses using supported simple patterns.

Non-final guards are ignored in this fast path. This is sound for exhaustiveness because a guard can only make a non-final clause cover less; the final unguarded catch-all still handles everything left over.

For example, this should not warn even though `X > Y` is not modeled as a guard refinement:

```erlang
-spec max(integer(), integer()) -> integer().
max(X, Y) when X > Y -> X;
max(X, Y) -> Y.
```

### Finite product-space exhaustiveness

Finite product-space checking is implemented for multi-argument function clauses without requiring a final catch-all, subject to explicit caps:

```scala
private val maxProductArity = 4
private val maxProductCells = 128
```

Each argument type must expand through `simpleAlternatives`; broad types such as `any()`, `term()`, `dynamic()`, unconstrained `atom()`, and unconstrained `integer()` are not finite product dimensions.

Product-space guard refinement is not implemented yet. Guarded product-space clauses are still a follow-up area.

## Supported pattern forms

The checker currently supports these simple patterns:

```text
_
X
atom literals
nil
integer/number patterns, coarsely as number()
tuple patterns
record patterns in a limited form
match aliases
binary patterns in a special narrow binary path
```

Tuple patterns and tuple alternatives are supported, including tagged tuple unions such as:

```erlang
-type t(E, A) :: {left, E} | {right, A}.

-spec is_left(t(any(), any())) -> boolean().
is_left({left, _}) -> true;
is_left({right, _}) -> false.
```

## Supported guard forms

The checker supports simple single-guard tests of the form:

```erlang
X when is_atom(X) -> ...
X when is_integer(X) -> ...
X when is_binary(X) -> ...
X when is_record(X, foo) -> ...
```

The guard must be a single supported predicate over the selected pattern variable or alias.

Supported simple unary predicates include:

```text
is_atom
is_binary
is_bitstring
is_boolean
is_float
is_function
is_integer
is_list
is_number
is_pid
is_port
is_reference
is_map
is_tuple
```

These guards are used for actual refinement in ordinary `case`, single-interesting-argument function, and product-space paths. In the multi-argument final-catch-all fast path, unsupported non-final guards are ignored rather than treated as a reason to skip.

`is_record/2` is supported. `is_record/3` is intentionally not implemented yet.

## Binary-size coverage

The checker has a deliberately narrow model for `binary()` exhaustiveness. It splits `binary()` into:

```text
<<>>
<<_, _/binary>>
```

Supported binary patterns include empty binary, open binary, and simple non-empty binary patterns such as:

```erlang
<<First, Rest/binary>>
```

This is not a general Erlang bit syntax model. It does not yet support variable sizes, non-byte-aligned bitstrings, UTF segments, arbitrary bitstring specs, or exact byte lengths beyond empty versus non-empty.

## Skipped-check diagnostics

The checker emits explicit warnings when it cannot analyze a construct, for example:

```text
skipped_exhaustiveness_check
```

Typical skipped reasons include:

```text
selector type is not known
scrutinee type is outside the supported flat-union subset
product space includes an argument type outside the supported flat-union subset
product-space arity N exceeds limit 4
product space has N cells, above limit 128
pattern is outside the supported subset
guard is outside the supported subset
function clauses are not in the supported single-interesting-argument form
binary pattern is outside the supported subset
```

Unsupported non-final guards are not a skipped reason in the multi-argument final-catch-all fast path.

## Known limitations

* The checker does not currently run for overloaded specs.
* Unspecced functions usually have dynamic fallback types, which the checker cannot enumerate usefully.
* The checker does not call the full eqwalizer elaborator for arbitrary selector expressions; it has a small conservative `selectorType` helper.
* The checker does not infer predicate-driven result specialization such as `get_value(Key, Map, fun is_binary/1)` returning `binary()` unless the spec says so.
* Finite product-space checking has arity and cell-count limits.
* General binary/bitstring coverage is not implemented.
* Multiple guards, disjunctions, conjunctions, comparisons, and arbitrary guard expressions are not modeled for refinement. They are ignored only in the multi-argument final-catch-all fast path, where the final unguarded clause proves exhaustiveness by itself.
* Simple aliases are supported in selected patterns, but general environment propagation through body matches remains limited.

## Important commits from the session

Not every commit is listed here, but these are the important ones to recognize later:

```text
a1374993d99fe7299ca8bb56d589b7e9b2564a97
Enable exhaustive case checking by default

8de01058122d5e4ca1da25e8e655a05e05d46862
Omit unsupported structured exhaustive diagnostics

d21f0df45cc1f8ffdb5f11560cdfcb8f667d66da
Check single-interesting-argument function clauses

e3c03e0cd011c44723fdd584120da62c06aaa160
Treat unguarded catch-all clauses as exhaustive

abe36c781b52931db4878d3691158baea9f7bfff
Support tuple patterns in exhaustiveness checks

84314c46c3efdd48a78d38fc2827de66ace9279f
Treat atom type as a simple alternative

3d9b0c01bc3527fb25bb67ab2d82fa7a5f53b342
Model empty and non-empty binary coverage

0343caa6eee5644ec4e0a72a43b6d426072a0ff4
Type simple case selector expressions from specs

2da1abbdceab7c43e20287f9b7cf343344b0179c
Remove unreachable selector type case

c6987898252e948e3e41b7884ef4f39548013ebd
Accept tuple selector cases with catch-all

8c926e17692f013b4fcd36593192d4760e897d3c
Accept multi-argument clauses with catch-all

d75b834743c435ccd065bb83a9d318419a0fb52b
Check finite product-space clauses

fa6234fa23ebfced575f0c8d26b596e44c13e731
Allow unsupported guards before final catch-all in exhaustive check
```

Earlier compatibility commits included fixes for:

```text
ELP map key decoding
ELP binary specifier names
ELP converted form variants
ELP type JSON shape
forall as List[Int]
```

## How to test quickly

### Multi-argument function with catch-all and unsupported guard

```erlang
-spec max(integer(), integer()) -> integer().
max(X, Y) when X > Y -> X;
max(X, Y) -> Y.
```

Expected: no exhaustiveness warning.

### Multi-argument function with catch-all

```erlang
-type t(T) :: none | {some, T}.

-spec apply3(t(fun((A) -> B)), t(A)) -> t(B).
apply3({some, Fun}, {some, Value}) ->
    {some, Fun(Value)};
apply3(_, _) ->
    none.
```

Expected: no exhaustiveness warning.

### Function clauses

```erlang
-type abc() :: a | b | c.

-spec f(abc()) -> ok.
f(a) -> ok;
f(b) -> ok.
```

Expected:

```text
Function f/1 does not handle: 'c'
```

### Simple atom union case expression

```erlang
-type abc() :: a | b | c.

-spec atom_case(abc()) -> ok.
atom_case(X) ->
    case X of
        a -> ok;
        b -> ok
    end.
```

Expected:

```text
Case expression does not handle: 'c'
```

### Binary non-empty pattern

```erlang
-spec capitalize_word(binary()) -> binary().
capitalize_word(<<First, Rest/binary>>) ->
    <<First, Rest/binary>>.
```

Expected missing:

```text
<<>>
```

## Suggested next steps

The most useful follow-ups would be:

1. Add a real feature flag plumbed from `eqwalizer.config` through ELP into Scala, probably using an environment variable such as:

   ```text
   EQWALIZER_EXHAUSTIVE_CASE_CHECKING
   ```

2. Reduce skipped-check noise by deciding which skipped diagnostics should be warnings by default and which should perhaps be hidden behind a debug flag.

3. Add tests for:

   * atom union case expressions;
   * function-clause exhaustiveness;
   * single-interesting-argument functions;
   * multi-argument final catch-all functions, including unsupported non-final guards;
   * finite product-space functions;
   * tuple selector cases with final catch-all;
   * tuple tagged unions;
   * `is_record/2`;
   * `is_atom` / `is_integer` / `is_binary` guards;
   * binary empty/non-empty coverage;
   * selector typing from local calls and dynamic function calls.

4. Add product-space guard refinement for simple guards over whole-argument aliases.

5. Consider custom return typing for known predicate-driven helpers, but only carefully. For example, specializing `get_value(..., fun is_binary/1)` to `binary()` is useful, but it is no longer generic spec trust. It is a custom semantic rule.

6. Eventually rename diagnostics from `NonExhaustiveCase` to a more general name, because the same machinery now covers both `case` expressions and function clauses.
