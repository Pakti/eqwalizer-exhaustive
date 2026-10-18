-module(box_owner).
-compile([export_all, nowarn_export_all]).
-include("private_box.hrl").

%% @test the owner may construct a private record declared in a header
%% @expect-clean
-spec create(term()) -> #box{}.
create(Value) -> #box{value = Value}.
%% @end

%% @test the owner may update its private record
%% @expect-clean
-spec update(#box{}, term()) -> #box{}.
update(Box, Value) -> Box#box{value = Value}.
%% @end
