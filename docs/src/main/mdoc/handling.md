# Handling

`Handling[T]` is a pure typeclass that parses provider-specific AI responses into common Constellations types.

Image / binary assets are handled separately by effectful [`AssetsHandling`](#assetshandling).

## Overview

Handling provides:
- **Text extraction** — plain text from the response
- **Function calls** — tool/function call requests
- **Finish reason** — why generation stopped
- **Structured output** — JSON/`Struct` payloads

```scala mdoc:compile-only
import io.github.serhiip.constellations.common.*

trait HandlingExample[T]:
  def getTextFromResponse(response: T): Option[String]
  def getFunctionCalls(response: T): Either[Throwable, List[FunctionCall]]
  def finishReason(response: T): FinishReason
  def structuredOutput(response: T): Either[Throwable, Struct]
```

Fallible methods return `Either[Throwable, A]`; consumers lift into their effect with `.liftTo[F]`.

## Summoning

The companion summons an instance in scope:

```scala mdoc:compile-only
import io.github.serhiip.constellations.Handling

// given Handling[MyResponse] = ...
def useHandling[T](response: T)(using Handling[T]): Option[String] =
  Handling[T].getTextFromResponse(response)
```

Provider modules export `given` instances. Import them at the call site:

```scala
import io.github.serhiip.constellations.handling.OpenRouter.given
import io.github.serhiip.constellations.handling.GoogleGenAI.given
import io.github.serhiip.constellations.handling.Bedrock.given
```

Thin factories still exist for tests (`OpenRouter()`, `GoogleGenAI()`, `Bedrock()`) and simply `summon` the given.

## AssetsHandling

`AssetsHandling[F, T]` extracts generated images (and later other binary assets) in an effect `F`, with bytes as `Stream[F, Byte]`:

```scala mdoc:compile-only
import cats.effect.IO
import io.github.serhiip.constellations.common.GeneratedImage

trait AssetsHandlingExample[T]:
  def getImages(response: T): IO[List[GeneratedImage[IO]]]
```

Provider objects export both `Handling` and `AssetsHandling` givens. Importing `.given` brings both into scope.

## Consumers

`StructuredInvoker` needs `Handling[T]`. `Stateful` needs both `Handling[T]` and `AssetsHandling[F, T]`. Production `Stateful(...)` also needs `Tracer` and `StructuredLogger` (use `Stateful.pure` in tests):

```scala
import io.github.serhiip.constellations.handling.OpenRouter.given
import io.github.serhiip.constellations.executor.Stateful

Stateful.Config(functionCallLimit = 5, agentErrorRetryLimit = 3)
// optional: agentErrorInstruction: String (correction text in the tool-result)
Stateful[IO, ChatCompletionResponse](config, invoker, files)
StructuredInvoker[IO, ChatCompletionResponse, MyType](baseInvoker)
```

On invalid tool names/arguments (`AgentError`), `Stateful` uses `ToolDispatcher.dispatchAll`, which validates the whole batch first and executes none on failure. It posts tool-results with correction instructions, retries until `agentErrorRetryLimit`, then returns `Left(Failure.AgentRetriesExhausted(errors))`. Only when every call is `Valid` does `dispatchAll` run the prepared effects.
