# Encoding and decoding

Tool dispatch converts between LLM JSON-like values (`Value` / `Struct`) and Scala types. This page covers the codecs used by [`ToolDispatcher`](tool-dispatcher.md).

## Inbound: `Decoder`

`Decoder[P, A]` turns a payload `P` (`Value` or `Struct`) into `A`, accumulating errors as `ValidatedNec[Decoder.Error, A]`.

```scala mdoc:silent
import cats.data.Validated
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.dispatcher.Decoder

val decoded: Validated[cats.data.NonEmptyChain[Decoder.Error], Int] =
  Decoder[Value, Int].decode(Value.number(42), "a")
```

Built-in support includes primitives, `Option`, `List`, `Map[String, _]`, case classes (Mirror derivation), and sealed traits / enums.

### Sum types

Derived sum decoders expect a discriminator field `_type` with the case name:

```scala mdoc:silent
enum Shape:
  case Circle(radius: Double)
  case Rect(width: Double, height: Double)

val circleValue = Value.struct(
  Struct(
    "_type" -> Value.string("Circle"),
    "radius" -> Value.number(1.5)
  )
)

val shape = Decoder[Value, Shape].decode(circleValue, "shape")
```

## Outbound: encoders

When a tool method returns `F[A]`, the macro summons `ResultEncoder[A]`:

```
A  →  ValueEncoder[A]  →  StructEncoder[A]  →  ResultEncoder[A]  →  ToolDispatcher.Result
```

- **`ValueEncoder[A]`** — Scala value → `Value`
- **`StructEncoder[A]`** — if the value is already a struct, use it; otherwise wrap as `Struct("value" → …)`
- **`ResultEncoder[A]`** — `encode(call, value)` builds `ToolDispatcher.Result.Response(FunctionResponse(call, …))`

So a method returning `F[Int]` yields a response like `{"value": 8.0}`.

### Case-class returns

```scala mdoc:silent
import io.github.serhiip.constellations.dispatcher.ValueEncoder

final case class Weather(temp: Double, city: String) derives ValueEncoder

val encoded: Value = ValueEncoder[Weather].encode(Weather(21.5, "Kyiv"))
```

### Special results

| Return type | Encoding |
|-------------|----------|
| Ordinary `A` with `ValueEncoder` | `Result.Response(FunctionResponse(call, …))` with wrapped/struct body |
| `FunctionResponse` | `Result.Response` keeping the returned body, forcing the dispatch `call` |
| `ToolDispatcher.Result` | `HumanInTheLoop` as-is; `Response` gets the dispatch `call` |

## Circe `Codecs`

`io.github.serhiip.constellations.common.Codecs` provides Circe `Encoder`/`Decoder` givens for `Value`, `Struct`, `Schema`, and function types. Use them for JSON over the wire — they are **not** the path ToolDispatcher macros use (that path is `dispatcher.Decoder` / `ValueEncoder`).

```scala mdoc:silent
import io.circe.syntax.*
import io.github.serhiip.constellations.common.Codecs.given
import io.github.serhiip.constellations.common.*

val json = Struct("a" -> Value.number(1)).asJson
```

## Related

- [Values & schemas](value-and-types.md) — `Value`, `Struct`, `Schema.derived`
- [ToolDispatcher](tool-dispatcher.md) — when these codecs are summoned
