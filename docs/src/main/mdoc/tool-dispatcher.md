# ToolDispatcher

`ToolDispatcher` routes LLM tool/function calls to your Scala implementations using Scala 3 macros. It lives in **`constellations-common`** (also pulled transitively by `constellations-core`).

```scala
libraryDependencies += "io.github.serhiip" %% "constellations-common" % "@VERSION@"
```

## API

```scala
trait ToolDispatcher[F[_]]:
  def prepare(call: FunctionCall): ValidatedNec[AgentError, F[ToolDispatcher.Result]]
  def dispatch(call: FunctionCall): F[ToolDispatcher.Result]
  def dispatchAll(calls: List[FunctionCall]): F[ValidatedNec[AgentError, List[ToolDispatcher.Result]]]
  def getFunctionDeclarations: F[List[FunctionDeclaration]]

enum ToolDispatcher.Result:
  case Response(result: FunctionResponse)
  case HumanInTheLoop
```

`dispatchAll` validates every call first (name lookup + argument decoding), aggregating failures with `ValidatedNec`. If **any** call is invalid it returns `Invalid` and executes **nothing**. If all are valid it executes each call **reusing the arguments already parsed** during validation (no second decode). Prefer `dispatchAll` for batches; `dispatch` remains for single calls and raises the first `AgentError` in `F`.

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

Pass concrete instances as **explicit arguments** (not a `Seq`). Requires `MonadThrow[F]` in scope so unknown tools and decode failures raise `AgentError` inside `F`:

```scala mdoc:silent
val dispatcher: ToolDispatcher[IO] =
  ToolDispatcher.generate[IO](calculator)
```

The instance you pass is `inline`, so its term is spliced into the generated dispatcher. Always pass a **stable reference** (a `val` or `object`). Passing a fresh expression such as `ToolDispatcher.generate[IO](makeCalculator())` would re-evaluate that expression on every dispatch — bind it to a `val` first.

### `to` — single trait as a factory

```scala mdoc:silent
val fromTrait: Calculator[IO] => ToolDispatcher[IO] =
  ToolDispatcher.to[IO, Calculator]

val typedDispatcher: ToolDispatcher[IO] =
  fromTrait(calculator)
```

### `generate` vs `to`

Both are macros that expand at **compile time** and emit the *same* dispatcher code (the same routing map, `dispatch` / `dispatchAll` / `getFunctionDeclarations`). There is no runtime performance difference between them; choose based on ergonomics:

| | `generate[F](component, others*)` | `to[F, T]` |
|---|---|---|
| Components | One **or many** (varargs) | Exactly one trait |
| Trait type | Inferred from the value | Written explicitly (`T`) |
| Returns | `ToolDispatcher[F]` | `T[F] => ToolDispatcher[F]` (reusable factory) |
| Instance term | `inline` — pass a stable `val`/`object` | Supplied at apply time as a stable lambda parameter (immune to re-evaluation) |
| When the macro runs | At the `generate(...)` call site, using the component's type and term | At the `to[F, T]` call site, using only the types; the instance is applied later at runtime |

Rule of thumb: reach for `generate` (the default in these docs) — especially to merge multiple components. Prefer `to` when you want a reusable `T[F] => ToolDispatcher[F]` factory, want the trait fixed explicitly in the type, or want to build from a non-stable expression without the re-evaluation caveat.

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

### Combining dispatchers

Use `combine` to merge already-built `ToolDispatcher` values (for example a local dispatcher plus an MCP one). It loads declarations once, builds a name→dispatcher index, and returns **`F[ToolDispatcher[F]]`**. Earlier dispatchers win on name clashes.

```scala mdoc:silent
val local: ToolDispatcher[IO] =
  ToolDispatcher.generate[IO](calculator)

val greetingOnly: ToolDispatcher[IO] =
  ToolDispatcher.generate[IO](greeting)

// merged: IO[ToolDispatcher[IO]]
val mergedIO = ToolDispatcher.combine(local, greetingOnly)
```

This is different from multi-arg `generate`, which merges trait *components* at compile time. Wrap the result with `ToolDispatcher.observed(...)` if you want a single observability layer on the composite.

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

`ToolDispatcher.dispatch` raises **`AgentError`** inside `F`. `dispatchAll` returns the same errors aggregated as `ValidatedNec[AgentError, List[Result]]` without executing any call when invalid (requires `MonadThrow[F]` when using `generate` / `to` / `combine`):

| Case | Error |
|------|--------|
| Unknown tool name | `AgentError.UnknownFunction(call)` |
| Missing/invalid arguments | `AgentError.ArgumentDecodingFailed(call, errors)` |

Both cases carry the original `FunctionCall` (including `callId` when the provider set one). Successful `FunctionResponse`s also embed that same `FunctionCall` (`response.call`), so providers read `call.callId` — there is no separate id field.
| Implementation failure | Error propagates in `F` (only after a successful validation in `dispatch` / `dispatchAll`) |

`Stateful` uses `dispatchAll` (validate-then-execute). On `Invalid` it posts tool-results for the whole batch (error for bad calls, skipped for valid siblings that were not run), retries up to `agentErrorRetryLimit`, then returns `Failure.AgentRetriesExhausted` with the aggregated `NonEmptyChain[AgentError]`.

## Observability

`ToolDispatcher.observed` wraps a delegate with tracing, logging, and success/error counters. It returns **`F[ToolDispatcher[F]]`** and needs:

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
  ToolDispatcher.observed(raw)
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
