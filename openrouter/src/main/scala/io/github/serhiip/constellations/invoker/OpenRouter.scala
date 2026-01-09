package io.github.serhiip.constellations.invoker

import cats.Monad
import cats.data.NonEmptyChain as NEC
import cats.syntax.all.*

import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Codecs.given
import io.github.serhiip.constellations.openrouter.{
  ChatCompletionRequest,
  ChatCompletionResponse,
  ChatMessage,
  Client,
  CompletionRequest,
  CompletionResponse,
  ImageUrl,
  ImageUrlContent,
  Tool,
  ToolCall,
  ToolCallFunction,
  ToolFunction
}
import io.circe.Json
import io.circe.syntax.*
import java.util.UUID

object OpenRouter:

  case class Config(
      model: String,
      temperature: Option[Double] = None,
      maxTokens: Option[Int] = None,
      topP: Option[Double] = None,
      presencePenalty: Option[Double] = None,
      frequencyPenalty: Option[Double] = None,
      systemPrompt: Option[String] = None,
      modalities: Option[List[String]] = None
  )

  def chatCompletion[F[_]: Monad](
      client: Client[F],
      config: Config,
      functionDeclarations: List[FunctionDeclaration] = List.empty,
      modelNameOverride: Option[F[String]] = None
  ): Invoker[F, ChatCompletionResponse] = new:

    override def generate(history: NEC[Message], responseSchema: Option[Schema]): F[ChatCompletionResponse] =
      val messages = history.toChain.toList.map(messageToChatMessage)

      val tools =
        if functionDeclarations.nonEmpty then
          Some(functionDeclarations.map { funcDecl =>
            Tool(
              `type` = "function",
              function = ToolFunction(
                name = funcDecl.name,
                description = funcDecl.description,
                parameters = funcDecl.parameters.map(_.asJson).getOrElse(Json.obj())
              )
            )
          })
        else None

      for
        modelName <- modelNameOverride.getOrElse(config.model.pure[F])

        request = ChatCompletionRequest(
                    model = config.model,
                    messages = config.systemPrompt.map(ChatMessage.system).toList ::: messages,
                    temperature = config.temperature,
                    maxTokens = config.maxTokens,
                    topP = config.topP,
                    presencePenalty = config.presencePenalty,
                    frequencyPenalty = config.frequencyPenalty,
                    tools = tools,
                    toolChoice = tools.map(_ => Json.fromString("auto")),
                    modalities = config.modalities
                  )
        result <- client.createChatCompletion(request)
      yield result

    private def messageToChatMessage(message: Message): ChatMessage =
      val gemini         = "google/gemini-.*".r
      val messageHandler = config.model match
        case gemini() => MessageHandler.gemini
        case _        => MessageHandler.default

      message match
        case Message.User(content)              => ChatMessage(role = "user", content = Some(messageHandler.convertUserMessage(content)))
        case Message.Assistant(content, images) =>
          ChatMessage(
            role = "assistant",
            content = content.map(Json.fromString),
            images = Option.when(images.nonEmpty)(
              images.map(img => ImageUrlContent(imageUrl = ImageUrl(s"data:image/jpeg;base64,${img.base64Encoded}")))
            )
          )
        case Message.System(content)            => ChatMessage(role = "system", content = Some(Json.fromString(content)))
        case Message.Tool(content)              =>
          val toolCall = ToolCall(
            id = content.callId.getOrElse(UUID.randomUUID().toString),
            `type` = "function",
            function = ToolCallFunction(
              name = content.name,
              arguments = content.args.asJson.noSpaces
            )
          )
          ChatMessage(role = "assistant", toolCalls = Some(List(toolCall)))
        case Message.ToolResult(content)        => messageHandler.convertToolResultMessage(content)

  def completion[F[_]](client: Client[F], config: Config): Invoker[F, CompletionResponse] = new:

    override def generate(history: NEC[Message], responseSchema: Option[Schema]): F[CompletionResponse] =
      val prompt  = history.toChain.toList.flatMap(messageToText(_).toList).mkString("\n")
      val request = CompletionRequest(
        model = config.model,
        prompt = prompt,
        temperature = config.temperature,
        maxTokens = config.maxTokens,
        topP = config.topP,
        presencePenalty = config.presencePenalty,
        frequencyPenalty = config.frequencyPenalty
      )
      client.createCompletion(request)

    private def messageToText(message: Message): Option[String] = message match
      case Message.User(content)              => content.collect { case ContentPart.Text(text) => text }.mkString(" ").some
      case Message.Assistant(content, images) => content
      case Message.System(content)            => content.some
      case Message.Tool(content)              => content.toString.some
      case Message.ToolResult(content)        => content.toString.some

protected trait MessageHandler:
  def convertUserMessage(content: List[ContentPart]): Json
  def convertToolResultMessage(content: FunctionResponse): ChatMessage

protected object MessageHandler:
  private def convertContentPartToJson(contentPart: ContentPart): Json = contentPart match
    case ContentPart.Text(text)           => Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(text))
    case ContentPart.Image(base64Encoded) =>
      val dataUrl = s"data:image/jpeg;base64,$base64Encoded"
      Json.obj(
        "type"      -> Json.fromString("image_url"),
        "image_url" -> Json.obj("url" -> Json.fromString(dataUrl))
      )

  def gemini: MessageHandler = new:
    def convertUserMessage(content: List[ContentPart]): Json =
      content match
        case List(ContentPart.Text(text)) => Json.fromString(text)
        case _                            => Json.arr(content.map(convertContentPartToJson)*)

    def convertToolResultMessage(content: FunctionResponse): ChatMessage =
      ChatMessage(
        role = "tool",
        content = Some(Json.fromString(content.response.asJson.noSpaces)),
        toolCallId = content.functionCallId
      )

  def default: MessageHandler = new:
    def convertUserMessage(content: List[ContentPart]): Json =
      Json.arr(content.map(convertContentPartToJson)*)

    def convertToolResultMessage(content: FunctionResponse): ChatMessage =
      ChatMessage(
        role = "tool",
        content = Some(content.response.asJson),
        toolCallId = content.functionCallId,
        name = Some(content.name)
      )
