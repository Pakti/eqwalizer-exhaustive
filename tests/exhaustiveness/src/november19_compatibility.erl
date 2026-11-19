%% These examples use OTP 27-compatible syntax while exercising the new clause
%% wrappers, comprehension template lists and sharpened November 19 overrides.
-module(november19_compatibility).
-compile([export_all, nowarn_export_all]).

%% @test list comprehension templates still receive custom case checking
%% @expect non_exhaustive_case contains "'c'"
-spec list_template(a | b | c) -> [ok].
list_template(X) -> [case X of a -> ok; b -> ok end || _ <- [unit]].
%% @end

%% @test map comprehension keys still receive custom case checking
%% @expect non_exhaustive_case contains "'c'"
-spec map_template(a | b | c) -> #{ok => ok}.
map_template(X) -> #{case X of a -> ok; b -> ok end => ok || _ <- [unit]}.
%% @end

%% @test ordered dictionary take preserves its value type rather than dynamic
%% @expect incompatible_types
-spec wrong_take(orddict:orddict(atom(), integer())) ->
    {binary(), orddict:orddict(atom(), integer())} | error.
wrong_take(Dict) -> orddict:take(key, Dict).
%% @end

%% @test safe_relative_path cannot promise an arbitrary binary result
%% @expect incompatible_types
-spec wrong_relative_path(file:name_all(), file:name_all()) -> binary().
wrong_relative_path(Path, Cwd) -> filelib:safe_relative_path(Path, Cwd).
%% @end
