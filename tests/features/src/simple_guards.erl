-module(simple_guards).
-compile([export_all, nowarn_export_all]).
-record(point, {x :: integer()}).

%% @test is_record/2 can refine a whole selected argument
%% @expect-clean
-spec record_guard(#point{} | none) -> ok.
record_guard(X) when is_record(X, point) -> ok;
record_guard(none) -> ok.
%% @end

%% @test is_record/3 remains outside our refinement subset
%% @expect skipped_exhaustiveness_check contains "guard is outside"
-spec record_guard_three(#point{} | none) -> ok.
record_guard_three(X) when is_record(X, point, 2) -> ok;
record_guard_three(none) -> ok.
%% @end

%% @test pattern aliases can supply the selected guard variable
%% @expect-clean
-spec alias_guard(atom()) -> ok.
alias_guard(X = _) when is_atom(X) -> ok.
%% @end

%% @test is_number covers the integer and float alternatives
%% @expect-clean
-spec number_guard(number()) -> ok.
number_guard(X) when is_number(X) -> ok.
%% @end

%% @test is_float covers the separate float alternative
%% @expect-clean
-spec float_guard(float()) -> ok.
float_guard(X) when is_float(X) -> ok.
%% @end

%% @test conjunctions remain outside our custom guard refinement
%% @expect skipped_exhaustiveness_check contains "guard is outside"
-spec conjunction(integer()) -> ok.
conjunction(X) when is_integer(X), X > 0 -> ok.
%% @end
