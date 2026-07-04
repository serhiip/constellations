package io.github.serhiip.constellations.invoker

import cats.data.NonEmptyChain as NEC
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.bedrock.Client
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.services.bedrockruntime.model.{ConverseRequest, ConverseResponse, ConversationRole, StopReason}

final class BedrockTest extends CatsEffectSuite:

  private final class RecordingClient(requestRef: Ref[IO, Option[ConverseRequest]]) extends Client[IO]:
    def converse(request: ConverseRequest): IO[ConverseResponse] =
      requestRef.set(request.some) *>
        IO.pure(
          ConverseResponse
            .builder()
            .stopReason(StopReason.END_TURN)
            .build()
        )

  test("chatCompletion should shape ConverseRequest with system prompt, messages, and tools") {
    val weatherTool = FunctionDeclaration(
      name = "getWeather",
      description = "Get weather for a city".some,
      parameters = Schema
        .obj(
          properties = Map("city" -> Schema.string(description = "City name".some)),
          required = List("city")
        )
        .some
    )

    val history = NEC.of(
      Message.System("You are a helpful assistant."),
      Message.User(List(ContentPart.Text("What is the weather in London?"))),
      Message.Tool(FunctionCall("getWeather", Struct("city" -> Value.string("London")), "call-1".some)),
      Message.ToolResult(FunctionResponse("getWeather", Struct("temperature" -> Value.string("18C")), "call-1".some)),
      Message.Assistant("It is 18C in London.".some)
    )

    for
      requestRef <- Ref.of[IO, Option[ConverseRequest]](None)
      client      = RecordingClient(requestRef)
      invoker     = Bedrock.chatCompletion(
                      client,
                      Bedrock.Config(
                        model = "amazon.nova-lite-v1:0",
                        temperature = 0.2f.some,
                        maxTokens = 256.some,
                        topP = 0.9f.some,
                        systemPrompt = "Answer briefly.".some
                      ),
                      functionDeclarations = List(weatherTool)
                    )
      _          <- invoker.generate(history)
      request    <- requestRef.get.flatMap(_.liftTo[IO](RuntimeException("Missing recorded request")))
    yield
      assertEquals(request.modelId(), "amazon.nova-lite-v1:0")
      assertEquals(request.inferenceConfig().temperature(), java.lang.Float.valueOf(0.2f))
      assertEquals(request.inferenceConfig().maxTokens(), java.lang.Integer.valueOf(256))
      assertEquals(request.inferenceConfig().topP(), java.lang.Float.valueOf(0.9f))

      val systemTexts = request.system().asScala.map(_.text()).toList
      assertEquals(systemTexts, List("Answer briefly.", "You are a helpful assistant."))

      val messages = request.messages().asScala.toList
      assertEquals(messages.size, 4)
      assertEquals(messages.head.role(), ConversationRole.USER)
      assertEquals(messages.head.content().get(0).text(), "What is the weather in London?")
      assertEquals(messages(1).role(), ConversationRole.ASSISTANT)
      assertEquals(messages(1).content().get(0).toolUse().name(), "getWeather")
      assertEquals(messages(2).role(), ConversationRole.USER)
      assertEquals(messages(2).content().get(0).toolResult().toolUseId(), "call-1")
      assertEquals(messages(3).role(), ConversationRole.ASSISTANT)
      assertEquals(messages(3).content().get(0).text(), "It is 18C in London.")

      val tools = request.toolConfig().tools().asScala.toList
      assertEquals(tools.size, 1)
      assertEquals(tools.head.toolSpec().name(), "getWeather")
      assertEquals(tools.head.toolSpec().description(), "Get weather for a city")
  }
