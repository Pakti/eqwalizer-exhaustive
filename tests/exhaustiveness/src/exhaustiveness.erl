%% Executable documentation for our custom coverage pass. Each @test region is
%% checked independently; every unexpected diagnostic fails the suite.
-module(exhaustiveness).
-compile([export_all, nowarn_export_all]).

%% @test a function must cover each atom alternative
%% @expect non_exhaustive_function contains "'c'"
-spec missing_atom(a | b | c) -> ok.
missing_atom(a) -> ok;
missing_atom(b) -> ok.
%% @end

%% @test a complete atom function is quiet
%% @expect-clean
-spec complete_atom(a | b | c) -> ok.
complete_atom(a) -> ok;
complete_atom(b) -> ok;
complete_atom(c) -> ok.
%% @end

%% @test case expressions also report the missing alternative
%% @expect non_exhaustive_case contains "'c'"
-spec missing_case(a | b | c) -> ok.
missing_case(X) ->
    case X of a -> ok; b -> ok end.
%% @end

%% @test one interesting argument can coexist with a broad unused argument
%% @expect non_exhaustive_function contains "'c'"
-spec interesting_argument(a | b | c, term()) -> ok.
interesting_argument(a, _) -> ok;
interesting_argument(b, _) -> ok.
%% @end

%% @test unsupported earlier guards do not defeat a final catch-all
%% @expect-clean
-spec maximum(integer(), integer()) -> integer().
maximum(X, Y) when X > Y -> X;
maximum(X, Y) -> Y.
%% @end

%% @test finite products expose missing argument combinations
%% @expect non_exhaustive_function contains "('b', 'b')"
-spec missing_product(a | b, a | b) -> ok.
missing_product(a, _) -> ok;
missing_product(b, a) -> ok.
%% @end

%% @test a complete product needs no final catch-all
%% @expect-clean
-spec complete_product(a | b, a | b) -> ok.
complete_product(a, _) -> ok;
complete_product(b, _) -> ok.
%% @end

%% @test tagged tuples cover their declared alternatives
%% @expect-clean
-spec tagged_tuple({left, term()} | {right, term()}) -> ok.
tagged_tuple({left, _}) -> ok;
tagged_tuple({right, _}) -> ok.
%% @end

%% @test tuple selector expressions accept a final catch-all
%% @expect-clean
-spec tuple_selector(a | b, a | b) -> ok.
tuple_selector(X, Y) ->
    case {X, Y} of {a, b} -> ok; _ -> ok end.
%% @end

%% @test guards distinguish integers from floats
%% @expect non_exhaustive_function contains "float()"
-spec integer_guard(number()) -> ok.
integer_guard(X) when is_integer(X) -> ok.
%% @end

%% @test supported guards can jointly cover a flat union
%% @expect-clean
-spec simple_guards(atom() | binary()) -> ok.
simple_guards(X) when is_atom(X) -> ok;
simple_guards(X) when is_binary(X) -> ok.
%% @end

%% @test broad dynamic input is explicitly skipped
%% @expect skipped_exhaustiveness_check
-spec skipped_dynamic(term()) -> ok.
skipped_dynamic(a) -> ok.
%% @end

%% @test product-space guard refinement is not implemented
%% @expect skipped_exhaustiveness_check contains "product-space clause"
-spec guarded_product(a | b, a | b) -> ok.
guarded_product(X, a) when is_atom(X) -> ok.
%% @end

%% @test product arity is capped at four
%% @expect skipped_exhaustiveness_check contains "limit 4"
-spec arity_cap(a | b, a | b, a | b, a | b, a | b) -> ok.
arity_cap(a, a, a, a, a) -> ok.
%% @end

%% @test numeric literals currently use a coarse coverage approximation
%% Known limitation: no warning does NOT prove this numeric match exhaustive.
%% This characterizes current behavior, not a desired soundness guarantee.
%% @expect-clean
-spec coarse_numeric(integer()) -> ok.
coarse_numeric(1) -> ok.
%% @end

%% @test product cells are capped at 128
%% @expect skipped_exhaustiveness_check contains "above limit 128"
-spec cell_cap(a | b | c | d, a | b | c | d, a | b | c | d, a | b | c | d) -> ok.
cell_cap(a, a, a, a) -> ok.
%% @end

%% @test union expansion has an immediate-member limit of ten
%% @expect skipped_exhaustiveness_check contains "supported flat-union subset"
-spec union_cap(a | b | c | d | e | f | g | h | i | j | k) -> ok.
union_cap(a) -> ok.
%% @end

-type option(T) :: none | {some, T}.

%% @test generic multi-argument functions accept a final catch-all
%% @expect-clean
-spec apply_option(option(fun((A) -> B)), option(A)) -> option(B).
apply_option({some, Fun}, {some, Value}) -> {some, Fun(Value)};
apply_option(_, _) -> none.
%% @end

%% @test repeated-variable equality is a current coverage limitation
%% Known limitation: this is not actually exhaustive for unequal inputs.
%% @expect-clean
-spec repeated_variable(a | b, a | b) -> ok.
repeated_variable(X, X) -> ok.
%% @end
