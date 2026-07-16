# ToolDispatcher

`ToolDispatcher` routes LLM tool/function calls to your Scala implementations using Scala 3 macros. It lives in **`constellations-common`** (also pulled transitively by `constellations-core`).

```scala
libraryDependencies += "io.github.serhiip" %% "constellations-common" % "@VERSION@"
```

## API

```scala
trait ToolDispatcher[F[_]]:
  def dispatch(call: FunctionCall): F[ToolDispatcher.Result]
  def getFunctionDeclarations: F[List[FunctionDeclaration]]

enum ToolDispatcher.Result:
  case Response(result: FunctionResponse)
  case HumanInTheLoop
```

## Trait rules

1. Define an effectful trait `Api[F[_]]`
2. Public methods must return `F[A]` (not bare `A`)
3. Parameter and return types must have `Decoder` / `ResultEncoder` support (see [Encoding & decoding](encoding-decoding.md))
4. Method/parameter **docstrings** become LLM descriptions and schema descriptions

```scala mdoc:silent
import cats.effect.IO
import io.github.serhiip.constellations.ToolDispatcher
import io.github.serhiip.constellations.common.*

trait Calculator[F[_]]:
  /** Adds two integers together */
  def add(a: Int, b: Int): F[Int]

  /** Multiplies two integers */
  def multiply(a: Int, b: Int): F[Int]

  /** Divides a by b */
  def divide(a: Double, b: Double): F[Double]

val calculator = new Calculator[IO]:
  def add(a: Int, b: Int): IO[Int] = IO.pure(a + b)
  def multiply(a: Int, b: Int): IO[Int] = IO.pure(a * b)
  def divide(a: Double, b: Double): IO[Double] =
    if b == 0 then IO.raiseError(ArithmeticException("Division by zero"))
    else IO.pure(a / b)
```

## Building a dispatcher

### `generate` — one or more components

Pass concrete instances as **explicit arguments** (not a `Seq`):

```scala mdoc:silent
val dispatcher: ToolDispatcher[IO] =
  ToolDispatcher.generate[IO](calculator)
```

### `to` — single trait as a factory

```scala mdoc:silent
val fromTrait: Calculator[IO] => ToolDispatcher[IO] =
  ToolDispatcher.to[IO, Calculator]

val typedDispatcher: ToolDispatcher[IO] =
  fromTrait(calculator)
```

### Multi-component

```scala mdoc:silent
trait Greeting[F[_]]:
  /** Greets someone by name */
  def greet(name: String): F[String]

val greeting = new Greeting[IO]:
  def greet(name: String): IO[String] = IO.pure(s"Hello, $name")

val multi: ToolDispatcher[IO] =
  ToolDispatcher.generate[IO](calculator, greeting)
```

Declarations from each trait are merged; tool names stay unique via the trait prefix.

## Naming contract

| Scala | Tool / JSON name |
|-------|------------------|
| Trait `Calculator` | kept as-is → prefix `Calculator` |
| Method `add` | `add` → tool `Calculator_add` |
| Method `mixedTypes` | `mixed_types` → `Calculator_mixed_types` |
| Param `intVal` | `int_val` |
| Param `strVal` | `str_val` |

Component (trait) names are **not** snake_cased. Only methods and parameters are.

```scala mdoc
import cats.effect.unsafe.implicits.global

val call = FunctionCall(
  name = "Calculator_add",
  args = Struct(Map(
    "a" -> Value.number(10),
    "b" -> Value.number(5)
  ))
)

val result = dispatcher.dispatch(call).unsafeRunSync()
result match
  case ToolDispatcher.Result.Response(response) =>
    println(s"Response: ${response.response}")
  case ToolDispatcher.Result.HumanInTheLoop =>
    println("Human approval required")
```

## Function declarations

`getFunctionDeclarations` returns schemas built from your traits (names, docstrings, parameter schemas):

```scala mdoc
val declarations = dispatcher.getFunctionDeclarations.unsafeRunSync()
declarations.foreach { decl =>
  println(s"- ${decl.name}: ${decl.description.getOrElse("")}")
}
```

Pass these to your LLM provider as tools, or convert them with [MCP](mcp.md) via `fromToolDispatcher`.

## Results and HumanInTheLoop

- Ordinary return types are encoded via `ResultEncoder` (usually wrapping scalars as `Struct("value" → …)`).
- Return `ToolDispatcher.Result` (or `FunctionResponse`) from a method when you need to signal **human-in-the-loop** or a custom response shape.

```scala mdoc:silent
trait Approval[F[_]]:
  def requestReview(summary: String): F[ToolDispatcher.Result]

val approval = new Approval[IO]:
  def requestReview(summary: String): IO[ToolDispatcher.Result] =
    IO.pure(ToolDispatcher.Result.HumanInTheLoop)

val withApproval: ToolDispatcher[IO] =
  ToolDispatcher.generate[IO](approval)
```

## Errors

| Case | Behavior |
|------|----------|
| Unknown tool name | Throws `RuntimeException` |
| Missing/invalid arguments | Throws `IllegalArgumentException` with decode errors |
| Implementation failure | Error propagates in `F` |

Decode errors currently surface as thrown exceptions when building the call (outside `F`). Prefer validating tool args from the model when possible.

## Observability

`ToolDispatcher.apply` wraps a delegate with tracing, logging, and success/error counters. It returns **`F[ToolDispatcher[F]]`** and needs:

- `Tracer[F]`
- `LoggerFactory[F]`
- `Meter[F]`
- `MonadThrow[F]`

```scala mdoc:compile-only
import cats.effect.IO
import cats.MonadThrow
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import io.github.serhiip.constellations.ToolDispatcher

def observe[F[_]: Tracer: LoggerFactory: Meter: MonadThrow](
    raw: ToolDispatcher[F]
): F[ToolDispatcher[F]] =
  ToolDispatcher(raw)
```

| Kind | Name |
|------|------|
| Span | `constellations-tool-dispatcher-dispatch` |
| Span | `constellations-tool-dispatcher-get-function-declarations` |
| Counter | `constellations/tool_dispatcher_dispatch_success_count` |
| Counter | `constellations/tool_dispatcher_dispatch_error_count` |

See [Observability](observability.md).

## `mapK` and `noop`

```scala mdoc:silent
import cats.~>
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

val futureDispatcher: ToolDispatcher[Future] =
  ToolDispatcher.mapK(dispatcher)(new (IO ~> Future) {
    def apply[A](fa: IO[A]): Future[A] = fa.unsafeToFuture()
  })

val stub: ToolDispatcher[IO] = ToolDispatcher.noop[IO]
```

`noop` returns empty declarations; `dispatch` throws.

## Schema generation (parameters)

From method signatures the macro supports:

- Primitives: `Int`, `Long`, `Double`, `Float`, `String`, `Boolean`
- `Option` (nullable / optional)
- `List` / `Seq`
- Case classes (nested objects)
- Sealed traits / enums (string enums)

ToolDispatcher parameter schemas use **docstrings**, not `@llmHint`. For standalone schema derivation with `@llmHint`, see [Values & schemas](value-and-types.md).

## Next

- [Encoding & decoding](encoding-decoding.md) — `ValueEncoder`, `Decoder`, scalar wrapping
- [MCP](mcp.md) — expose a dispatcher as MCP tools
- [Getting started](getting-started.md) — install paths
