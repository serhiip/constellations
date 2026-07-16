# Messages and function calls

Conversation and tool types live in **`constellations-common`**. They connect chat history to [`ToolDispatcher`](tool-dispatcher.md).

## Message

```scala mdoc:silent
import io.github.serhiip.constellations.common.*

val user = Message.User(List(ContentPart.Text("What's 2 + 2?")))

val assistant = Message.Assistant(
  content = Some("I'll use the calculator."),
  images = List.empty
)

val system = Message.System("You are a helpful assistant.")
```

| Case | Role |
|------|------|
| `User` | User turns (`ContentPart.Text` / `Image`) |
| `Assistant` | Model text (+ optional images) |
| `System` | System prompt |
| `Tool` | Model requested a tool (`FunctionCall`) |
| `ToolResult` | Tool output (`FunctionResponse`) |

## FunctionCall, FunctionResponse, FunctionDeclaration

```scala mdoc:silent
val call = FunctionCall(
  name = "Calculator_add",
  args = Struct(Map("a" -> Value.number(2), "b" -> Value.number(2))),
  callId = Some("call_1")
)

val toolMsg = Message.Tool(call)

// After ToolDispatcher.dispatch:
val response = FunctionResponse(
  name = "Calculator_add",
  response = Struct("value" -> Value.number(4)),
  functionCallId = Some("call_1")
)

val toolResult = Message.ToolResult(response)

val declaration = FunctionDeclaration(
  name = "Calculator_add",
  description = Some("Adds two integers together"),
  parameters = Some(
    Schema.obj(
      properties = Map(
        "a" -> Schema.integer(),
        "b" -> Schema.integer()
      ),
      required = List("a", "b")
    )
  )
)
```

Flow with ToolDispatcher:

1. Provider returns a tool call → build `FunctionCall` (or map from provider SDK)
2. `dispatcher.dispatch(call)` → `ToolDispatcher.Result`
3. On `Response`, append `Message.ToolResult` and continue the chat

`getFunctionDeclarations` produces the `FunctionDeclaration` list you register with the model.

## ContentPart

```scala mdoc:silent
val multimodal = Message.User(
  List(
    ContentPart.Text("Describe this image"),
    ContentPart.Image(base64Encoded = "<base64>")
  )
)
```

## Related

- [ToolDispatcher](tool-dispatcher.md)
- [Values & schemas](value-and-types.md)
- [Core concepts](core-concepts.md) — executor loop using these types
