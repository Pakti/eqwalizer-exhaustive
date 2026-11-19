%% Integration regressions for the OTP spec overrides imported in 2026-11-16.
%% These exercise the real ELP type database as well as the Scala checker.
-module(release_compatibility).
-compile([export_all, nowarn_export_all]).

%% @test append_list preserves an ordered dictionary of lists
%% @expect-clean
-spec append_list(orddict:orddict(atom(), [integer()])) ->
    orddict:orddict(atom(), [integer()]).
append_list(Dict) -> orddict:append_list(key, [1, 2], Dict).
%% @end

%% @test append_list does not turn stored lists into scalar values
%% @expect incompatible_types
-spec append_list_wrong_result(orddict:orddict(atom(), [integer()])) ->
    orddict:orddict(atom(), integer()).
append_list_wrong_result(Dict) -> orddict:append_list(key, [1, 2], Dict).
%% @end

%% @test regular expression overrides accept the OTP compiled-pattern type
%% @expect-clean
-spec compiled_pattern(binary(), re:mp()) -> term().
compiled_pattern(Subject, Pattern) -> re:run(Subject, Pattern).
%% @end

%% @test regular expression options follow the installed OTP contract
%% @expect-clean
-spec pattern_options(binary(), re:mp(), re:options()) -> term().
pattern_options(Subject, Pattern, Options) -> re:run(Subject, Pattern, Options).
%% @end
