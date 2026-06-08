# Project status: exhaustive case checking

Branch:

```text
feature/exhaustive-case-checking
```

Last updated: 2026-06-08.

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
* multi-argument functions with a final catch-all clause;
* finite product-space checking for multi-argument functions without requiring a final catch-all.

## Main files changed

The central files are:

```text
eqwalizer/src/main/scala/com/whatsapp/eqwalizer/tc/ExhaustiveCase.scala
eqwalizer/src/main/scala/com/whatsapp/eqwalizer/tc/package.scala
eqwalizer/src/main/scala/com/whatsapp/eqwalizer/tc/TcDiagnostics.scala
eqwalizer/src/main/scala/com/whatsapp/eqwalizer/util/ELPDiagnostics.scala
eqwalizer/src/main/scala/com/whatsapp/eqwalizer/ast/Types.scala
eqwalizer/src/main/scala/com/whatsapp/eqwalizer/ast/Exprs.scala
eqwalizer/src/main/scala/com/whatsapp/eqwalizer/ast/Forms.scala
eqwalizer/src/main/scala/com/whatsapp/eqwalizer/ast/Specifier.scala
eqwalizer/src/main/scala/com/whatsapp/eqwalizer/ast/TypeVars.scala
eqwalizer/src/main/scala/com/whatsapp/eqwalizer/tc/Subtype.scala
eqwalizer/src/main/scala/com/whatsapp/eqwalizer/tc/TypeMismatch.scala
```

The most important implementation file is:

```text
eqwalizer/src/main/scala/com/whatsapp/eqwalizer/tc/ExhaustiveCase.scala
```

## AST compatibility work for ELP 3a65019

The fork originally expected a newer or different AST JSON shape than ELP `3a65019` emits. We adjusted Scala-side decoding to match the older format.

### Type variable encoding

ELP `3a65019` encodes `FunType.forall` as a list of type variable ids, not as an integer count.

We changed the Scala model from the newer `forall: Int` assumption back to:

```scala
forall: List[Int]
```

and fixed downstream code that had been comparing `forall` directly with integers.

Affected areas included:

```text
Types.scala
TypeVars.scala
Subtype.scala
TypeMismatch.scala
```

### Type declarations

ELP `3a65019` serializes type declarations as JSON objects such as:

```json
{"id":"t/1","params":[...],"body":...}
```

rather than array-like forms. The Scala decoder was adjusted accordingly.

### Map type props

ELP serializes `MapType.props` as a JSON object whose keys are strings, for example:

```json
"props": {
  "auto_shutdown": {
    "req": false,
    "tp": ...
  }
}
```

The Scala side had been configured with `.withMapAsArray(true)`, which expected map entries as arrays. That caused runtime JSON parse errors like:

```text
expected '[' or null
```

We fixed this by:

* removing `.withMapAsArray(true)` from the relevant type codec;
* adding a `JsonKeyCodec[Key]`;
* adding `Key.fromString`;
* supporting atom keys and tuple keys such as `{foo, bar}`.

### Binary specifier enum names

ELP `3a65019` emits binary segment specifiers such as:

```text
UnsignedIntegerSpecifier
SignedIntegerSpecifier
BinarySpecifier
BytesSpecifier
BitstringSpecifier
BitsSpecifier
Utf8Specifier
Utf16Specifier
Utf32Specifier
```

The Scala package-level `Specifier` enum was updated to use those names. A mistake was briefly made by adding a nested `Exprs.Specifier`; that caused type mismatch errors in `Elab.scala`. We removed the duplicate and kept the package-level type:

```scala
com.whatsapp.eqwalizer.ast.Specifier
```

### External form variants

ELP `3a65019` emits more converted form variants than the Scala model was accepting. We added support for the relevant ignored or pass-through external forms, including:

```text
CompileExportAll
Export
Import
ExportType
TypingAttribute
ExternalTypeDecl
ExternalFunSpec
ExternalCallback
ExternalOptionalCallbacks
ExternalRecDecl
ExternalRecField
```

The point here was not to typecheck all these forms in Scala, but to avoid JSON decoding failures when ELP sends them.

## Diagnostic JSON compatibility

The first version emitted a structured diagnostic containing a new Scala error variant:

```text
NonExhaustiveCase
```

ELP `3a65019` has a fixed Rust enum for structured diagnostics and did not know that variant. This made ELP fail while parsing eqwalizer's successful JSON response.

We changed `ELPDiagnostics.scala` so new exhaustiveness diagnostics are sent as normal diagnostics without unsupported structured payloads.

In practice:

* `message`, `range`, `uri`, `code`, `explanation`, and `expression` remain;
* `diagnostic` is omitted for unsupported new exhaustiveness diagnostics.

This fixed errors where ELP printed raw escaped JSON like:

```text
parsing for eqwalizer: "{\"Done\": ... }"
```

## Checker architecture

The checker lives in `ExhaustiveCase.scala`.

It is invoked from the normal pipeline after ordinary function checking:

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

Unsupported code should not produce false missing-case diagnostics; it should produce a skipped-check diagnostic.

## Case expression support

Initially, case checking only worked for:

```erlang
case X of
    ...
end
```

where `X` was a variable from the function arguments.

Now it also tries to type simple selector expressions from specs.

Supported selector forms include:

```text
Var(V)
Tuple(Elems), when all element types are known
LocalCall(F, Args)
RemoteCall(M:F, Args)
DynCall(FunExpr, Args), when FunExpr has a known FunType
```

### Spec-based selector calls

This now handles selectors such as:

```erlang
case IsExpectedType(X) of
    false -> ...;
    true -> ...
end
```

where `IsExpectedType` has a type such as:

```erlang
fun((any()) -> boolean())
```

It also handles local or remote function calls when the function spec has a useful return type, for example:

```erlang
case get_option(Key, Map, fun is_binary/1) of
    null -> null;
    X when is_binary(X) -> X;
    _ -> error(badarg)
end
```

Important limitation: selector typing currently trusts the declared result type. It does not specialize a polymorphic or callback-driven function result based on the actual arguments. If `get_value/3` is specced as returning `any()`, then:

```erlang
case get_value(Key, Map, fun is_binary/1) of
    X when is_binary(X) -> X
end
```

still sees the selector as `any()`, not `binary()`. That will usually skip because `any()` is outside the supported finite subset.

### Tuple selector expressions

The checker now supports tuple selector expressions when every tuple element has a known type:

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

This is accepted through a conservative fast path:

* the selector must be a tuple expression;
* the final clause must be an unguarded catch-all;
* every non-final clause must have exactly one tuple pattern;
* the tuple pattern arity must match the selector tuple arity;
* non-final tuple patterns must stay inside the existing simple-pattern subset.

This feature is intentionally separate from full product-space reasoning for `case`; it accepts the common idiom where a final catch-all handles the remaining combinations.

## Function-clause exhaustiveness

The checker supports function-clause exhaustiveness using several increasingly general paths.

### Single-argument finite unions

The basic supported form is:

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

The checker still supports a conservative multi-argument mode where exactly one argument position is interesting and all other argument positions are variables or wildcards in every clause.

Supported:

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
* non-final clauses using supported simple patterns;
* non-final guards, if present, staying inside the existing supported guard subset.

### Finite product-space exhaustiveness

Finite product-space checking is now implemented for multi-argument function clauses.

This handles idiomatic functional-style Erlang code without requiring a final catch-all. For example:

```erlang
-type t(E, A) :: {left, E} | {right, A}.

-spec apply(t(E, fun((A) -> B)), t(E, A)) -> t(E, B).
apply({right, Fn}, {right, Value}) ->
    {right, Fn(Value)};
apply({left, E}, _) ->
    {left, E};
apply(_, {left, E}) ->
    {left, E}.
```

The product space is finite:

```text
({left, E}, {left, E})
({left, E}, {right, A})
({right, fun((A) -> B)}, {left, E})
({right, fun((A) -> B)}, {right, A})
```

The clauses subtract those cells as follows:

```text
apply({right, Fn}, {right, Value}) -> covers ({right, _}, {right, _})
apply({left, E}, _)                -> covers ({left, _}, _)
apply(_, {left, E})                -> covers (_, {left, _})
```

All cells are covered, so the function is exhaustive.

The product-space checker has explicit caps:

```scala
private val maxProductArity = 4
private val maxProductCells = 128
```

If a product has too many dimensions or cells, the checker skips with a clear reason rather than exploding.

Product-space checking currently supports:

* finite alternatives produced by `simpleAlternatives`;
* simple patterns covered by `simplePatternCover`;
* clause-by-clause subtraction of covered product cells;
* readable missing-cell diagnostics such as:

  ```text
  ({some, A}, none)
  ```

Product-space guard refinement is not implemented yet. Guarded product-space clauses are still a follow-up area.

## Catch-all handling

We fixed a false positive where functions like this warned:

```erlang
-spec left(E) -> t(E, none).
left(X) -> {left, X}.
```

The problem was that the checker tried to enumerate generic type variable `E` before noticing that `X` is an unguarded catch-all.

Now the checker treats unguarded catch-all patterns as exhaustive before trying to enumerate the scrutinee type.

This applies to:

```erlang
f(X) -> ...
f(_) -> ...
```

and:

```erlang
case X of
    _ -> ...
end
```

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

### Tuple patterns

Tuple patterns and tuple alternatives are supported.

This handles tagged tuple unions such as:

```erlang
-type t(E, A) :: {left, E} | {right, A}.

-spec is_left(t(any(), any())) -> boolean().
is_left({left, _}) -> true;
is_left({right, _}) -> false.
```

The checker understands that `{left, _}` covers the `{left, E}` alternative and `{right, _}` covers the `{right, A}` alternative.

If the second clause is missing:

```erlang
is_left({left, _}) -> true.
```

it should report the `{right, A}` alternative as uncovered.

### Record guards

Supported guard form:

```erlang
R when is_record(R, foo) -> ...
```

This maps to:

```scala
RecordType("foo")(module)
```

We intentionally did not implement `is_record/3` yet.

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

We fixed a bug where `AtomType` was not included as a simple enumerable alternative. Before that, this incorrectly skipped:

```erlang
-type guarded_t() :: atom() | integer() | binary().

-spec guard_case(guarded_t()) -> ok.
guard_case(X) ->
    case X of
        Y when is_atom(Y) -> ok;
        Y when is_integer(Y) -> ok
    end.
```

After the fix, the checker reports the real missing alternative, `binary()`.

## Binary-size coverage

We added a deliberately narrow model for `binary()` exhaustiveness.

The checker splits `binary()` into:

```text
<<>>
<<_, _/binary>>
```

Meaning:

```text
empty binary
non-empty binary
```

Supported binary patterns:

```erlang
<<>>
```

covers only the empty binary.

```erlang
<<_/binary>>
```

covers all binaries.

```erlang
<<First, Rest/binary>>
```

covers non-empty binaries.

So this warns:

```erlang
-spec capitalize_word(binary()) -> binary().
capitalize_word(<<First, Rest/binary>>) ->
    UpperFirst = iolist_to_binary(string:uppercase(<<First>>)),
    <<UpperFirst/binary, Rest/binary>>.
```

Expected missing case:

```text
<<>>
```

And this should be exhaustive:

```erlang
-spec capitalize_word(binary()) -> binary().
capitalize_word(<<>>) ->
    <<>>;
capitalize_word(<<First, Rest/binary>>) ->
    UpperFirst = iolist_to_binary(string:uppercase(<<First>>)),
    <<UpperFirst/binary, Rest/binary>>.
```

Limitations: this is not a general Erlang bit syntax model. It does not yet support variable sizes, non-byte-aligned bitstrings, UTF segments, arbitrary bitstring specs, or exact byte lengths beyond empty versus non-empty.

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

Some skipped checks are genuine checker limitations. Others indicate that the function spec is wider than the code handles.

Example:

```erlang
-spec atom_to_json_key(term()) -> binary().
atom_to_json_key(Atom) when is_atom(Atom) ->
    ...
```

This warns or skips because `term()` is too broad to enumerate and the function only accepts atoms. The better spec is:

```erlang
-spec atom_to_json_key(atom()) -> binary().
```

or the function should add a catch-all clause.

## Known limitations

### Overloaded specs

The checker does not currently run for overloaded specs.

In `Pipeline.scala`, `checkOverloadedFun` still only runs ordinary checking and does not call:

```scala
ctx.exhaustiveCase.checkFun(...)
```

This is a known gap.

### Unspecced functions

Unspecced functions get a dynamic fallback type:

```scala
FunType(... DynamicType ...)
```

The exhaustiveness checker usually cannot do anything useful with `dynamic()`.

### Full expression typing

The checker does not call the full eqwalizer elaborator for arbitrary selector expressions. It has a small conservative `selectorType` helper.

It handles common spec-based selectors, tuple selectors, and dynamic function calls, but not every expression eqwalizer can type.

### Result specialization from predicate arguments

The checker does not infer that:

```erlang
get_value(Key, Map, fun is_binary/1)
```

returns `binary()` unless the spec says so.

If `get_value/3` is specced as returning `any()`, the checker treats it as `any()` and cannot enumerate it.

This is why this warning is expected:

```erlang
-spec get_binary(binary(), map()) -> binary().
get_binary(Key, Map) ->
    case get_value(Key, Map, fun is_binary/1) of
        X when is_binary(X) -> X
    end.
```

If `get_value/3` returns `any()`, the case is not exhaustive for all possible returns. Add a catch-all or give `get_value/3` a more precise spec if possible.

### Product-space limits

Finite product-space checking is implemented, but intentionally limited:

* max arity is currently 4;
* max product cells is currently 128;
* each argument type must expand through `simpleAlternatives`;
* broad types such as `any()`, `term()`, `dynamic()`, unconstrained `atom()`, and unconstrained `integer()` are not finite product dimensions;
* guarded product-space clauses are not refined yet.

### General binary/bitstring support

Only a tiny `binary()` model exists:

```text
empty
non-empty
```

No full bit syntax coverage yet.

### Complex guards

Multiple guards, disjunctions, conjunctions, comparisons, and arbitrary guard expressions are not modeled.

### Pattern aliases and match propagation

Simple aliases are supported in selected patterns, but general environment propagation through body matches remains limited.

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

### Tagged tuples

```erlang
-type t(E, A) :: {left, E} | {right, A}.

-spec is_left(t(any(), any())) -> boolean().
is_left({left, _}) -> true.
```

Expected missing:

```text
{right, ...}
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

### Boolean callback result

```erlang
-spec f(fun((any()) -> boolean()), any()) -> any().
f(Pred, X) ->
    case Pred(X) of
        true -> X
    end.
```

Expected missing:

```text
false
```

### Tuple selector with catch-all

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

### Finite product-space function without catch-all

```erlang
-type t(E, A) :: {left, E} | {right, A}.

-spec apply(t(E, fun((A) -> B)), t(E, A)) -> t(E, B).
apply({right, Fn}, {right, Value}) ->
    {right, Fn(Value)};
apply({left, E}, _) ->
    {left, E};
apply(_, {left, E}) ->
    {left, E}.
```

Expected: no exhaustiveness warning.

If the last clause is removed, the checker should report the remaining cell involving a right function and a left value.

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
   * multi-argument final catch-all functions;
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
