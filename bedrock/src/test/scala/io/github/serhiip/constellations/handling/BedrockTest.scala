package io.github.serhiip.constellations.handling

import cats.effect.IO
import cats.syntax.all.*
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.bedrock.Codecs
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.services.bedrockruntime.model.{
  ContentBlock,
  ConversationRole,
  ConverseOutput,
  ConverseResponse,
  Message,
  StopReason,
  ToolUseBlock
}

final class BedrockHandlingTest extends CatsEffectSuite:

  private def responseWith(
      text: Option[String] = None,
      toolUses: List[ToolUseBlock] = Nil,
      stopReason: StopReason = StopReason.END_TURN
  ): ConverseResponse =
    val contentBlocks = text.map(ContentBlock.fromText).toList ++ toolUses.map(ContentBlock.fromToolUse)
    val message       = Message.builder().role(ConversationRole.ASSISTANT).content(contentBlocks.asJava).build()
    ConverseResponse
      .builder()
      .output(ConverseOutput.fromMessage(message))
      .stopReason(stopReason)
      .build()

  test("getTextFromResponse extracts assistant text") {
    val handling = Bedrock[IO]
    val response = responseWith(text = "Hello from Bedrock".some)

    handling.getTextFromResponse(response).map(text => assertEquals(text, Some("Hello from Bedrock")))
  }

  test("getFunctinoCalls extracts tool use blocks") {
    val handling = Bedrock[IO]
    val toolUse  = ToolUseBlock
      .builder()
      .toolUseId("call-123")
      .name("getWeather")
      .input(Codecs.structToDocument(Struct("city" -> Value.string("London"))))
      .build()
    val response = responseWith(toolUses = List(toolUse), stopReason = StopReason.TOOL_USE)

    handling.getFunctinoCalls(response).map { calls =>
      assertEquals(calls.size, 1)
      assertEquals(calls.head.name, "getWeather")
      assertEquals(calls.head.args, Struct("city" -> Value.string("London")))
      assertEquals(calls.head.callId, Some("call-123"))
    }
  }

  test("finishReason maps Bedrock stop reasons") {
    val handling = Bedrock[IO]
    val cases    = List(
      StopReason.END_TURN                      -> FinishReason.Stop,
      StopReason.STOP_SEQUENCE                 -> FinishReason.Stop,
      StopReason.TOOL_USE                      -> FinishReason.ToolCalls,
      StopReason.MAX_TOKENS                    -> FinishReason.Length,
      StopReason.CONTENT_FILTERED              -> FinishReason.ContentFilter,
      StopReason.GUARDRAIL_INTERVENED          -> FinishReason.ContentFilter,
      StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> FinishReason.Error
    )

    cases.traverse_ { case (bedrockReason, expected) =>
      handling.finishReason(responseWith(stopReason = bedrockReason)).map(reason => assertEquals(reason, expected))
    }
  }

  test("structuredOutput parses JSON text into Struct") {
    val handling = Bedrock[IO]
    val response = responseWith(text = """{"city":"London","temperature":18}""".some)

    handling.structuredOutput(response).map { struct =>
      assertEquals(struct.fields("city"), Value.string("London"))
      assertEquals(struct.fields("temperature"), Value.number(18))
    }
  }
