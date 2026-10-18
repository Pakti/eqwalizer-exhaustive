-module(strict_directives).
-compile([export_all, nowarn_export_all]).

%% @test a source comment suppresses skipped checks for the following function
%% @expect-clean
% eqwalizer_strict:disable_error skipped_exhaustiveness_check
-spec suppressed(term()) -> ok.
suppressed(a) -> ok.
%% @end

%% @test suppression does not leak to a neighboring function
%% @expect skipped_exhaustiveness_check
-spec neighboring(term()) -> ok.
neighboring(a) -> ok.
%% @end

%% @test suppressing skipped checks does not suppress missing cases
%% @expect non_exhaustive_function contains "'b'"
% eqwalizer_strict:disable_error skipped_exhaustiveness_check
-spec still_missing(a | b) -> ok.
still_missing(a) -> ok.
%% @end
