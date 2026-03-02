# Invoker

The Invoker handles communication with AI models, managing message generation and retry logic.

## Overview

The Invoker abstraction provides:
- **Model communication** - Send messages and receive responses
- **Retry logic** - Automatic retry with exponential backoff
- **Message history** - Contextual conversations
- **Response schemas** - Structured output support

```scala
trait Invoker[F[_]]:
  def generate(
    history: Chain[Message],
    responseSchema: Option[List[FunctionDeclaration]]
  ): F[Invoker.Result[T]]
```

## Retry Configuration

Add retry logic to any Invoker:

```scala
import io.github.serhiip.constellations.Invoker
import scala.concurrent.duration.*

val retryingInvoker = Invoker.retrying(
  invoker,
  maxRetries = 3,
  delay = 100.millis
)
```

## Coming Soon

Detailed examples for OpenRouter and Google GenAI Invoker implementations.
