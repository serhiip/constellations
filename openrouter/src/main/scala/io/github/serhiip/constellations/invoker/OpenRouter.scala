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
      message match
        case Message.User(content)              =>
          val textParts  = content.collect { case ContentPart.Text(text) => text }
          val imageParts = content.collect { case ContentPart.Image(base64) => base64 }

          val images = Option.when(imageParts.nonEmpty)(
            imageParts.map { base64 =>
              ImageUrlContent(imageUrl = ImageUrl(s"data:image/png;base64,$base64"))
            }
          )

          val textJson = textParts.mkString(" ").some.filter(_.nonEmpty).map(Json.fromString)

          ChatMessage(role = "user", content = textJson, images = images)
        case Message.Assistant(content, images) =>
          val imageParts = images.map { img =>
            Json.obj(
              "type"      -> Json.fromString("image_url"),
              "image_url" -> Json.obj("url" -> Json.fromString(s"data:image/jpeg;base64,${img.base64Encoded}"))
            )
          }

          val textPart = content.map { text =>
            Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(text))
          }

          val contentJson =
            if images.nonEmpty then Some(Json.arr((textPart.toList ++ imageParts)*))
            else content.map(Json.fromString)

          ChatMessage(
            role = "assistant",
            content = contentJson
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
        case Message.ToolResult(content)        => MessageHandler.default.convertToolResultMessage(content)

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
  def convertToolResultMessage(content: FunctionResponse): ChatMessage

protected object MessageHandler:
  def convertContentPartToJson(contentPart: ContentPart): Json = contentPart match
    case ContentPart.Text(text)           => Json.obj("type" -> Json.fromString("text"), "text" -> Json.fromString(text))
    case ContentPart.Image(base64Encoded) =>
      val dataUrl = s"data:image/png;base64,$base64Encoded"
      Json.obj(
        "type"      -> Json.fromString("image_url"),
        "image_url" -> Json.obj("url" -> Json.fromString(dataUrl))
      )

  def userContent(parts: List[ContentPart]): Json =
    Json.arr(parts.map(convertContentPartToJson)*)

  def default: MessageHandler = new:
    def convertToolResultMessage(content: FunctionResponse): ChatMessage =
      ChatMessage(
        role = "tool",
        content = Some(content.response.asJson),
        toolCallId = content.functionCallId,
        name = Some(content.name)
      )
