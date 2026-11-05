# OpenRouter Integration

Constellations provides seamless integration with the [OpenRouter](https://openrouter.ai/) API.

## Overview

The OpenRouter module provides:
- **Chat completions** - Conversational AI with function calling
- **Text completions** - Simple text generation
- **Model management** - List available models and their capabilities
- **Built-in observability** - Metrics and tracing
- **Retry logic** - Automatic retries with exponential backoff

## Setup

Add the dependency:

```scala
libraryDependencies += "io.github.serhiip" %% "constellations-openrouter" % "@VERSION@"
```

## Usage

Create a client:

```scala
import io.github.serhiip.constellations.openrouter.*
import cats.effect.IO
import org.typelevel.otel4s.metrics.Meter

val config = Client.Config(
  appUrl = Some("https://my-app.com"),
  appTitle = Some("My AI App")
)

given Meter[IO] = Meter.noop[IO]

Client.HTTP[IO]().map(Client.core("your-api-key", config)).use { client =>
  // Use client here
}
```

## Chat Completions

Send a chat request:

```scala
import io.github.serhiip.constellations.openrouter.schema.chat.messages.ChatUserMessage
import io.github.serhiip.constellations.openrouter.schema.chat.request.ChatRequest

val request = ChatRequest(
  model = Some("anthropic/claude-3-haiku"),
  messages = List(
    ChatUserMessage(content = "What's 2 + 2?")
  ),
  temperature = Some(0.7)
)

client.createChatCompletion(request).flatMap { response =>
  IO.println(s"Response: ${response.choices.head.message.content}")
}
```

## Function Calling

Pass your dispatcher for function calling:

```scala
val request = ChatRequest(
  model = Some("anthropic/claude-3-haiku"),
  messages = List(
    ChatUserMessage(content = "Calculate 2 + 2")
  ),
  tools = Some(functionDeclarations),
  tool_choice = Some("auto")
)
```

## Coming Soon

Detailed examples for error handling, model management, and observability features.
