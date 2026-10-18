-module(box_client).
-compile([export_all, nowarn_export_all]).
-include("private_box.hrl").

%% @test a client cannot construct a private record
%% @expect private_constructor_violation contains "box_owner"
-spec create(term()) -> #box{}.
create(Value) -> #box{value = Value}.
%% @end

%% @test a client cannot update a private record
%% @expect private_constructor_violation contains "box_owner"
-spec update(#box{}, term()) -> #box{}.
update(Box, Value) -> Box#box{value = Value}.
%% @end

%% @test a client may pattern match a private record
%% Field patterns are allowed by private constructors, but the coverage pass
%% cannot refine them. Expect its skip and no private-constructor violation.
%% @expect skipped_exhaustiveness_check contains "pattern is outside"
-spec match(#box{}) -> term().
match(#box{value = Value}) -> Value.
%% @end

%% @test a client may read a field of a private record
%% @expect-clean
-spec select(#box{}) -> term().
select(Box) -> Box#box.value.
%% @end
