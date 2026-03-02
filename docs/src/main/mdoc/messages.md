# Messages

The Messages module defines conversation message types for AI interactions.

## Overview

Constellations supports multiple message types:

```scala
enum Message:
  case User(content: List[ContentPart])
  case Assistant(content: Option[String], images: List[ContentPart.Image])
  case System(content: String)
  case Tool(content: FunctionCall)
  case ToolResult(content: FunctionResponse)
```

## Content Parts

Multimodal content support:

```scala
enum ContentPart:
  case Text(text: String)
  case Image(base64Encoded: String)
```

## Usage

Create user messages:

```scala
import io.github.serhiip.constellations.common.*

val userMessage = Message.User(List(
  ContentPart.Text("What's the weather like?")
))

val assistantMessage = Message.Assistant(
  content = Some("I'll check the weather for you."),
  images = List.empty
)
```

## Coming Soon

Detailed examples of multimodal conversations with images.
