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

- Primitives: `String`, `Int` (`format: int32`), `Long` (`format: int64`), `Double`, `Float`, `Boolean` (and `OffsetDateTime` as a `date-time` string)
- `Option` (nullable / not required)
- `List` / `Seq`
- Nested case classes
- Sealed traits and Scala 3 enums

These are provided as `given ToSchema[...]` instances in the `ToSchema` companion, so they are part of the implicit search scope.

### Custom instances

Provide your own `given ToSchema[T]` to control how a type maps to `Schema`. Derivation honors it wherever `T` appears — as a top-level type, a nested field, or inside `Option` / `List` / `Seq`:

```scala mdoc:silent
import io.github.serhiip.constellations.common.Schema
import io.github.serhiip.constellations.schema.ToSchema

final case class Temperature(celsius: Double)
object Temperature:
  given ToSchema[Temperature] =
    ToSchema.instance(Schema.number(description = Some("temperature in celsius")))

final case class Reading(id: String, temperature: Temperature, history: List[Temperature])

// `temperature` and each item of `history` use the custom Temperature schema
val readingSchema: Schema = Schema.derived[Reading]
```

### `@llmHint`

Overrides metadata and constraints on fields (description, min/max, pattern, enum values, etc.). Used by **`Schema.derived` / `ToSchema`**. Type-level docstrings are used as a fallback for `description` when `@llmHint` does not set one (`@llmHint` wins). Prefer `@llmHint(description = ...)` on fields — case-class parameter docstrings are not reliably available via Scala 3's `Symbol.docstring`.

### ToolDispatcher vs Schema.derived

| Feature                     | ToolDispatcher param schemas                                  | `Schema.derived` / `ToSchema`                |
| --------------------------- | ------------------------------------------------------------- | -------------------------------------------- |
| Type → schema               | Via `ToSchema` (`summonInline`)                               | Explicit `Schema.derived[A]` / `ToSchema[A]` |
| Nested descriptions         | `@llmHint` + type-level docstrings                            | `@llmHint` + type-level docstrings           |
| Method / param descriptions | Method / parameter **docstrings** (declaration surface)       | N/A                                          |
| Extensibility               | User `given ToSchema[Custom]` (e.g. `ToSchema.instance(...)`) | Same                                         |

## Related

- [Encoding & decoding](encoding-decoding.md) — convert `Value` ↔ Scala types
- [ToolDispatcher](tool-dispatcher.md) — uses schemas in `FunctionDeclaration`
- [Messages](messages.md) — conversation types that carry `FunctionCall` / `FunctionResponse`
