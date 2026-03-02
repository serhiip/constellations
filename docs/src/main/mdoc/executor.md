# Executor

The Executor orchestrates AI conversations, managing the flow from user queries through AI responses and function calls.

## Overview

The Executor provides:
- **Conversation state management** - Track where you are in a conversation
- **Multi-step execution** - Handle complex workflows with multiple function calls
- **Memory integration** - Persist conversation history
- **Image persistence** - Handle multimodal content

```scala
trait Executor[F[_], E, T]:
  def execute(query: String): F[Either[E, T]]
  def resume(): F[Either[E, T]]
```

## Execution Steps

The Executor tracks these step types:

- **UserQuery** - User's initial message
- **UserReply** - User's response to a question
- **ModelResponse** - AI's response
- **Call** - Function call made by AI
- **Response** - Function call result
- **System** - System message or metadata

## Coming Soon

Detailed examples and API documentation for the Executor component.
