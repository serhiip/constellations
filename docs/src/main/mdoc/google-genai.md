# Google GenAI Integration

Constellations provides integration with Google's GenAI API (Vertex AI and AI Studio).

## Overview

The Google GenAI module provides:
- **Chat generation** - Conversational AI
- **Function calling** - Native tool use support
- **Streaming responses** - Real-time output
- **Image understanding** - Multimodal content

## Setup

Add the dependency:

```scala
libraryDependencies += "io.github.serhiip" %% "constellations-google-genai" % "@VERSION@"
```

## Authentication

Configure your API key:

```scala
import io.github.serhiip.constellations.google.*

val config = Client.Config(
  apiKey = "your-api-key",
  projectId = Some("your-gcp-project")
)
```

## Chat Generation

Generate a response:

```scala
val request = GenerateContentRequest(
  model = "gemini-pro",
  contents = List(
    Content.user("What's the weather like?")
  )
)

client.generateContent(request).flatMap { response =>
  IO.println(response.text)
}
```

## Function Calling

Use your dispatcher for function calls:

```scala
val request = GenerateContentRequest(
  model = "gemini-pro",
  contents = List(
    Content.user("Calculate 5 + 3")
  ),
  tools = Some(List(Tool(functionDeclarations)))
)
```

## Coming Soon

Detailed examples for streaming, multimodal content, and advanced configuration.
