package io.github.serhiip.constellations.invoker

import cats.data.NonEmptyChain as NEC
import cats.syntax.all.*

import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Codecs.given
import io.github.serhiip.constellations.google.*
import scala.jdk.CollectionConverters.*
import scala.util.chaining.*
import io.circe.syntax.*
import java.util.Base64

import com.google.genai.types.{
  Content,
  GenerateContentConfig,
  GenerateContentResponse,
  Part,
  Tool as GTool,
  FunctionDeclaration as GFunctionDeclaration,
  Schema as GSchema
}

import java.lang.{Boolean as JBoolean, Double as JDouble}

object GoogleGenAI:

  final case class Config(
      model: String,
      temperature: Option[Float] = None,
      maxTokens: Option[Int] = None,
      topP: Option[Float] = None,
      systemPrompt: Option[String] = None,
      responseSchema: Option[Schema] = None
  )

  def chatCompletion[F[_]](
      client: Client[F],
      config: Config,
      functionDeclarations: List[FunctionDeclaration] = List.empty
  ): Invoker[F, GenerateContentResponse] = new:

    def generate(history: NEC[Message], responseSchema: Option[Schema]) =
      val messages = history.map(messageToContent)

      val maybeTools =
        Option.when(functionDeclarations.nonEmpty) {
          List(GTool.builder().functionDeclarations(functionDeclarations.map(toGFunctionDeclaration).asJava).build())
        }

      val genCfg = GenerateContentConfig
        .builder()
        .tap(b => config.temperature.foreach(b.temperature(_)))
        .tap(b => config.topP.foreach(b.topP(_)))
        .tap(b => config.maxTokens.foreach(b.maxOutputTokens(_)))
        .tap(b => maybeTools.foreach(tools => b.tools(tools.asJava)))
        .tap(b =>
          responseSchema
            .orElse(config.responseSchema)
            .foreach(s => b.responseSchema(GSchema.fromJson(s.asJson.noSpaces)))
        )
        .tap(b => responseSchema.orElse(config.responseSchema).foreach(_ => b.responseMimeType("application/json")))
        .tap(b =>
          config.systemPrompt.foreach(sys => b.systemInstruction(Content.builder().role("system").parts(Part.fromText(sys)).build()))
        )
        .build()

      client.generate(config.model, messages, genCfg.some)

    private def messageToContent(message: Message): Content = message match
      case Message.User(parts)             =>
        val p = parts.map {
          case ContentPart.Text(t)           => Part.fromText(t)
          case ContentPart.Image(base64Data) => Part.fromBytes(Base64.getDecoder.decode(base64Data), "image/jpeg")
        }
        Content.builder().role("user").parts(p.map(_.toBuilder().build()).asJava).build()
      case Message.Assistant(text, images) =>
        val p = text.map(Part.fromText).toList ::: images.map { img =>
          Part.fromBytes(Base64.getDecoder.decode(img.base64Encoded), "image/jpeg")
        }
        Content.builder().role("model").parts(p.map(_.toBuilder().build()).asJava).build()
      case Message.System(text)            => Content.builder().role("system").parts(Part.fromText(text)).build()
      case Message.Tool(fc)                =>
        val args = fc.args.fields.view.mapValues(valueToJava).toMap.asJava
        val part = Part.fromFunctionCall(fc.name, args)
        Content.builder().role("user").parts(part).build()
      case Message.ToolResult(fr)          =>
        val args = fr.response.fields.view.mapValues(valueToJava).toMap.asJava
        val part = Part.fromFunctionResponse(fr.name, args)
        Content.builder().role("tool").parts(part).build()

    private def toGFunctionDeclaration(fd: FunctionDeclaration): GFunctionDeclaration =
      GFunctionDeclaration
        .builder()
        .name(fd.name)
        .tap(b => fd.description.foreach(b.description(_)))
        .tap(b => fd.parameters.foreach(p => b.parameters(GSchema.fromJson(p.asJson.noSpaces))))
        .build()

  def valueToJava(v: Value): Object = v match
    case Value.NullValue        => null
    case Value.NumberValue(d)   => JDouble.valueOf(d)
    case Value.StringValue(s)   => s
    case Value.BoolValue(b)     => JBoolean.valueOf(b)
    case Value.StructValue(str) => str.fields.view.mapValues(valueToJava).toMap.asJava
    case Value.ListValue(lst)   => lst.map(valueToJava).asJava
