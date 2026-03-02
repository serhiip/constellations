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
import org.http4s.ember.client.EmberClientBuilder

val config = Client.Config(
  appUrl = Some("https://my-app.com"),
  appTitle = Some("My AI App")
)

Client.resource[IO]("your-api-key", config).use { client =>
  // Use client here
}
```

## Chat Completions

Send a chat request:

```scala
val request = ChatCompletionRequest(
  model = "anthropic/claude-3-haiku",
  messages = List(
    ChatMessage.user("What's 2 + 2?")
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
val request = ChatCompletionRequest(
  model = "anthropic/claude-3-haiku",
  messages = List(
    ChatMessage.user("Calculate 2 + 2")
  ),
  functions = Some(functionDeclarations),
  functionCall = Some("auto")
)
```

## Coming Soon

Detailed examples for error handling, model management, and observability features.
