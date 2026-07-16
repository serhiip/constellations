# Values and schemas

Shared data model in **`constellations-common`**: JSON-like values for tool args/results, and JSON Schema for declarations and structured output.

## Value and Struct

```scala mdoc:silent
import io.github.serhiip.constellations.common.*

val stringVal = Value.string("hello")
val numberVal = Value.number(42)
val boolVal = Value.bool(true)
val listVal = Value.list(Value.string("a"), Value.string("b"))
val structVal = Value.struct(
  Struct(
    "name" -> Value.string("Ada"),
    "age" -> Value.number(36)
  )
)

val empty = Struct.empty
val fromPairs = Struct("ok" -> Value.bool(true))
```

`Value` cases: `NullValue`, `NumberValue`, `StringValue`, `BoolValue`, `StructValue`, `ListValue`.

## Manual Schema builders

```scala mdoc:silent
val personSchema: Schema = Schema.obj(
  description = Some("A person"),
  properties = Map(
    "name" -> Schema.string(description = Some("Full name")),
    "age" -> Schema.integer(minimum = Some(0))
  ),
  required = List("name", "age")
)
```

Helpers: `Schema.string`, `.number`, `.integer`, `.boolean`, `.array`, `.obj`.

## Schema.derived and ToSchema

Derive JSON Schema from case classes and sealed hierarchies:

```scala mdoc:silent
import io.github.serhiip.constellations.common.{Schema, llmHint}
import io.github.serhiip.constellations.schema.ToSchema

final case class Person(
    @llmHint(description = Some("Full name"))
    name: String,
    age: Int
)

val derived: Schema = Schema.derived[Person]
val viaTypeclass: Schema = ToSchema[Person].schema
```

Supported:

- Primitives: `String`, `Int`, `Long`, `Double`, `Float`, `Boolean`
- `Option` (nullable / not required)
- `List` / `Seq`
- Nested case classes
- Sealed traits and Scala 3 enums

### `@llmHint`

Overrides metadata and constraints on fields (description, min/max, pattern, enum values, etc.). Used by **`Schema.derived` / `ToSchema`**.

### ToolDispatcher vs Schema.derived

| Feature | ToolDispatcher param schemas | `Schema.derived` |
|---------|------------------------------|------------------|
| Descriptions | Method / parameter **docstrings** | `@llmHint` + docstrings |
| Trigger | Macros in `generate` / `to` | Explicit `Schema.derived[A]` |

## Related

- [Encoding & decoding](encoding-decoding.md) — convert `Value` ↔ Scala types
- [ToolDispatcher](tool-dispatcher.md) — uses schemas in `FunctionDeclaration`
- [Messages](messages.md) — conversation types that carry `FunctionCall` / `FunctionResponse`
