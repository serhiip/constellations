package io.github.serhiip.constellations.handling

import cats.syntax.all.*
import munit.FunSuite

import io.github.serhiip.constellations.common.{FinishReason, FunctionCall, Value}
import io.github.serhiip.constellations.openrouter.schema.chat.choice.ChatChoice
import io.github.serhiip.constellations.openrouter.schema.chat.content.image.ImageUrl
import io.github.serhiip.constellations.openrouter.schema.chat.content.items.{ChatContentImage, ChatContentItems, ChatContentText}
import io.github.serhiip.constellations.openrouter.schema.chat.finish.reason.enum_.ChatFinishReasonEnum
import io.github.serhiip.constellations.openrouter.schema.chat.messages.ChatAssistantMessage
import io.github.serhiip.constellations.openrouter.schema.chat.result.ChatResult
import io.github.serhiip.constellations.openrouter.schema.chat.tool.call.{ChatToolCall, Function}

class OpenRouterTest extends FunSuite:

  private val handling = OpenRouter()

  private def responseWith(
      message: ChatAssistantMessage,
      finishReason: Option[ChatFinishReasonEnum] = Some(ChatFinishReasonEnum.Stop)
  ): ChatResult =
    ChatResult(
      id = "test-id",
      created = 1234567890L,
      model = "test-model",
      systemFingerprint = None,
      choices = List(ChatChoice(finishReason = finishReason, index = 0L, message = message))
    )

  test("OpenRouter handling should work with IO effect type") {
    val response = responseWith(ChatAssistantMessage(content = Some("Hello, world!")))

    assertEquals(handling.getTextFromResponse(response), Some("Hello, world!"))
    assertEquals(handling.finishReason(response), FinishReason.Stop)
    assertEquals(handling.getFunctionCalls(response), List.empty[FunctionCall].asRight)
  }

  test("OpenRouter handling should handle missing content") {
    val response = responseWith(ChatAssistantMessage())

    assertEquals(handling.getTextFromResponse(response), None)
  }

  test("OpenRouter handling should join text parts and skip image parts") {
    val content: List[ChatContentItems] | String = List(
      ChatContentText(text = "Hello "),
      ChatContentImage(imageUrl = ImageUrl(url = "data:image/png;base64,abc")),
      ChatContentText(text = "world"),
      ChatContentText(text = "   ")
    )
    val response = responseWith(ChatAssistantMessage(content = Some(content), reasoning = Some("ignored")))

    assertEquals(handling.getTextFromResponse(response), Some("Hello world"))
  }

  test("OpenRouter handling should prefer content over reasoning") {
    val response = responseWith(ChatAssistantMessage(content = Some("answer"), reasoning = Some("chain of thought")))

    assertEquals(handling.getTextFromResponse(response), Some("answer"))
  }

  test("OpenRouter handling should use reasoning when content is blank") {
    val response = responseWith(ChatAssistantMessage(content = Some("  "), reasoning = Some("the reply")))

    assertEquals(handling.getTextFromResponse(response), Some("the reply"))
  }

  test("OpenRouter handling should use reasoning when content is missing") {
    val response = responseWith(ChatAssistantMessage(reasoning = Some("only reasoning")))

    assertEquals(handling.getTextFromResponse(response), Some("only reasoning"))
  }

  test("OpenRouter handling should ignore blank reasoning") {
    val response = responseWith(ChatAssistantMessage(content = Some(""), reasoning = Some("  ")))

    assertEquals(handling.getTextFromResponse(response), None)
  }

  test("OpenRouter handling should handle empty choices") {
    val response = ChatResult(
      id = "test-id",
      created = 1234567890L,
      model = "test-model",
      systemFingerprint = None,
      choices = List.empty
    )

    assertEquals(handling.getTextFromResponse(response), None)
    assertEquals(handling.finishReason(response), FinishReason.Error)
    assertEquals(handling.getFunctionCalls(response), List.empty[FunctionCall].asRight)
  }

  test("OpenRouter handling should parse tool calls with empty or blank arguments as empty Struct") {
    val response = responseWith(
      ChatAssistantMessage(
        toolCalls = Some(List(ChatToolCall(id = "call_abc", function = Function(name = "get_current_time", arguments = ""))))
      ),
      finishReason = Some(ChatFinishReasonEnum.ToolCalls)
    )

    val calls = handling.getFunctionCalls(response).toOption.get
    assertEquals(calls.length, 1)
    assertEquals(calls.head.name, "get_current_time")
    assertEquals(calls.head.args.fields, Map.empty)
    assertEquals(calls.head.callId, Some("call_abc"))
  }

  test("OpenRouter handling should decode typed tool-call arguments without quoting strings") {
    val response = responseWith(
      ChatAssistantMessage(
        toolCalls = Some(
          List(
            ChatToolCall(
              id = "call-schedule",
              function = Function(
                name = "schedule_api_at",
                arguments = """{"when":"2025-07-21T15:00:00+01:00","count":2,"enabled":true}"""
              )
            )
          )
        )
      ),
      finishReason = Some(ChatFinishReasonEnum.ToolCalls)
    )

    val calls = handling.getFunctionCalls(response).toOption.get
    assertEquals(calls.length, 1)
    assertEquals(calls.head.name, "schedule_api_at")
    assertEquals(calls.head.callId, Some("call-schedule"))
    assertEquals(calls.head.args.fields("when"), Value.string("2025-07-21T15:00:00+01:00"))
    assertEquals(calls.head.args.fields("count"), Value.number(2.0))
    assertEquals(calls.head.args.fields("enabled"), Value.bool(true))
  }

  test("OpenRouter handling should handle different finish reasons") {
    val testCases = List(
      (ChatFinishReasonEnum.ToolCalls, FinishReason.ToolCalls),
      (ChatFinishReasonEnum.Stop, FinishReason.Stop),
      (ChatFinishReasonEnum.Length, FinishReason.Length),
      (ChatFinishReasonEnum.ContentFilter, FinishReason.ContentFilter),
      (ChatFinishReasonEnum.Unknown("unknown"), FinishReason.Error)
    )

    testCases.foreach { (reason, expectedReason) =>
      val response = responseWith(ChatAssistantMessage(content = Some("test")), finishReason = Some(reason))
      assertEquals(handling.finishReason(response), expectedReason)
    }
  }
