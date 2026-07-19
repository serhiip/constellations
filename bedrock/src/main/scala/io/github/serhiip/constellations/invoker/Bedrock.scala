package io.github.serhiip.constellations.invoker

import cats.data.NonEmptyChain as NEC
import io.circe.syntax.*
import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.bedrock.{Client, Codecs}
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Codecs.given
import scala.jdk.CollectionConverters.*
import scala.util.chaining.*
import java.util.Base64
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.model.{
  ContentBlock,
  ConversationRole,
  ConverseRequest,
  ConverseResponse,
  ImageBlock,
  ImageFormat,
  ImageSource,
  InferenceConfiguration,
  Message as BedrockMessage,
  SystemContentBlock,
  Tool,
  ToolConfiguration,
  ToolInputSchema,
  ToolSpecification
}

object Bedrock:

  final case class Config(
      model: String,
      temperature: Option[Float] = None,
      maxTokens: Option[Int] = None,
      topP: Option[Float] = None,
      systemPrompt: Option[String] = None
  )

  def chatCompletion[F[_]](
      client: Client[F],
      config: Config,
      functionDeclarations: List[FunctionDeclaration] = List.empty
  ): Invoker[F, ConverseResponse] = new:

    def generate(history: NEC[Message]): F[ConverseResponse] =
      val allMessages            = history.toChain.toList
      val (systemMsgs, turnMsgs) = allMessages.partition { case Message.System(_) => true; case _ => false }
      val systemBlocks           =
        config.systemPrompt.toList.map(SystemContentBlock.fromText) ++
          systemMsgs.collect { case Message.System(text) => SystemContentBlock.fromText(text) }
      val messages               = turnMsgs.map(messageToBedrockMessage)
      val toolConfig             = Option.when(functionDeclarations.nonEmpty)(buildToolConfig(functionDeclarations))
      val inference              = InferenceConfiguration
        .builder()
        .tap: b =>
          config.temperature.foreach(t => b.temperature(t))
          config.topP.foreach(p => b.topP(p))
          config.maxTokens.foreach(m => b.maxTokens(m))
        .build()
      val request                = ConverseRequest
        .builder()
        .modelId(config.model)
        .messages(messages.asJava)
        .inferenceConfig(inference)
        .tap: b =>
          if systemBlocks.nonEmpty then b.system(systemBlocks.asJava): Unit
        .tap: b =>
          toolConfig.foreach(b.toolConfig(_)): Unit
        .build()
      client.converse(request)

    private def buildToolConfig(declarations: List[FunctionDeclaration]): ToolConfiguration =
      val tools = declarations.map: fd =>
        val spec = ToolSpecification
          .builder()
          .name(fd.name)
          .tap(b => fd.description.foreach(b.description(_)))
          .tap: b =>
            fd.parameters.foreach: params =>
              b.inputSchema(ToolInputSchema.builder().json(Document.fromString(params.asJson.noSpaces)).build())
          .build()
        Tool.builder().toolSpec(spec).build()
      ToolConfiguration.builder().tools(tools.asJava).build()

    private def messageToBedrockMessage(message: Message): BedrockMessage = message match
      case Message.User(parts)             =>
        val blocks = parts.map(contentPartToBlock)
        BedrockMessage.builder().role(ConversationRole.USER).content(blocks.map(_.toBuilder().build()).asJava).build()
      case Message.Assistant(text, images) =>
        val textBlocks  = text.map(ContentBlock.fromText).toList
        val imageBlocks = images.map: img =>
          ContentBlock.fromImage(
            ImageBlock
              .builder()
              .format(ImageFormat.JPEG)
              .source(ImageSource.builder().bytes(SdkBytes.fromByteArray(Base64.getDecoder.decode(img.base64Encoded))).build())
              .build()
          )
        BedrockMessage
          .builder()
          .role(ConversationRole.ASSISTANT)
          .content((textBlocks ::: imageBlocks).map(_.toBuilder().build()).asJava)
          .build()
      case Message.System(text)            =>
        BedrockMessage.builder().role(ConversationRole.USER).content(ContentBlock.fromText(text)).build()
      case Message.Tool(fc)                =>
        val block = ContentBlock.fromToolUse(
          software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock
            .builder()
            .toolUseId(fc.callId.getOrElse(fc.name))
            .name(fc.name)
            .input(Codecs.structToDocument(fc.args))
            .build()
        )
        BedrockMessage.builder().role(ConversationRole.ASSISTANT).content(block).build()
      case Message.ToolResult(fr)          =>
        val block = ContentBlock.fromToolResult(
          software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock
            .builder()
            .toolUseId(fr.call.callId.getOrElse(fr.call.name))
            .content(
              software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock
                .fromJson(Codecs.structToDocument(fr.response))
            )
            .build()
        )
        BedrockMessage.builder().role(ConversationRole.USER).content(block).build()

    private def contentPartToBlock(part: ContentPart): ContentBlock = part match
      case ContentPart.Text(text)           => ContentBlock.fromText(text)
      case ContentPart.Image(base64Encoded) =>
        ContentBlock.fromImage(
          ImageBlock
            .builder()
            .format(ImageFormat.JPEG)
            .source(ImageSource.builder().bytes(SdkBytes.fromByteArray(Base64.getDecoder.decode(base64Encoded))).build())
            .build()
        )
