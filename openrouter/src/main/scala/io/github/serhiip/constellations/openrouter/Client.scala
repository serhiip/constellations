package io.github.serhiip.constellations.openrouter

import scala.concurrent.duration.*

import cats.effect.std.Supervisor
import cats.effect.syntax.resource.*
import cats.effect.{Async, Clock, Concurrent, Resource}
import cats.syntax.all.*
import cats.{Applicative, Functor, Semigroupal, Show}
import cats.~>

import org.typelevel.ci.CIString
import org.typelevel.log4cats.{Logger, LoggerFactory, StructuredLogger}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Counter, Meter}
import org.typelevel.otel4s.trace.{SpanContext, StatusCode, Tracer}

import io.github.serhiip.constellations.common.Observability
import io.github.serhiip.constellations.common.Observability.*
import fs2.io.net.Network
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.syntax.*
import io.circe.{Codec, Decoder, Encoder, Json}
import org.http4s.*
import org.http4s.Method.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client as HTTPClient
import org.http4s.client.middleware.{Metrics as Http4sMetrics, Retry, RetryPolicy}
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.{Authorization, Referer, `Content-Type`}
import org.http4s.metrics.{MetricsOps, TerminationType}
import org.http4s.metrics.TerminationType.{Abnormal, Canceled, Timeout}
import org.http4s.client.middleware.Logger as Http4sLogger
import org.typelevel.otel4s.metrics.BucketBoundaries

private given Configuration = Configuration.default.withSnakeCaseMemberNames

enum Modality:
  case Text, Image, Audio, Video, File
  case Unknown(value: String)

object Modality:
  private val knownByString: Map[String, Modality] =
    Map("text" -> Text, "image" -> Image, "audio" -> Audio, "video" -> Video, "file" -> File)

  private val stringByKnown: Map[Modality, String] =
    knownByString.map(_.swap)

  given Codec[Modality] = Codec.from(
    Decoder[String].map(s => knownByString.getOrElse(s, Unknown(s))),
    Encoder[String].contramap {
      case Unknown(v) => v
      case other      => stringByKnown(other)
    }
  )

enum SupportedParameter:
  case FrequencyPenalty, IncludeReasoning, LogitBias, Logprobs, MaxTokens, MinP, PresencePenalty, Reasoning,
    RepetitionPenalty, ResponseFormat, Seed, Stop, StructuredOutputs, Temperature, ToolChoice, Tools, TopA, TopK,
    TopLogprobs, TopP, Verbosity, WebSearchOptions
  case Unknown(value: String)

object SupportedParameter:
  private val knownByString: Map[String, SupportedParameter] = Map(
    "frequency_penalty"  -> FrequencyPenalty,
    "include_reasoning"  -> IncludeReasoning,
    "logit_bias"         -> LogitBias,
    "logprobs"           -> Logprobs,
    "max_tokens"         -> MaxTokens,
    "min_p"              -> MinP,
    "presence_penalty"   -> PresencePenalty,
    "reasoning"          -> Reasoning,
    "repetition_penalty" -> RepetitionPenalty,
    "response_format"    -> ResponseFormat,
    "seed"               -> Seed,
    "stop"               -> Stop,
    "structured_outputs" -> StructuredOutputs,
    "temperature"        -> Temperature,
    "tool_choice"        -> ToolChoice,
    "tools"              -> Tools,
    "top_a"              -> TopA,
    "top_k"              -> TopK,
    "top_logprobs"       -> TopLogprobs,
    "top_p"              -> TopP,
    "verbosity"          -> Verbosity,
    "web_search_options" -> WebSearchOptions
  )

  private val stringByKnown: Map[SupportedParameter, String] =
    knownByString.map(_.swap)

  given Codec[SupportedParameter] = Codec.from(
    Decoder[String].map(s => knownByString.getOrElse(s, Unknown(s))),
    Encoder[String].contramap {
      case Unknown(v) => v
      case other      => stringByKnown(other)
    }
  )

enum Tokenizer:
  case Claude, Cohere, DeepSeek, GPT, Gemini, Grok, Llama2, Llama3, Llama4, Mistral, Nova, Qwen, Qwen3, Router, Other
  case Unknown(value: String)

object Tokenizer:
  private val knownByString: Map[String, Tokenizer] = Map(
    "Claude"   -> Claude,
    "Cohere"   -> Cohere,
    "DeepSeek" -> DeepSeek,
    "GPT"      -> GPT,
    "Gemini"   -> Gemini,
    "Grok"     -> Grok,
    "Llama2"   -> Llama2,
    "Llama3"   -> Llama3,
    "Llama4"   -> Llama4,
    "Mistral"  -> Mistral,
    "Nova"     -> Nova,
    "Qwen"     -> Qwen,
    "Qwen3"    -> Qwen3,
    "Router"   -> Router,
    "Other"    -> Other
  )

  private val stringByKnown: Map[Tokenizer, String] =
    knownByString.map(_.swap)

  given Codec[Tokenizer] = Codec.from(
    Decoder[String].map(s => knownByString.getOrElse(s, Unknown(s))),
    Encoder[String].contramap {
      case Unknown(v) => v
      case other      => stringByKnown(other)
    }
  )

case class ErrorResponse(error: ErrorDetails) derives ConfiguredCodec
case class ErrorDetails(code: Int, message: String, metadata: Option[Map[String, Json]] = None) derives ConfiguredCodec

case class TextContent(`type`: String = "text", text: String) derives ConfiguredCodec
case class ImageUrlContent(`type`: String = "image_url", imageUrl: ImageUrl) derives ConfiguredCodec
case class FileContent(`type`: String = "file", file: FileData) derives ConfiguredCodec
case class ImageUrl(url: String) derives ConfiguredCodec
case class FileData(filename: String, fileData: String) derives ConfiguredCodec

case class ToolFunction(
    name: String,
    description: Option[String] = None,
    parameters: Json
) derives ConfiguredCodec

case class Tool(
    `type`: String = "function",
    function: ToolFunction
) derives ConfiguredCodec

case class ToolCall(
    id: String,
    `type`: String = "function",
    function: ToolCallFunction
) derives ConfiguredCodec

case class ToolCallFunction(
    name: String,
    arguments: String
) derives ConfiguredCodec

case class ChatMessage(
    role: String,
    content: Option[Json] = None,
    toolCalls: Option[List[ToolCall]] = None,
    toolCallId: Option[String] = None,
    name: Option[String] = None,
    images: Option[List[ImageUrlContent]] = None,
    reasoning: Option[String] = None
) derives ConfiguredCodec:

  override def toString: String =
    val imagesInfo = images.fold("None")(imgs => s"Some(count=${imgs.size})")
    s"ChatMessage($role,$content,$toolCalls,$toolCallId,$name,$imagesInfo,$reasoning)"

given Codec[ChatMessage] =
  val c = ConfiguredCodec.derived[ChatMessage]
  Codec.from(c, c.mapJson(_.deepDropNullValues))

object ChatMessage:
  def system(content: String): ChatMessage =
    ChatMessage("system", content = Some(Json.fromString(content)))

  def user(content: String): ChatMessage =
    ChatMessage("user", content = Some(Json.fromString(content)))

  def user(content: Json): ChatMessage =
    ChatMessage("user", content = Some(content))

  def assistant(content: String): ChatMessage =
    ChatMessage("assistant", content = Some(Json.fromString(content)))

  def assistant(content: Json): ChatMessage =
    ChatMessage("assistant", content = Some(content))

  def assistantWithToolCalls(toolCalls: List[ToolCall]): ChatMessage =
    ChatMessage("assistant", toolCalls = Some(toolCalls))

  def tool(toolCallId: String, name: String, content: String): ChatMessage =
    ChatMessage("tool", content = Some(Json.fromString(content)), toolCallId = Some(toolCallId), name = Some(name))

  def tool(toolCallId: String, name: String, content: Json): ChatMessage =
    ChatMessage("tool", content = Some(content), toolCallId = Some(toolCallId), name = Some(name))

case class ChatCompletionRequest(
    model: String,
    messages: List[ChatMessage],
    temperature: Option[Double] = None,
    maxTokens: Option[Int] = None,
    topP: Option[Double] = None,
    stream: Boolean = false,
    presencePenalty: Option[Double] = None,
    frequencyPenalty: Option[Double] = None,
    logitBias: Option[Map[String, Double]] = None,
    tools: Option[List[Tool]] = None,
    toolChoice: Option[Json] = None,
    modalities: Option[List[String]] = None
) derives ConfiguredCodec

given Codec[ChatCompletionRequest] =
  val c = ConfiguredCodec.derived[ChatCompletionRequest]
  Codec.from(c, c.mapJson(_.deepDropNullValues))

case class ChatCompletionChoice(index: Int, message: ChatMessage, finishReason: Option[String]) derives ConfiguredCodec

case class ChatCompletionUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int) derives ConfiguredCodec

case class ChatCompletionResponse(
    id: String,
    `object`: String,
    created: Long,
    model: String,
    choices: List[ChatCompletionChoice],
    usage: ChatCompletionUsage
) derives ConfiguredCodec

case class ModelDefaultParameters(
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    frequencyPenalty: Option[Double] = None,
    presencePenalty: Option[Double] = None
) derives ConfiguredCodec

case class ModelPricing(
    prompt: BigDecimal,
    completion: BigDecimal,
    image: Option[BigDecimal] = None,
    audio: Option[BigDecimal] = None,
    request: Option[BigDecimal] = None,
    webSearch: Option[BigDecimal] = None,
    internalReasoning: Option[BigDecimal] = None,
    inputCacheRead: Option[BigDecimal] = None,
    inputCacheWrite: Option[BigDecimal] = None
)

object ModelPricing:
  private given Decoder[BigDecimal] =
    Decoder.decodeBigDecimal.or(
      Decoder.decodeString.emap(s => Either.catchOnly[NumberFormatException](BigDecimal(s)).leftMap(_.getMessage))
    )

  given Codec[ModelPricing] = ConfiguredCodec.derived

case class ModelArchitecture(
    modality: Option[String] = None,
    inputModalities: Set[Modality],
    outputModalities: Set[Modality],
    tokenizer: Option[Tokenizer] = None,
    instructType: Option[String] = None
) derives ConfiguredCodec

case class ModelTopProvider(
    isModerated: Boolean,
    contextLength: Option[Int] = None,
    maxCompletionTokens: Option[Int] = None
) derives ConfiguredCodec

case class PerRequestLimits(
    promptTokens: Option[String] = None,
    completionTokens: Option[String] = None,
    requestTokens: Option[String] = None
) derives ConfiguredCodec

case class Model(
    id: String,
    name: String,
    created: Option[Long] = None,
    description: Option[String] = None,
    architecture: Option[ModelArchitecture] = None,
    topProvider: Option[ModelTopProvider] = None,
    pricing: Option[ModelPricing] = None,
    canonicalSlug: Option[String] = None,
    contextLength: Option[Int] = None,
    huggingFaceId: Option[String] = None,
    perRequestLimits: Option[PerRequestLimits] = None,
    supportedParameters: Option[List[SupportedParameter]] = None,
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    frequencyPenalty: Option[Double] = None,
    defaultParameters: Option[ModelDefaultParameters] = None
) derives ConfiguredCodec:
  def supportsTools: Boolean = supportedParameters.exists(_.contains(SupportedParameter.Tools))

case class ModelsResponse(data: List[Model]) derives ConfiguredCodec

case class CompletionRequest(
    model: String,
    prompt: String,
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    stream: Boolean = false,
    maxTokens: Option[Int] = None,
    presencePenalty: Option[Double] = None,
    frequencyPenalty: Option[Double] = None,
    logitBias: Option[Map[String, Double]] = None
) derives ConfiguredCodec

case class CompletionChoice(
    text: String,
    index: Option[Int],
    logprobs: Option[Json] = None,
    finishReason: Option[String] = None
) derives ConfiguredCodec

case class CompletionResponse(
    id: String,
    `object`: String,
    created: Long,
    model: String,
    choices: List[CompletionChoice],
    usage: ChatCompletionUsage
) derives ConfiguredCodec

case class EmbeddingsRequest(model: String, input: Json) derives ConfiguredCodec

given Codec[EmbeddingsRequest] =
  val c = ConfiguredCodec.derived[EmbeddingsRequest]
  Codec.from(c, c.mapJson(_.deepDropNullValues))

case class EmbeddingData(
    `object`: String,
    embedding: List[Float],
    index: Int
)

object EmbeddingData:
  given Codec[EmbeddingData] = Codec.from(
    Decoder.instance { c =>
      for
        obj <- c.downField("object").as[String]
        emb <- c.downField("embedding").as[List[Double]].map(_.map(_.toFloat))
        idx <- c.downField("index").as[Int]
      yield EmbeddingData(obj, emb, idx)
    },
    Encoder.instance { d =>
      Json.obj(
        "object"    -> Json.fromString(d.`object`),
        "embedding" -> Json.arr(d.embedding.map(f => Json.fromFloat(f).getOrElse(Json.Null))*),
        "index"     -> Json.fromInt(d.index)
      )
    }
  )

case class EmbeddingsUsage(promptTokens: Int, totalTokens: Int) derives ConfiguredCodec

case class EmbeddingsResponse(
    `object`: String,
    data: List[EmbeddingData],
    model: String,
    usage: EmbeddingsUsage
) derives ConfiguredCodec

case class GenerationStats(
    id: String,
    totalCost: Double,
    createdAt: String,
    model: String,
    origin: String,
    usage: Double,
    isByok: Boolean,
    upstreamId: Option[String] = None,
    cacheDiscount: Option[Double] = None,
    upstreamInferenceCost: Double,
    appId: Option[Int] = None,
    externalUser: Option[String] = None,
    streamed: Boolean,
    cancelled: Boolean,
    providerName: String,
    latency: Int,
    moderationLatency: Option[Int] = None,
    generationTime: Int,
    finishReason: String,
    nativeFinishReason: String,
    tokensPrompt: Int,
    tokensCompletion: Int,
    nativeTokensPrompt: Int,
    nativeTokensCompletion: Int,
    nativeTokensReasoning: Int,
    nativeTokensCached: Int,
    numMediaPrompt: Option[Int] = None,
    numMediaCompletion: Option[Int] = None,
    numSearchResults: Option[Int] = None,
    apiType: Option[String] = None
) derives ConfiguredCodec

case class GenerationStatsResponse(
    data: GenerationStats
) derives ConfiguredCodec

object ToolChoice:
  def auto: Json                   = Json.fromString("auto")
  def none: Json                   = Json.fromString("none")
  def function(name: String): Json = Json.obj(
    "type"     -> Json.fromString("function"),
    "function" -> Json.obj("name" -> Json.fromString(name))
  )

object Tool:
  def function(name: String, description: Option[String] = None, parameters: Json): Tool =
    Tool("function", ToolFunction(name, description, parameters))

trait Client[F[_]]:
  def createChatCompletion(request: ChatCompletionRequest): F[ChatCompletionResponse]
  def createCompletion(request: CompletionRequest): F[CompletionResponse]
  def createEmbeddings(request: EmbeddingsRequest): F[EmbeddingsResponse]
  def listModels(): F[ModelsResponse]
  def getGenerationStats(generationId: String): F[GenerationStats]

object Client:
  case class Config(
      baseUri: Uri = Uri.unsafeFromString("https://openrouter.ai/api"),
      timeout: FiniteDuration = 30.seconds,
      idleConnectionTime: FiniteDuration = 60.seconds,
      retryMaxWait: FiniteDuration = 30.seconds,
      retryMaxAttempts: Int = 5,
      logHeaders: Boolean = false,
      logBody: Boolean = false,
      appUrl: Option[String] = None,
      appTitle: Option[String] = None
  )

  case class Meters[F[_]](
      requestTokenCounter: Counter[F, Long],
      responseTokenCounter: Counter[F, Long],
      requestCounter: Counter[F, Long],
      errorCounter: Counter[F, Long],
      // GenerationStats metrics
      totalCostCounter: Counter[F, Double],
      usageCounter: Counter[F, Double],
      cacheDiscountCounter: Counter[F, Double],
      upstreamInferenceCostCounter: Counter[F, Double],
      latencyCounter: Counter[F, Long],
      moderationLatencyCounter: Counter[F, Long],
      generationTimeCounter: Counter[F, Long],
      tokensPromptCounter: Counter[F, Long],
      tokensCompletionCounter: Counter[F, Long],
      nativeTokensPromptCounter: Counter[F, Long],
      nativeTokensCompletionCounter: Counter[F, Long],
      nativeTokensReasoningCounter: Counter[F, Long],
      nativeTokensCachedCounter: Counter[F, Long],
      numMediaPromptCounter: Counter[F, Long],
      numMediaCompletionCounter: Counter[F, Long],
      numSearchResultsCounter: Counter[F, Long]
  )

  object Meters:
    def apply[F[_]: Meter: Applicative]: F[Meters[F]] =
      val meter = Meter[F]
      (
        meter.counter[Long](Metrics.name("request_token_count")).create,
        meter.counter[Long](Metrics.name("response_token_count")).create,
        meter.counter[Long](Metrics.name("openrouter_request_count")).create,
        meter.counter[Long](Metrics.name("openrouter_error_count")).create,
        meter.counter[Double](Metrics.name("generation_total_cost")).create,
        meter.counter[Double](Metrics.name("generation_usage")).create,
        meter.counter[Double](Metrics.name("generation_cache_discount")).create,
        meter.counter[Double](Metrics.name("generation_upstream_inference_cost")).create,
        meter.counter[Long](Metrics.name("generation_latency")).create,
        meter.counter[Long](Metrics.name("generation_moderation_latency")).create,
        meter.counter[Long](Metrics.name("generation_time")).create,
        meter.counter[Long](Metrics.name("generation_tokens_prompt")).create,
        meter.counter[Long](Metrics.name("generation_tokens_completion")).create,
        meter.counter[Long](Metrics.name("generation_native_tokens_prompt")).create,
        meter.counter[Long](Metrics.name("generation_native_tokens_completion")).create,
        meter.counter[Long](Metrics.name("generation_native_tokens_reasoning")).create,
        meter.counter[Long](Metrics.name("generation_native_tokens_cached")).create,
        meter.counter[Long](Metrics.name("generation_num_media_prompt")).create,
        meter.counter[Long](Metrics.name("generation_num_media_completion")).create,
        meter.counter[Long](Metrics.name("generation_num_search_results")).create
      ).mapN(Meters.apply)

  enum Error extends RuntimeException:
    case BadRequest(message: String, errorDetails: ErrorDetails)
    case Unauthorized(message: String, errorDetails: ErrorDetails)
    case TooManyRequests(message: String, errorDetails: ErrorDetails)
    case InternalServerError(message: String, errorDetails: ErrorDetails)
    case UnknownError(statusCode: Int, message: String, errorDetails: ErrorDetails)

    override def getMessage(): String = this.show

  object Error:
    given Show[Error] = Show.show {
      case BadRequest(msg, details)           => s"Bad Request: $msg (code: ${details.code})"
      case Unauthorized(msg, details)         => s"Unauthorized: $msg (code: ${details.code})"
      case TooManyRequests(msg, details)      => s"Too Many Requests: $msg (code: ${details.code})"
      case InternalServerError(msg, details)  => s"Internal Server Error: $msg (code: ${details.code})"
      case UnknownError(status, msg, details) => s"Unknown Error (status $status): $msg (code: ${details.code})"
    }

  private class Core[F[_]: Concurrent](client: HTTPClient[F], apiKey: String, config: Config) extends Client[F]:
    private val baseHeaders =
      val authHeaders    = Headers(Authorization(Credentials.Token(AuthScheme.Bearer, apiKey)), `Content-Type`(MediaType.application.json))
      val refererHeaders = Headers(config.appUrl.map(url => Referer(Uri.unsafeFromString(url))).toList)
      val titleHeaders   = Headers(config.appTitle.map(title => Header.Raw(CIString("X-Title"), title)).toList)

      authHeaders ++ refererHeaders ++ titleHeaders

    def createChatCompletion(request: ChatCompletionRequest): F[ChatCompletionResponse] =
      val uri = config.baseUri / "v1" / "chat" / "completions"
      transformBody(request.asJson).flatMap(post[ChatCompletionResponse](uri, _, "chat completion"))

    def createCompletion(request: CompletionRequest): F[CompletionResponse] =
      val uri = config.baseUri / "v1" / "completions"
      transformBody(request.asJson).flatMap(post[CompletionResponse](uri, _, "completion"))

    def createEmbeddings(request: EmbeddingsRequest): F[EmbeddingsResponse] =
      val uri = config.baseUri / "v1" / "embeddings"
      client
        .run(Request[F](POST, uri).withHeaders(baseHeaders).withEntity(request))
        .use(decodeResponse[EmbeddingsResponse]("embeddings"))

    def listModels(): F[ModelsResponse] =
      client
        .run(Request[F](GET, config.baseUri / "v1" / "models").withHeaders(baseHeaders))
        .use(decodeResponse[ModelsResponse]("list models"))

    def getGenerationStats(generationId: String): F[GenerationStats] =
      val uri = config.baseUri / "v1" / "generation" +? ("id" -> generationId)
      client
        .run(Request[F](GET, uri).withHeaders(baseHeaders))
        .use(decodeResponse[GenerationStatsResponse]("get generation stats"))
        .map(_.data)

    protected def transformBody(body: Json): F[Json] =
      body.pure[F]

    protected def classifyError(operation: String, status: Status, details: ErrorDetails): F[Client.Error] =
      val _ = operation
      ((status, details.code) match
        case (Status.BadRequest, _) | (_, 400)          => Client.Error.BadRequest(details.message, details)
        case (Status.Unauthorized, _) | (_, 401)        => Client.Error.Unauthorized(details.message, details)
        case (Status.TooManyRequests, _) | (_, 429)     => Client.Error.TooManyRequests(details.message, details)
        case (Status.InternalServerError, _) | (_, 500) => Client.Error.InternalServerError(details.message, details)
        case _                                          => Client.Error.UnknownError(status.code, details.message, details)
      ).pure[F]

    private def post[A: Decoder](uri: Uri, body: Json, operation: String): F[A] =
      client
        .run(Request[F](POST, uri).withHeaders(baseHeaders).withEntity(body))
        .use(decodeResponse[A](operation))

    private def decodeResponse[A: Decoder](operation: String)(response: Response[F]): F[A] =
      if response.status.isSuccess then
        response.as[Json].flatMap { json =>
          json.hcursor.downField("error").as[ErrorDetails] match
            case Right(details) => raiseDomainError[A](operation, response.status, details)
            case Left(_)        =>
              json.as[A].leftMap(err => InvalidMessageBodyFailure(s"Invalid message body: ${err.getMessage}", err.some)).liftTo[F]
        }
      else response.as[ErrorResponse].flatMap(error => raiseDomainError[A](operation, response.status, error.error))

    private def raiseDomainError[A](operation: String, status: Status, details: ErrorDetails): F[A] =
      classifyError(operation, status, details).flatMap(_.raiseError[F, A])

  private class Observed[F[_]: Concurrent: StructuredLogger: Tracer](
      client: HTTPClient[F],
      apiKey: String,
      config: Config,
      statsSupervisor: Supervisor[F],
      meters: Meters[F],
      onGenerationStats: GenerationStatsHandler[F]
  ) extends Core[F](client, apiKey, config):

    private val generationStatsHandler =
      GenerationStatsHandler.combine(GenerationStatsHandler.metrics(meters), onGenerationStats)

    override def createChatCompletion(request: ChatCompletionRequest): F[ChatCompletionResponse] =
      val modelAttr     = Attribute("model", request.model)
      val operationAttr = Attribute("operation", "chat-completion")
      val attrs         = List(modelAttr, operationAttr)
      Tracer[F]
        .span("openrouter-client", "create-chat-completion")(attrs*)
        .logged: logger =>
          for
            _      <- logger.trace(s"Creating chat completion for model ${request.model}")
            _      <- meters.requestCounter.inc(attrs*)
            result <- super.createChatCompletion(request).onError {
                        case e: Client.Error => addResponseStatus(e).flatTap(_ => recordClientError(attrs, e))
                        case _               => meters.errorCounter.inc(attrs*)
                      }
            _      <- addResponseStatus(200)
            _      <- statsSupervisor.supervise(getGenerationStats(result.id))
            _      <- meters.requestTokenCounter.add(result.usage.promptTokens.toLong, attrs*)
            _      <- meters.responseTokenCounter.add(result.usage.completionTokens.toLong, attrs*)
            _      <-
              logger.trace(
                s"Chat completion created with ${result.choices.size} choices, used ${result.usage.totalTokens} tokens"
              )
          yield result

    override def createCompletion(request: CompletionRequest): F[CompletionResponse] =
      val modelAttr     = Attribute("model", request.model)
      val operationAttr = Attribute("operation", "completion")
      val attrs         = List(modelAttr, operationAttr)
      Tracer[F]
        .span("openrouter-client", "create-completion")(attrs*)
        .logged: logger =>
          for
            _      <- logger.trace(s"Creating completion for model ${request.model}")
            _      <- meters.requestCounter.inc(attrs*)
            result <- super.createCompletion(request).onError {
                        case e: Client.Error => addResponseStatus(e).flatTap(_ => recordClientError(attrs, e))
                        case _               => meters.errorCounter.inc(attrs*)
                      }
            _      <- addResponseStatus(200)
            _      <- statsSupervisor.supervise(getGenerationStats(result.id))
            _      <- meters.requestTokenCounter.add(result.usage.promptTokens.toLong, attrs*)
            _      <- meters.responseTokenCounter.add(result.usage.completionTokens.toLong, attrs*)
            _      <- logger.trace(
                        s"Completion created with ${result.choices.size} choices, used ${result.usage.totalTokens} tokens"
                      )
          yield result

    override def createEmbeddings(request: EmbeddingsRequest): F[EmbeddingsResponse] =
      val modelAttr     = Attribute("model", request.model)
      val operationAttr = Attribute("operation", "embeddings")
      val attrs         = List(modelAttr, operationAttr)
      Tracer[F]
        .span("openrouter-client", "create-embeddings")(attrs*)
        .logged: logger =>
          for
            _      <- logger.trace(s"Creating embeddings for model ${request.model}")
            _      <- meters.requestCounter.inc(attrs*)
            result <- super.createEmbeddings(request).onError {
                        case e: Client.Error => addResponseStatus(e).flatTap(_ => recordClientError(attrs, e))
                        case _               => meters.errorCounter.inc(attrs*)
                      }
            _      <- addResponseStatus(200)
            _      <- meters.requestTokenCounter.add(result.usage.promptTokens.toLong, attrs*)
            _      <- meters.responseTokenCounter.add(0L, attrs*)
            _      <- logger.trace(s"Embeddings created, used ${result.usage.totalTokens} tokens")
          yield result

    override def listModels(): F[ModelsResponse] =
      val operationAttr = Attribute("operation", "list-models")
      Tracer[F]
        .span("openrouter-client", "list-models")(operationAttr)
        .logged: logger =>
          for
            _      <- logger.trace("Listing available models")
            _      <- meters.requestCounter.inc(operationAttr)
            result <- super.listModels().onError {
                        case e: Client.Error => addResponseStatus(e).flatTap(_ => recordClientErrorListModels(List(operationAttr), e))
                        case _               => meters.errorCounter.inc(operationAttr)
                      }
            _      <- addResponseStatus(200)
            _      <- logger.trace(s"Found ${result.data.size} models")
          yield result

    override def getGenerationStats(generationId: String): F[GenerationStats] =
      for
        current <- Tracer[F].currentSpanOrNoop
        result  <- Tracer[F].rootScope(getGenerationStatsLinked(generationId, current.context))
      yield result

    override protected def transformBody(body: Json): F[Json] =
      Tracer[F].currentSpanOrNoop.map { span =>
        val ctx = span.context
        if ctx.isValid then
          body.deepMerge(Json.obj("trace" -> Json.obj("trace_id" -> ctx.traceIdHex.asJson, "parent_span_id" -> ctx.spanIdHex.asJson)))
        else body
      }

    override protected def classifyError(operation: String, status: Status, details: ErrorDetails): F[Client.Error] =
      super.classifyError(operation, status, details).flatTap { domainError =>
        val metadata = details.metadata.map(_.mkString(", ")).getOrElse("empty")
        Logger[F].error(domainError)(s"OpenRouter API error during $operation: metadata=$metadata")
      }

    private def getGenerationStatsLinked(generationId: String, linkTo: SpanContext): F[GenerationStats] =
      val operationAttr = Attribute("operation", "get-generation-stats")
      Tracer[F]
        .span("openrouter-client", "get-generation-stats")(operationAttr)
        .logged: logger =>
          for
            _            <- logger.trace(s"Getting generation stats for id $generationId")
            _            <- meters.requestCounter.inc(operationAttr)
            span         <- Tracer[F].currentSpanOrNoop
            result       <- super
                              .getGenerationStats(generationId)
                              .onError(t =>
                                meters.errorCounter.inc(operationAttr) >> addResponseStatus(t) >> span
                                  .setStatus(StatusCode.Error, t.getMessage) >> logger
                                  .warn(t)("Error while fetching generation stats") >> span.recordException(t)
                              )
            _            <- addResponseStatus(200)
            _            <- span.setStatus(StatusCode.Ok)
            _            <- logger.trace(s"Generation stats retrieved for model ${result.model}")
            enrichedAttrs = GenerationStatsHandler.generationStatsAttributes(result)
            _             = span.addLink(linkTo, enrichedAttrs*)
            _            <- span.addAttributes(enrichedAttrs*)
            _            <- generationStatsHandler.onGenerationStats(result)
          yield result

    private def recordClientError(attrs: Seq[Attribute[?]], error: Client.Error): F[Unit] =
      val errorCodeAttr = Attribute(
        "error_code",
        error match
          case Client.Error.BadRequest(_, details)          => details.code.toString
          case Client.Error.Unauthorized(_, details)        => details.code.toString
          case Client.Error.TooManyRequests(_, details)     => details.code.toString
          case Client.Error.InternalServerError(_, details) => details.code.toString
          case Client.Error.UnknownError(_, _, details)     => details.code.toString
      )
      meters.errorCounter.inc(attrs :+ errorCodeAttr)

    private def recordClientErrorListModels(attrs: Seq[Attribute[?]], error: Client.Error): F[Unit] =
      val errorCodeAttr = Attribute(
        "error_code",
        error match
          case Client.Error.BadRequest(_, details)          => details.code.toString
          case Client.Error.InternalServerError(_, details) => details.code.toString
          case Client.Error.UnknownError(_, _, details)     => details.code.toString
          case _                                            => "unknown"
      )
      meters.errorCounter.inc(attrs :+ errorCodeAttr)

    private def addResponseStatus(code: Int): F[Unit] =
      Tracer[F].currentSpanOrNoop.flatMap(_.addAttributes(Attribute("response_status_code", code.toLong)))

    private def addResponseStatus(error: Throwable): F[Unit] =
      error match
        case clientError: Client.Error => addResponseStatus(statusFromError(clientError))
        case _                         => addResponseStatus(Status.InternalServerError.code)

    private def statusFromError(error: Client.Error): Int =
      error match
        case Client.Error.BadRequest(_, details)          => details.code
        case Client.Error.Unauthorized(_, details)        => details.code
        case Client.Error.TooManyRequests(_, details)     => details.code
        case Client.Error.InternalServerError(_, details) => details.code
        case Client.Error.UnknownError(status, _, _)      => status

  private def createHttpClient[F[_]: Async: Network](config: Config): Resource[F, HTTPClient[F]] =
    EmberClientBuilder
      .default[F]
      .withTimeout(config.timeout)
      .withIdleConnectionTime(config.idleConnectionTime)
      .build

  private def defaultRetryPolicy[F[_]](config: Config): RetryPolicy[F] =
    RetryPolicy[F](
      backoff = RetryPolicy.exponentialBackoff(maxWait = config.retryMaxWait, maxRetry = config.retryMaxAttempts),
      retriable = {
        case (GET -> Root / "api" / "v1" / "generation", Right(Response(Status.NotFound, _, _, _, _))) => true
        case (_, result)                                                                               => result.isLeft
      }
    )

  private def configureClient[F[_]: Async](client: HTTPClient[F], config: Config, retryPolicy: RetryPolicy[F]): HTTPClient[F] =
    val retryClient = Retry[F](retryPolicy)(client)
    Http4sLogger.colored[F](logHeaders = config.logHeaders, logBody = config.logBody)(retryClient)

  private def buildHttpClient[F[_]: Async: Network: Meter](
      config: Config,
      name: String,
      retryPolicy: RetryPolicy[F]
  ): Resource[F, HTTPClient[F]] =
    for
      http         <- createHttpClient(config)
      instrumented <- instrumentHttp4sClient(name, http).toResource
    yield configureClient(instrumented, config, retryPolicy)

  object HTTP:
    def apply[F[_]: Async: Network: Meter](
        config: Config,
        name: String = "openrouter",
        retryPolicy: Option[RetryPolicy[F]] = None
    ): Resource[F, HTTPClient[F]] =
      buildHttpClient(config, name, retryPolicy.getOrElse(defaultRetryPolicy[F](config)))

  def core[F[_]: Concurrent](http: HTTPClient[F], apiKey: String, config: Config): Client[F] =
    new Core[F](http, apiKey, config)

  def observed[F[_]: Concurrent: StructuredLogger: Tracer](
      http: HTTPClient[F],
      apiKey: String,
      config: Config,
      statsSupervisor: Supervisor[F],
      meters: Meters[F]
  ): Client[F] =
    observed(http, apiKey, config, statsSupervisor, meters, GenerationStatsHandler.noop[F])

  def observed[F[_]: Concurrent: StructuredLogger: Tracer](
      http: HTTPClient[F],
      apiKey: String,
      config: Config,
      statsSupervisor: Supervisor[F],
      meters: Meters[F],
      onGenerationStats: GenerationStatsHandler[F]
  ): Client[F] =
    new Observed[F](http, apiKey, config, statsSupervisor, meters, onGenerationStats)

  def observed[F[_]: Async: Network: LoggerFactory: Tracer: Meter](
      apiKey: String,
      config: Config = Client.Config()
  ): Resource[F, Client[F]] =
    observed(apiKey, config, GenerationStatsHandler.noop[F])

  def observed[F[_]: Async: Network: LoggerFactory: Tracer: Meter](
      apiKey: String,
      config: Config,
      onGenerationStats: GenerationStatsHandler[F]
  ): Resource[F, Client[F]] =
    for
      http                      <- buildHttpClient(config, "openrouter", defaultRetryPolicy[F](config))
      given StructuredLogger[F] <- LoggerFactory[F].create.toResource
      statsSupervisor           <- Supervisor[F]
      meters                    <- Meters[F].toResource
    yield observed(http, apiKey, config, statsSupervisor, meters, onGenerationStats)

  def instrumentHttp4sClient[F[_]: Functor: Semigroupal: Concurrent: Clock: Meter](
      name: String,
      delegate: HTTPClient[F],
      excludeValues: String => Boolean = Function.const(false),
      histogramBoundaries: Option[BucketBoundaries] = None
  ): F[HTTPClient[F]] =
    val clientNameA           = Attribute("name", name)
    val classifierA           = Attribute("classifier", (_: String))
    val methodA               = (m: Method) => Attribute("method", m.name)
    val statusA               = (s: Status) => Attribute("status", s.code.toLong)
    val statusResonA          = (s: Status) => Attribute("reason", s.reason)
    val prefix                = Observability.Metrics.name("client")
    def metricName(n: String) = s"$prefix/$n"
    val boundaries            = histogramBoundaries.getOrElse(BucketBoundaries(Range(start = 0, end = 600, step = 50).map(_ * 1e09)*))

    (
      Meter[F]
        .upDownCounter[Long](metricName("active_requests"))
        .withDescription("The count of active requests in particular client")
        .create,
      Meter[F]
        .histogram[Long](metricName("call_duration"))
        .withDescription("The time to fully consume the response, including the body")
        .withExplicitBucketBoundaries(boundaries)
        .withUnit("ns")
        .create,
      Meter[F]
        .histogram[Long](metricName("header_time"))
        .withDescription("The time to receive the response headers")
        .withExplicitBucketBoundaries(boundaries)
        .withUnit("ns")
        .create,
      Meter[F]
        .histogram[Long](metricName("abnormal_terminations"))
        .withDescription("The time before request was abnormally terminated")
        .withUnit("ns")
        .withExplicitBucketBoundaries(boundaries)
        .create
    ).mapN: (activeRequests, totalTime, headerTime, abnormal) =>
      val metricsOps = new MetricsOps[F]:
        override def recordAbnormalTermination(elapsed: Long, terminationType: TerminationType, classifier: Option[String]): F[Unit] =
          val termination = terminationType match
            case Abnormal(rootCause)              => s"abnormal: ${rootCause.getMessage}"
            case Canceled                         => "cancelled"
            case TerminationType.Error(rootCause) => s"error: ${rootCause.getMessage()}"
            case Timeout                          => "timeout"

          abnormal.record(elapsed, Attribute("type", termination) :: clientNameA :: classifier.map(classifierA).toList)

        override def increaseActiveRequests(classifier: Option[String]): F[Unit] =
          activeRequests.inc(clientNameA :: classifier.map(classifierA).toList)

        override def decreaseActiveRequests(classifier: Option[String]): F[Unit] =
          activeRequests.dec(clientNameA :: classifier.map(classifierA).toList)

        override def recordTotalTime(
            method: Method,
            status: Status,
            elapsed: Long,
            classifier: Option[String]
        ): F[Unit] =
          totalTime.record(
            elapsed,
            statusResonA(status) :: statusA(status) :: methodA(method) :: clientNameA :: classifier.map(classifierA).toList
          )

        override def recordHeadersTime(method: Method, elapsed: Long, classifier: Option[String]): F[Unit] =
          headerTime.record(elapsed, methodA(method) :: clientNameA :: classifier.map(classifierA).toList)

      Http4sMetrics(metricsOps, MetricsOps.classifierFMethodWithOptionallyExcludedPath(exclude = excludeValues))(delegate)

  def mapK[F[_], G[_]](client: Client[F])(f: F ~> G): Client[G] = new Client[G]:
    def createChatCompletion(request: ChatCompletionRequest): G[ChatCompletionResponse] = f(client.createChatCompletion(request))
    def createCompletion(request: CompletionRequest): G[CompletionResponse]             = f(client.createCompletion(request))
    def createEmbeddings(request: EmbeddingsRequest): G[EmbeddingsResponse]             = f(client.createEmbeddings(request))
    def listModels(): G[ModelsResponse]                                                 = f(client.listModels())
    def getGenerationStats(generationId: String): G[GenerationStats]                    = f(client.getGenerationStats(generationId))
