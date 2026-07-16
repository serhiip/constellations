# Getting Started

Welcome to Constellations — a type-safe Scala 3 toolkit for AI function calling, schema derivation, and observability.

## Installation

### Tools only (`constellations-common`)

For routing LLM tool calls to Scala methods without Executor/Memory:

```scala
libraryDependencies += "io.github.serhiip" %% "constellations-common" % "@VERSION@"
```

### Full stack

For conversation loops plus a provider:

```scala
libraryDependencies ++= Seq(
  "io.github.serhiip" %% "constellations-core" % "@VERSION@",
  "io.github.serhiip" %% "constellations-openrouter" % "@VERSION@"
)
```

`constellations-core` pulls `constellations-common` transitively.

### Scala versions

- `constellations-common` (including `ToolDispatcher`) is built with **Scala 3.3.8** (LTS) and works on 3.3.8+
- Other modules (`core`, providers, `mcp`, …) require **Scala 3.7.x**

## Quick example

Define an effectful trait, generate a `ToolDispatcher`, then dispatch a call named `{Trait}_{snake_case_method}`:

```scala mdoc:silent
import cats.effect.IO
import io.github.serhiip.constellations.ToolDispatcher
import io.github.serhiip.constellations.common.*

trait Calculator[F[_]]:
  /** Adds two numbers together */
  def add(a: Int, b: Int): F[Int]

  /** Multiplies two numbers */
  def multiply(a: Int, b: Int): F[Int]

val calculatorImpl = new Calculator[IO]:
  def add(a: Int, b: Int): IO[Int] = IO.pure(a + b)
  def multiply(a: Int, b: Int): IO[Int] = IO.pure(a * b)

val dispatcher: ToolDispatcher[IO] =
  ToolDispatcher.generate[IO](calculatorImpl)
```

```scala mdoc
import cats.effect.unsafe.implicits.global

val call = FunctionCall(
  name = "Calculator_add",
  args = Struct(Map(
    "a" -> Value.number(5),
    "b" -> Value.number(3)
  ))
)

val result = dispatcher.dispatch(call).unsafeRunSync()
result match
  case ToolDispatcher.Result.Response(response) =>
    println(s"Result: ${response.response}")
  case ToolDispatcher.Result.HumanInTheLoop =>
    println("Human approval required")
```

Scalar results are wrapped as a struct with a `value` field (here `{"value": 8.0}`).

## Modules

| Module | Role |
|--------|------|
| **constellations-common** | Types, codecs, schema derivation, `ToolDispatcher` |
| **constellations-core** | Executor, memory, invoker abstractions |
| **constellations-openrouter** / **google-genai** / … | LLM providers |
| **constellations-mcp** | Expose tools via Model Context Protocol |

## Next steps

- [ToolDispatcher](tool-dispatcher.md) — naming, `generate` / `to`, observability
- [Encoding & decoding](encoding-decoding.md) — how arguments and results are converted
- [Values & schemas](value-and-types.md) — `Value`, `Struct`, `Schema.derived`
- [Core concepts](core-concepts.md) — how common fits the executor loop
- [OpenRouter](openrouter.md) / [Google GenAI](google-genai.md) — providers
- [Examples](examples.md) — runnable samples
