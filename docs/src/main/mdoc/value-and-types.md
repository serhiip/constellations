# Value and Types

Constellations provides type-safe data structures for AI interactions.

## Value

The `Value` enum represents JSON-like values with type safety:

```scala
enum Value:
  case NullValue
  case NumberValue(value: Double)
  case StringValue(value: String)
  case BoolValue(value: Boolean)
  case StructValue(value: Struct)
  case ListValue(value: List[Value])
```

## Struct

`Struct` is a container for structured data:

```scala
case class Struct(fields: Map[String, Value])
```

## Schema

`Schema` represents JSON Schema for type definitions:

```scala
case class Schema(
  tpe: SchemaType,
  format: Option[String],
  description: Option[String],
  nullable: Option[Boolean],
  // ... more fields
)

enum SchemaType:
  case String, Number, Integer, Boolean, Array, Object
```

## Factory Methods

Create values easily:

```scala
import io.github.serhiip.constellations.common.Value
import io.github.serhiip.constellations.common.Struct

val stringVal = Value.string("hello")
val numberVal = Value.number(42)
val boolVal = Value.bool(true)
val structVal = Value.struct(
  "name" -> Value.string("John"),
  "age" -> Value.number(30)
)
val listVal = Value.list(Value.string("a"), Value.string("b"))
```

## Coming Soon

Detailed documentation on automatic encoding/decoding and schema generation.
