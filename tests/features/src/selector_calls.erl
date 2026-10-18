-module(selector_calls).
-compile([export_all, nowarn_export_all]).

%% @test a helper is checked against its declared result type
%% @expect-clean
-spec values() -> a | b | c.
values() -> a.
%% @end

%% @test local call selectors use declared results, not only this body value
%% @expect non_exhaustive_case contains "'c'"
-spec local_selector() -> ok.
local_selector() ->
    case values() of a -> ok; b -> ok end.
%% @end

%% @test remote call selectors use declared results
%% @expect non_exhaustive_case contains "'c'"
-spec remote_selector() -> ok.
remote_selector() ->
    case ?MODULE:values() of a -> ok; b -> ok end.
%% @end

%% @test dynamic call selectors use the function argument spec
%% @expect non_exhaustive_case contains "'c'"
-spec dynamic_selector(fun(() -> a | b | c)) -> ok.
dynamic_selector(Fun) ->
    case Fun() of a -> ok; b -> ok end.
%% @end

%% @test an unknown selector type skips instead of inventing coverage
%% @expect skipped_exhaustiveness_check contains "selector type is not known"
-spec unknown_selector() -> ok.
unknown_selector() ->
    case (fun() -> a end)() of a -> ok end.
%% @end
