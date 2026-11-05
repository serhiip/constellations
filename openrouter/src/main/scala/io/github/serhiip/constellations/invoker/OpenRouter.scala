package io.github.serhiip.constellations.invoker

import cats.Monad
import cats.data.NonEmptyChain as NEC
import cats.syntax.all.*

import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Codecs.given
import io.github.serhiip.constellations.openrouter.{Client, CompletionRequest, CompletionResponse}
import io.github.serhiip.constellations.openrouter.schema.additional.tools.item
import io.github.serhiip.constellations.openrouter.schema.chat.assistant.images.{Item, ItemImageUrl}
import io.github.serhiip.constellations.openrouter.schema.chat.content.image.ImageUrl
import io.github.serhiip.constellations.openrouter.schema.chat.content.items.{ChatContentImage, ChatContentItems, ChatContentText}
import io.github.serhiip.constellations.openrouter.schema.chat.function.tool.FunctionFunction
import io.github.serhiip.constellations.openrouter.schema.chat.messages.{
  ChatAssistantMessage,
  ChatMessages,
  ChatSystemMessage,
  ChatToolMessage,
  ChatUserMessage
}
import io.github.serhiip.constellations.openrouter.schema.chat.named.tool.choice.ChatNamedToolChoice
import io.github.serhiip.constellations.openrouter.schema.chat.request.{ChatRequest, ModalitiesItem}
import io.github.serhiip.constellations.openrouter.schema.chat.result.ChatResult
import io.github.serhiip.constellations.openrouter.schema.chat.server.tool.choice.ChatServerToolChoice
import io.github.serhiip.constellations.openrouter.schema.chat.tool.call
import io.github.serhiip.constellations.openrouter.schema.chat.tool.call.ChatToolCall
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
  ): Invoker[F, ChatResult] = new:

    override def generate(history: NEC[Message]): F[ChatResult] =
      val messages = history.toChain.toList.map(messageToChatMessage)
      val tools    = Option.when(functionDeclarations.nonEmpty)(functionDeclarations.map(toTool))

      for
        modelName <- modelNameOverride.getOrElse(config.model.pure[F])
        request    = ChatRequest(
                       model = modelName.some,
                       messages = config.systemPrompt.map(prompt => ChatSystemMessage(content = prompt): ChatMessages).toList ::: messages,
                       temperature = config.temperature,
                       max_tokens = config.maxTokens.map(_.toLong),
                       top_p = config.topP,
                       presence_penalty = config.presencePenalty,
                       frequency_penalty = config.frequencyPenalty,
                       tools = tools,
                       tool_choice = tools.map(_ => ("auto": ChatNamedToolChoice | ChatServerToolChoice | String)),
                       modalities = config.modalities.map(_.map(modality))
                     )
        result    <- client.createChatCompletion(request)
      yield result

    private def messageToChatMessage(message: Message): ChatMessages =
      val gemini         = "google/gemini-.*".r
      val openai         = "openai/.*".r
      val anthropic      = "anthropic/.*".r
      val messageHandler = config.model match
        case gemini()    => MessageHandler.gemini
        case openai()    => MessageHandler.openai
        case anthropic() => MessageHandler.openai
        case _           => MessageHandler.default

      message match
        case Message.User(content)              => messageHandler.convertUserMessage(content)
        case Message.Assistant(content, images) =>
          ChatAssistantMessage(
            content = content.map(text => (text: List[ChatContentItems] | String)),
            images = Option.when(images.nonEmpty)(
              images.map(img => Item(image_url = ItemImageUrl(s"data:image/jpeg;base64,${img.base64Encoded}")))
            )
          )
        case Message.System(content)            => ChatSystemMessage(content = content)
        case Message.Tool(content)              =>
          val toolCall = ChatToolCall(
            id = content.callId.getOrElse(UUID.randomUUID().toString),
            function = call.Function(
              name = content.name,
              arguments = content.args.asJson.noSpaces
            )
          )
          ChatAssistantMessage(tool_calls = List(toolCall).some)
        case Message.ToolResult(content)        => messageHandler.convertToolResultMessage(content)

  def completion[F[_]](client: Client[F], config: Config): Invoker[F, CompletionResponse] = new:

    override def generate(history: NEC[Message]): F[CompletionResponse] =
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

  private def toTool(funcDecl: FunctionDeclaration): item.Function =
    item.Function(
      function = FunctionFunction(
        name = funcDecl.name,
        description = funcDecl.description,
        parameters = funcDecl.parameters.flatMap(_.asJson.asObject).map(_.toMap)
      )
    )

  private def modality(value: String): ModalitiesItem =
    ModalitiesItem.known.find(_.value == value).getOrElse(ModalitiesItem.Unknown(value))

protected trait MessageHandler:
  def convertUserMessage(content: List[ContentPart]): ChatUserMessage
  def convertToolResultMessage(content: FunctionResponse): ChatToolMessage

protected object MessageHandler:
  private def contentParts(content: List[ContentPart]): List[ChatContentItems] = content.map {
    case ContentPart.Text(text)           => ChatContentText(text = text)
    case ContentPart.Image(base64Encoded) =>
      ChatContentImage(image_url = ImageUrl(url = s"data:image/jpeg;base64,$base64Encoded"))
  }

  private def toolResultContent(content: FunctionResponse): String =
    content.response.asJson.noSpaces

  def gemini: MessageHandler = new:
    def convertUserMessage(content: List[ContentPart]): ChatUserMessage =
      val body: List[ChatContentItems] | String = content match
        case List(ContentPart.Text(text)) => text
        case other                         => contentParts(other)
      ChatUserMessage(content = body)

    def convertToolResultMessage(content: FunctionResponse): ChatToolMessage =
      ChatToolMessage(
        content = toolResultContent(content),
        tool_call_id = content.call.callId.getOrElse(content.call.name)
      )

  def openai: MessageHandler = new:
    def convertUserMessage(content: List[ContentPart]): ChatUserMessage =
      ChatUserMessage(content = contentParts(content))

    def convertToolResultMessage(content: FunctionResponse): ChatToolMessage =
      ChatToolMessage(
        content = toolResultContent(content),
        tool_call_id = content.call.callId.getOrElse(content.call.name)
      )

  def default: MessageHandler = openai
