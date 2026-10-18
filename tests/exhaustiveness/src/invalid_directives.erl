-module(invalid_directives).
-compile([export_all, nowarn_export_all]).

%% @test strict directives are source comments rather than attributes
%% @expect invalid_eqwalizer_strict_attribute contains "must be comments"
-eqwalizer_strict({private_constructor, box, owner}).
-spec attribute_syntax() -> ok.
attribute_syntax() -> ok.
%% @end

%% @test only the documented skipped-check error can be suppressed
%% @expect invalid_eqwalizer_strict_attribute contains "unsupported error"
% eqwalizer_strict:disable_error non_exhaustive_function
-spec unsupported_suppression() -> ok.
unsupported_suppression() -> ok.
%% @end

%% @test suppression must immediately precede a spec
%% @expect invalid_eqwalizer_strict_attribute contains "immediately followed"
% eqwalizer_strict:disable_error skipped_exhaustiveness_check

-spec separated_spec() -> ok.
separated_spec() -> ok.
%% @end

