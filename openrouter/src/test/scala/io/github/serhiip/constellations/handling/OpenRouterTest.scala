package io.github.serhiip.constellations.handling

import cats.syntax.all.*
import io.circe.Json
import munit.FunSuite

import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.openrouter.{
  ChatCompletionChoice,
  ChatCompletionResponse,
  ChatCompletionUsage,
  ChatMessage,
  ToolCall => OrToolCall,
  ToolCallFunction => OrToolCallFunction
}

class OpenRouterTest extends FunSuite:

  private val handling = OpenRouter()

  test("OpenRouter handling should work with IO effect type") {
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

    assertEquals(handling.getTextFromResponse(response), Some("Hello, world!"))
    assertEquals(handling.finishReason(response), FinishReason.Stop)
    assertEquals(handling.getFunctionCalls(response), List.empty[FunctionCall].asRight)
  }

  test("OpenRouter handling should handle missing content") {
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

    assertEquals(handling.getTextFromResponse(response), None)
  }

  test("OpenRouter handling should handle empty choices") {
    val response = ChatCompletionResponse(
      id = "test-id",
      `object` = "chat.completion",
      created = 1234567890L,
      model = "test-model",
      choices = List.empty,
      usage = ChatCompletionUsage(promptTokens = 10, completionTokens = 0, totalTokens = 10)
    )

    assertEquals(handling.getTextFromResponse(response), None)
    assertEquals(handling.finishReason(response), FinishReason.Error)
    assertEquals(handling.getFunctionCalls(response), List.empty[FunctionCall].asRight)
  }

  test("OpenRouter handling should parse tool calls with empty or blank arguments as empty Struct") {
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

    val calls = handling.getFunctionCalls(response).toOption.get
    assertEquals(calls.length, 1)
    assertEquals(calls.head.name, "get_current_time")
    assertEquals(calls.head.args.fields, Map.empty)
    assertEquals(calls.head.callId, Some("call_abc"))
  }

  test("OpenRouter handling should handle different finish reasons") {
    val testCases = List(
      ("tool_calls", FinishReason.ToolCalls),
      ("stop", FinishReason.Stop),
      ("length", FinishReason.Length),
      ("content_filter", FinishReason.ContentFilter),
      ("unknown", FinishReason.Error)
    )

    testCases.foreach { case (reasonStr, expectedReason) =>
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

      assertEquals(handling.finishReason(response), expectedReason)
    }
  }
