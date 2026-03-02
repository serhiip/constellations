package io.github.serhiip.constellations.handling

import cats.effect.IO
import munit.CatsEffectSuite
import cats.syntax.all.*
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.openrouter.{
  ChatCompletionChoice,
  ChatCompletionResponse,
  ChatCompletionUsage,
  ChatMessage,
  ToolCall => OrToolCall,
  ToolCallFunction => OrToolCallFunction
}
import io.circe.Json

class OpenRouterTest extends CatsEffectSuite:

  test("OpenRouter handling should work with IO effect type") {
    val handling = OpenRouter[IO]()

    val response = ChatCompletionResponse(
      id = "test-id",
      `object` = "chat.completion",
      created = 1234567890L,
      model = "test-model",
      choices = List(
        ChatCompletionChoice(
          index = 0,
          message = ChatMessage(
            role = "assistant",
            content = Some(Json.fromString("Hello, world!"))
          ),
          finishReason = Some("stop")
        )
      ),
      usage = ChatCompletionUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)
    )

    for
      text   <- handling.getTextFromResponse(response)
      reason <- handling.finishReason(response)
      calls  <- handling.getFunctinoCalls(response)
    yield
      assertEquals(text, Some("Hello, world!"))
      assertEquals(reason, FinishReason.Stop)
      assertEquals(calls, List.empty[FunctionCall])
  }

  test("OpenRouter handling should handle missing content") {
    val handling = OpenRouter[IO]()

    val response = ChatCompletionResponse(
      id = "test-id",
      `object` = "chat.completion",
      created = 1234567890L,
      model = "test-model",
      choices = List(
        ChatCompletionChoice(
          index = 0,
          message = ChatMessage(
            role = "assistant",
            content = None
          ),
          finishReason = Some("stop")
        )
      ),
      usage = ChatCompletionUsage(promptTokens = 10, completionTokens = 0, totalTokens = 10)
    )

    handling.getTextFromResponse(response).map { text =>
      assertEquals(text, None)
    }
  }

  test("OpenRouter handling should handle empty choices") {
    val handling = OpenRouter[IO]()

    val response = ChatCompletionResponse(
      id = "test-id",
      `object` = "chat.completion",
      created = 1234567890L,
      model = "test-model",
      choices = List.empty,
      usage = ChatCompletionUsage(promptTokens = 10, completionTokens = 0, totalTokens = 10)
    )

    for
      text   <- handling.getTextFromResponse(response)
      reason <- handling.finishReason(response)
      calls  <- handling.getFunctinoCalls(response)
    yield
      assertEquals(text, None)
      assertEquals(reason, FinishReason.Error)
      assertEquals(calls, List.empty[FunctionCall])
  }

  test("OpenRouter handling should parse tool calls with empty or blank arguments as empty Struct") {
    val handling = OpenRouter[IO]()

    val response = ChatCompletionResponse(
      id = "test-id",
      `object` = "chat.completion",
      created = 1234567890L,
      model = "test-model",
      choices = List(
        ChatCompletionChoice(
          index = 0,
          message = ChatMessage(
            role = "assistant",
            content = None,
            toolCalls = Some(
              List(
                OrToolCall(
                  id = "call_abc",
                  `type` = "function",
                  function = OrToolCallFunction(name = "get_current_time", arguments = "")
                )
              )
            )
          ),
          finishReason = Some("tool_calls")
        )
      ),
      usage = ChatCompletionUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)
    )

    for calls <- handling.getFunctinoCalls(response)
    yield
      assertEquals(calls.length, 1)
      assertEquals(calls.head.name, "get_current_time")
      assertEquals(calls.head.args.fields, Map.empty)
      assertEquals(calls.head.callId, Some("call_abc"))
  }

  test("OpenRouter handling should handle different finish reasons") {
    val handling = OpenRouter[IO]()

    val testCases = List(
      ("tool_calls", FinishReason.ToolCalls),
      ("stop", FinishReason.Stop),
      ("length", FinishReason.Length),
      ("content_filter", FinishReason.ContentFilter),
      ("unknown", FinishReason.Error)
    )

    testCases.traverse { case (reasonStr, expectedReason) =>
      val response = ChatCompletionResponse(
        id = "test-id",
        `object` = "chat.completion",
        created = 1234567890L,
        model = "test-model",
        choices = List(
          ChatCompletionChoice(
            index = 0,
            message = ChatMessage(
              role = "assistant",
              content = Some(Json.fromString("test"))
            ),
            finishReason = Some(reasonStr)
          )
        ),
        usage = ChatCompletionUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)
      )

      handling.finishReason(response).map { reason =>
        assertEquals(reason, expectedReason)
      }
    }.void
  }
