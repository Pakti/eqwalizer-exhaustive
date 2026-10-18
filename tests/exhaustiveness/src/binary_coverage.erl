-module(binary_coverage).
-compile([export_all, nowarn_export_all]).

%% @test a nonempty binary pattern leaves the empty binary uncovered
%% @expect non_exhaustive_function contains "<<>>"
-spec missing_empty(binary()) -> binary().
missing_empty(<<First, Rest/binary>>) -> <<First, Rest/binary>>.
%% @end

%% @test empty plus nonempty patterns cover the narrow binary model
%% @expect-clean
-spec both_parts(binary()) -> ok.
both_parts(<<>>) -> ok;
both_parts(<<_, _/binary>>) -> ok.
%% @end

%% @test exact byte lengths are outside the narrow binary model
%% @expect skipped_exhaustiveness_check contains "binary pattern"
-spec exact_byte(binary()) -> ok.
exact_byte(<<_:8>>) -> ok.
%% @end
