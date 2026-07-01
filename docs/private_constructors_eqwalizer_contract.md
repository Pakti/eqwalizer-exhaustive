# eqWAlizer private constructors - local attribute contract

This note describes the contract expected by the Scala-side implementation in
eqWAlizer.

## Attribute syntax

The supported forms are:

```erlang
-eqwalizer({private_constructor, RecordName}).
-eqwalizer({private_constructor, RecordName, OwnerModule}).
```

When `OwnerModule` is omitted, it defaults to `RecordName`.

The idiomatic owner-side form is:

```erlang
-eqwalizer({private_constructor, my_type, ?MODULE}).
```

After preprocessing/conversion, `?MODULE` must be resolved to the current module
name before it reaches Scala.

The Scala internal forms are:

```scala
EqwalizerPrivateConstructor("my_type")(pos)
EqwalizerPrivateConstructor("my_type", Some("my_api"))(pos)
```

## Semantics

The attribute is local to the module being checked.

If the current module contains either:

```erlang
-eqwalizer({private_constructor, my_type}).
```

or:

```erlang
-eqwalizer({private_constructor, my_type, my_api}).
```

then:

- `my_api` may construct and update `#my_type{}`.
- Any other module containing the same attribute may pattern match and select
  fields, but may not construct or update `#my_type{}`.

Forbidden outside `my_api`:

```erlang
#my_type{...}
R#my_type{...}
```

Allowed outside `my_api`:

```erlang
#my_type{x = X} = Value
Value#my_type.x
```

## Validation expectations

The converter/parser side should reject:

- the attribute in `.hrl` files;
- a non-atom `RecordName`;
- a non-atom `OwnerModule`, except for `?MODULE` before expansion;
- any form other than `{private_constructor, RecordName}` or `{private_constructor, RecordName, OwnerModule}`;
- duplicate local declarations for the same record with different owners.

The Scala patch also emits `invalid_private_constructor` if duplicate owners for
the same record reach the type-checker.

## Why this avoids `RecDecl` propagation

The earlier design propagated:

```scala
RecDecl.privateConstructorOwner: Option[String]
```

That requires enriching serialized record declarations. This local-attribute
design avoids changing `RecDecl` and avoids changing the IPC record-declaration
format.

The tradeoff is that client modules must also contain the attribute if they are
to enforce the private-constructor rule locally:

```erlang
-eqwalizer({private_constructor, my_type}).
```

or, when the owner module does not have the same name as the record:

```erlang
-eqwalizer({private_constructor, my_type, my_api}).
```
