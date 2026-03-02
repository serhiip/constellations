# Handling

The Handling abstraction parses AI responses and extracts structured data.

## Overview

Handling provides:
- **Text extraction** - Get plain text from AI responses
- **Function calls** - Extract function call requests
- **Structured output** - Parse JSON responses
- **Multimodal content** - Handle images and files

```scala
trait Handling[F[_], T]:
  def getTextFromResponse(response: T): F[Option[String]]
  def getFunctionCalls(response: T): F[Chain[ToolCall]]
  def finishReason(response: T): F[FinishReason]
  def structuredOutput(response: T): F[Option[Struct]]
  def getImages(response: T): F[Chain[ContentPart.Image]]
```

## Coming Soon

Detailed examples for OpenRouter and Google GenAI Handling implementations.
