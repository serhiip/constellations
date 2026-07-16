package io.github.serhiip.constellations.handling

import cats.effect.IO
import cats.syntax.all.*
import io.github.serhiip.constellations.AssetsHandling
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.bedrock.Codecs
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.model.{
  ContentBlock,
  ConversationRole,
  ConverseOutput,
  ConverseResponse,
  ImageBlock,
  ImageFormat,
  ImageSource,
  Message,
  StopReason,
  ToolUseBlock
}

final class BedrockHandlingTest extends CatsEffectSuite:

  import Bedrock.given

  private val handling = Bedrock()
  private val assets   = AssetsHandling[IO, ConverseResponse]

  private def responseWith(
      text: Option[String] = None,
      toolUses: List[ToolUseBlock] = Nil,
      images: List[ImageBlock] = Nil,
      stopReason: StopReason = StopReason.END_TURN
  ): ConverseResponse =
    val contentBlocks =
      text.map(ContentBlock.fromText).toList ++
        toolUses.map(ContentBlock.fromToolUse) ++
        images.map(ContentBlock.fromImage)
    val message       = Message.builder().role(ConversationRole.ASSISTANT).content(contentBlocks.asJava).build()
    ConverseResponse
      .builder()
      .output(ConverseOutput.fromMessage(message))
      .stopReason(stopReason)
      .build()

  test("getTextFromResponse extracts assistant text") {
    val response = responseWith(text = "Hello from Bedrock".some)

    assertEquals(handling.getTextFromResponse(response), Some("Hello from Bedrock"))
  }

  test("getFunctionCalls extracts tool use blocks") {
    val toolUse  = ToolUseBlock
      .builder()
      .toolUseId("call-123")
      .name("getWeather")
      .input(Codecs.structToDocument(Struct("city" -> Value.string("London"))))
      .build()
    val response = responseWith(toolUses = List(toolUse), stopReason = StopReason.TOOL_USE)

    val calls = handling.getFunctionCalls(response).toOption.get
    assertEquals(calls.size, 1)
    assertEquals(calls.head.name, "getWeather")
    assertEquals(calls.head.args, Struct("city" -> Value.string("London")))
    assertEquals(calls.head.callId, Some("call-123"))
  }

  test("finishReason maps Bedrock stop reasons") {
    val cases = List(
      StopReason.END_TURN                      -> FinishReason.Stop,
      StopReason.STOP_SEQUENCE                 -> FinishReason.Stop,
      StopReason.TOOL_USE                      -> FinishReason.ToolCalls,
      StopReason.MAX_TOKENS                    -> FinishReason.Length,
      StopReason.CONTENT_FILTERED              -> FinishReason.ContentFilter,
      StopReason.GUARDRAIL_INTERVENED          -> FinishReason.ContentFilter,
      StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> FinishReason.Error
    )

    cases.foreach { case (bedrockReason, expected) =>
      assertEquals(handling.finishReason(responseWith(stopReason = bedrockReason)), expected)
    }
  }

  test("structuredOutput parses JSON text into Struct") {
    val response = responseWith(text = """{"city":"London","temperature":18}""".some)

    val struct = handling.structuredOutput(response).toOption.get
    assertEquals(struct.fields("city"), Value.string("London"))
    assertEquals(struct.fields("temperature"), Value.number(18))
  }

  test("getImages extracts image blocks") {
    val bytes = Array[Byte](1, 2, 3, 4)
    val image = ImageBlock
      .builder()
      .format(ImageFormat.PNG)
      .source(ImageSource.builder().bytes(SdkBytes.fromByteArray(bytes)).build())
      .build()
    val response = responseWith(images = List(image))

    for
      images   <- assets.getImages(response)
      rawBytes <- images.head.bytes.compile.toList
    yield
      assertEquals(images.size, 1)
      assertEquals(images.head.mimeType, "image/png")
      assertEquals(images.head.extension, "png")
      assertEquals(rawBytes, bytes.toList)
  }

  test("getImages fails when image source has no bytes") {
    val image = ImageBlock
      .builder()
      .format(ImageFormat.JPEG)
      .source(ImageSource.builder().build())
      .build()
    val response = responseWith(images = List(image))

    assets.getImages(response).attempt.map { result =>
      assertEquals(result, Bedrock.Error.UnsupportedImageSource.asLeft)
    }
  }
