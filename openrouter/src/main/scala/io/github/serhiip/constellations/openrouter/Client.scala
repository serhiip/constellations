package io.github.serhiip.constellations.openrouter

import scala.concurrent.duration.*

import cats.effect.std.Supervisor
import cats.effect.syntax.resource.*
import cats.effect.{Async, Clock, Concurrent, Resource}
import cats.syntax.all.*
import cats.{Functor, Semigroupal, Show}
import cats.~>

import org.typelevel.ci.CIString
import org.typelevel.log4cats.{Logger, LoggerFactory, StructuredLogger}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Counter, Meter}
import org.typelevel.otel4s.trace.{SpanContext, StatusCode, Tracer}

import io.github.serhiip.constellations.common.Observability
import io.github.serhiip.constellations.common.Observability.*
import fs2.io.net.Network
import io.circe.derivation.Configuration
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

case class ErrorResponse(error: ErrorDetails) derives Codec
case class ErrorDetails(code: Int, message: String, metadata: Option[Map[String, Json]] = None) derives Codec

case class TextContent(`type`: String = "text", text: String) derives Codec
case class ImageUrlContent(`type`: String = "image_url", imageUrl: ImageUrl) derives Codec
case class FileContent(`type`: String = "file", file: FileData) derives Codec
case class ImageUrl(url: String) derives Codec
case class FileData(filename: String, fileData: String) derives Codec

case class ToolFunction(
    name: String,
    description: Option[String] = None,
    parameters: Json
) derives Codec

case class Tool(
    `type`: String = "function",
    function: ToolFunction
) derives Codec

case class ToolCall(
    id: String,
    `type`: String = "function",
    function: ToolCallFunction
) derives Codec

case class ToolCallFunction(
    name: String,
    arguments: String
) derives Codec

case class ChatMessage(
    role: String,
    content: Option[Json] = None,
    toolCalls: Option[List[ToolCall]] = None,
    toolCallId: Option[String] = None,
    name: Option[String] = None
) derives Codec

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
    toolChoice: Option[Json] = None
) derives Codec

case class ChatCompletionChoice(index: Int, message: ChatMessage, finishReason: Option[String]) derives Codec

case class ChatCompletionUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int) derives Codec

case class ChatCompletionResponse(
    id: String,
    `object`: String,
    created: Long,
    model: String,
    choices: List[ChatCompletionChoice],
    usage: ChatCompletionUsage
) derives Codec

case class ModelDefaultParameters(
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    frequencyPenalty: Option[Double] = None,
    presencePenalty: Option[Double] = None
) derives Codec

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
    supportedParameters: Option[List[String]] = None,
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    frequencyPenalty: Option[Double] = None,
    defaultParameters: Option[ModelDefaultParameters] = None
) derives Codec

case class ModelPricing(
    prompt: String,
    completion: String,
    image: Option[String] = None,
    audio: Option[String] = None,
    request: Option[String] = None,
    webSearch: Option[String] = None,
    internalReasoning: Option[String] = None,
    inputCacheRead: Option[String] = None,
    inputCacheWrite: Option[String] = None
) derives Codec

case class ModelArchitecture(
    modality: Option[String] = None,
    inputModalities: List[String],
    outputModalities: List[String],
    tokenizer: Option[String] = None,
    instructType: Option[String] = None
) derives Codec

case class ModelTopProvider(
    isModerated: Boolean,
    contextLength: Option[Int] = None,
    maxCompletionTokens: Option[Int] = None
) derives Codec

case class PerRequestLimits(
    promptTokens: Option[String] = None,
    completionTokens: Option[String] = None,
    requestTokens: Option[String] = None
) derives Codec

case class ModelsResponse(data: List[Model]) derives Codec

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
) derives Codec

case class CompletionChoice(
    text: String,
    index: Option[Int],
    logprobs: Option[Json] = None,
    finishReason: Option[String] = None
) derives Codec

case class CompletionResponse(
    id: String,
    `object`: String,
    created: Long,
    model: String,
    choices: List[CompletionChoice],
    usage: ChatCompletionUsage
) derives Codec

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
) derives Codec

case class GenerationStatsResponse(
    data: GenerationStats
) derives Codec

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

  def apply[F[_]: Async: StructuredLogger](client: HTTPClient[F], apiKey: String, config: Config): Client[F] =
    create(client, apiKey, config)

  def observed[F[_]: Async: Tracer: StructuredLogger](
      delegate: Client[F],
      statsSupervisor: Supervisor[F],
      meters: Option[Meters[F]] = none
  ): Client[F] =
    new:
      def createChatCompletion(request: ChatCompletionRequest): F[ChatCompletionResponse] =
        Tracer[F]
          .span("openrouter-client", "create-chat-completion")
          .logged: logger =>
            val modelAttr     = Attribute("model", request.model)
            val operationAttr = Attribute("operation", "chat-completion")
            val attrs         = List(modelAttr, operationAttr)
            for
              _      <- logger.trace(s"Creating chat completion for model ${request.model}")
              _      <- meters.traverse_(_.requestCounter.inc(attrs*))
              result <- delegate.createChatCompletion(request).onError {
                          case e: Client.Error => recordClientError(attrs, e)
                          case _               => meters.traverse_(_.errorCounter.inc(attrs*))
                        }
              _      <- statsSupervisor.supervise(getGenerationStats(result.id))
              _      <- meters.traverse_(_.requestTokenCounter.add(result.usage.promptTokens.toLong, attrs*))
              _      <- meters.traverse_(_.responseTokenCounter.add(result.usage.completionTokens.toLong, attrs*))
              _      <-
                logger.trace(
                  s"Chat completion created with ${result.choices.size} choices, used ${result.usage.totalTokens} tokens"
                )
            yield result

      def createCompletion(request: CompletionRequest): F[CompletionResponse] =
        Tracer[F]
          .span("openrouter-client", "create-completion")
          .logged: logger =>
            val modelAttr     = Attribute("model", request.model)
            val operationAttr = Attribute("operation", "completion")
            val attrs         = List(modelAttr, operationAttr)
            for
              _      <- logger.trace(s"Creating completion for model ${request.model}")
              _      <- meters.traverse_(_.requestCounter.inc(attrs*))
              result <- delegate.createCompletion(request).onError {
                          case e: Client.Error => recordClientError(attrs, e)
                          case _               => meters.traverse_(_.errorCounter.inc(attrs*))
                        }
              _      <- statsSupervisor.supervise(getGenerationStats(result.id))
              _      <- meters.traverse_(_.requestTokenCounter.add(result.usage.promptTokens.toLong, attrs*))
              _      <- meters.traverse_(_.responseTokenCounter.add(result.usage.completionTokens.toLong, attrs*))
              _      <- logger.trace(
                          s"Completion created with ${result.choices.size} choices, used ${result.usage.totalTokens} tokens"
                        )
            yield result

      def listModels(): F[ModelsResponse] =
        Tracer[F]
          .span("openrouter-client", "list-models")
          .logged: logger =>
            val operationAttr = Attribute("operation", "list-models")
            val attrs         = List(operationAttr)
            for
              _      <- logger.trace("Listing available models")
              _      <- meters.traverse_(_.requestCounter.inc(attrs*))
              result <- delegate.listModels().onError {
                          case e: Client.Error => recordClientErrorListModels(attrs, e)
                          case _               => meters.traverse_(_.errorCounter.inc(attrs*))
                        }
              _      <- logger.trace(s"Found ${result.data.size} models")
            yield result

      def getGenerationStats(generationId: String): F[GenerationStats] =
        for
          current <- Tracer[F].currentSpanOrNoop
          result  <- Tracer[F].rootScope(getGenerationStatsLinked(generationId, current.context))
        yield result

      def getGenerationStatsLinked(generationId: String, linkTo: SpanContext): F[GenerationStats] =
        Tracer[F]
          .span("openrouter-client", "get-generation-stats")
          .logged: logger =>
            val operationAttr = Attribute("operation", "get-generation-stats")
            val attrs         = List(operationAttr)
            for
              _            <- logger.trace(s"Getting generation stats for id $generationId")
              _            <- meters.traverse_(_.requestCounter.inc(attrs*))
              span         <- Tracer[F].currentSpanOrNoop
              result       <- delegate
                                .getGenerationStats(generationId)
                                .onError(t =>
                                  meters.traverse_(_.errorCounter.inc(attrs*)) >> span
                                    .setStatus(StatusCode.Error, t.getMessage) >> logger
                                    .warn(t)("Error while fetching generation stats") >> span.recordException(t)
                                )
              _            <- span.setStatus(StatusCode.Ok)
              _            <- logger.trace(s"Generation stats retrieved for model ${result.model}")
              enrichedAttrs = attrs ++ List(
                                Attribute("model", result.model),
                                Attribute("origin", result.origin),
                                Attribute("is_byok", result.isByok.toString),
                                Attribute("streamed", result.streamed.toString),
                                Attribute("cancelled", result.cancelled.toString),
                                Attribute("provider_name", result.providerName),
                                Attribute("finish_reason", result.finishReason),
                                Attribute("native_finish_reason", result.nativeFinishReason)
                              ) ++ result.externalUser.map(user => Attribute("external_user", user)).toList ++
                                result.upstreamId.map(id => Attribute("upstream_id", id)).toList

              _  = span.addLink(linkTo, enrichedAttrs*)
              _ <- span.addAttributes(enrichedAttrs*)
              _ <- meters.traverse_(m => recordGenerationStatsMetrics(m, result, enrichedAttrs))
            yield result

      private def recordGenerationStatsMetrics(
          m: Meters[F],
          result: GenerationStats,
          attributes: Seq[Attribute[?]]
      ): F[Unit] =
        for
          _ <- m.totalCostCounter.add(result.totalCost, attributes*)
          _ <- m.usageCounter.add(result.usage, attributes*)
          _ <- result.cacheDiscount.traverse(d => m.cacheDiscountCounter.add(d, attributes*))
          _ <- m.upstreamInferenceCostCounter.add(result.upstreamInferenceCost, attributes*)
          _ <- m.latencyCounter.add(result.latency.toLong, attributes*)
          _ <- result.moderationLatency.traverse(l => m.moderationLatencyCounter.add(l.toLong, attributes*))
          _ <- m.generationTimeCounter.add(result.generationTime.toLong, attributes*)
          _ <- m.tokensPromptCounter.add(result.tokensPrompt.toLong, attributes*)
          _ <- m.tokensCompletionCounter.add(result.tokensCompletion.toLong, attributes*)
          _ <- m.nativeTokensPromptCounter.add(result.nativeTokensPrompt.toLong, attributes*)
          _ <- m.nativeTokensCompletionCounter.add(result.nativeTokensCompletion.toLong, attributes*)
          _ <- m.nativeTokensReasoningCounter.add(result.nativeTokensReasoning.toLong, attributes*)
          _ <- m.nativeTokensCachedCounter.add(result.nativeTokensCached.toLong, attributes*)
          _ <- result.numMediaPrompt.traverse(c => m.numMediaPromptCounter.add(c.toLong, attributes*))
          _ <- result.numMediaCompletion.traverse(c => m.numMediaCompletionCounter.add(c.toLong, attributes*))
          _ <- result.numSearchResults.traverse(c => m.numSearchResultsCounter.add(c.toLong, attributes*))
        yield ()

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
        meters.traverse_(_.errorCounter.inc(attrs :+ errorCodeAttr))

      private def recordClientErrorListModels(attrs: Seq[Attribute[?]], error: Client.Error): F[Unit] =
        val errorCodeAttr = Attribute(
          "error_code",
          error match
            case Client.Error.BadRequest(_, details)          => details.code.toString
            case Client.Error.InternalServerError(_, details) => details.code.toString
            case Client.Error.UnknownError(_, _, details)     => details.code.toString
            case _                                            => "unknown"
        )
        meters.traverse_(_.errorCounter.inc(attrs :+ errorCodeAttr))

  private def create[F[_]: Async: StructuredLogger](client: HTTPClient[F], apiKey: String, config: Config): Client[F] =
    new:
      private val baseHeaders =
        val authHeaders    = Headers(Authorization(Credentials.Token(AuthScheme.Bearer, apiKey)), `Content-Type`(MediaType.application.json))
        val refererHeaders = Headers(config.appUrl.map(url => Referer(Uri.unsafeFromString(url))).toList)
        val titleHeaders   = Headers(config.appTitle.map(title => Header.Raw(CIString("X-Title"), title)).toList)

        authHeaders ++ refererHeaders ++ titleHeaders

      private def handleError[A](operation: String)(response: Response[F]): F[Throwable] =
        for
          errorResponse <- response.as[ErrorResponse]
          domainError    = response.status match
                             case Status.BadRequest          => Error.BadRequest(errorResponse.error.message, errorResponse.error)
                             case Status.Unauthorized        => Error.Unauthorized(errorResponse.error.message, errorResponse.error)
                             case Status.TooManyRequests     => Error.TooManyRequests(errorResponse.error.message, errorResponse.error)
                             case Status.InternalServerError => Error.InternalServerError(errorResponse.error.message, errorResponse.error)
                             case status                     => Error.UnknownError(status.code, errorResponse.error.message, errorResponse.error)
          metadata       = errorResponse.error.metadata.map(_.mkString(", ")).getOrElse("empty")
          _             <- Logger[F].error(domainError)(s"OpenRouter API error during $operation: metadata=$metadata")
        yield domainError

      def createChatCompletion(request: ChatCompletionRequest): F[ChatCompletionResponse] =
        val uri = config.baseUri / "v1" / "chat" / "completions"
        client.expectOr[ChatCompletionResponse](Request[F](POST, uri).withHeaders(baseHeaders).withEntity(request))(
          handleError("chat completion")
        )

      def createCompletion(request: CompletionRequest): F[CompletionResponse] =
        val uri = config.baseUri / "v1" / "completions"
        client.expectOr[CompletionResponse](Request[F](POST, uri).withHeaders(baseHeaders).withEntity(request))(
          handleError("completion")
        )

      def listModels(): F[ModelsResponse] =
        client.expectOr[ModelsResponse](Request[F](GET, config.baseUri / "v1" / "models").withHeaders(baseHeaders))(
          handleError("list models")
        )

      def getGenerationStats(generationId: String): F[GenerationStats] =
        val uri = config.baseUri / "v1" / "generation" +? ("id" -> generationId)
        client
          .expectOr[GenerationStatsResponse](Request[F](GET, uri).withHeaders(baseHeaders))(
            handleError("get generation stats")
          )
          .map(_.data)

  private def createHttpClient[F[_]: Async: Network](config: Config): Resource[F, HTTPClient[F]] =
    EmberClientBuilder
      .default[F]
      .withTimeout(config.timeout)
      .withIdleConnectionTime(config.idleConnectionTime)
      .build

  private def configureClient[F[_]: Async](client: HTTPClient[F], config: Config): HTTPClient[F] =
    val retryPolicy = RetryPolicy[F](
      backoff = RetryPolicy.exponentialBackoff(maxWait = config.retryMaxWait, maxRetry = config.retryMaxAttempts),
      retriable = {
        case (GET -> Root / "api" / "v1" / "generation", Right(Response(Status.NotFound, _, _, _, _))) => true
        case (_, result)                                                                               => result.isLeft
      }
    )
    val retryClient = Retry[F](retryPolicy)(client)
    Http4sLogger.colored[F](logHeaders = config.logHeaders, logBody = config.logBody)(retryClient)

  def resource[F[_]: Async: Network: StructuredLogger](
      apiKey: String,
      config: Config = Client.Config()
  ): Resource[F, Client[F]] =
    createHttpClient(config).map(client => apply(configureClient(client, config), apiKey, config))

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

  def resourceObserved[F[_]: Async: Network: LoggerFactory: Tracer: Meter](
      apiKey: String,
      config: Config = Client.Config()
  ): Resource[F, Client[F]] =
    for
      given StructuredLogger[F] <- LoggerFactory[F].create.toResource
      statsSupervisor           <- Supervisor[F]

      openRouterRequestCounter      <- Meter[F].counter[Long](Metrics.name("openrouter_request_count")).create.toResource
      openRouterErrorCounter        <- Meter[F].counter[Long](Metrics.name("openrouter_error_count")).create.toResource
      totalCostCounter              <- Meter[F].counter[Double](Metrics.name("generation_total_cost")).create.toResource
      usageCounter                  <- Meter[F].counter[Double](Metrics.name("generation_usage")).create.toResource
      cacheDiscountCounter          <- Meter[F].counter[Double](Metrics.name("generation_cache_discount")).create.toResource
      upstreamInferenceCostCounter  <- Meter[F].counter[Double](Metrics.name("generation_upstream_inference_cost")).create.toResource
      latencyCounter                <- Meter[F].counter[Long](Metrics.name("generation_latency")).create.toResource
      moderationLatencyCounter      <- Meter[F].counter[Long](Metrics.name("generation_moderation_latency")).create.toResource
      generationTimeCounter         <- Meter[F].counter[Long](Metrics.name("generation_time")).create.toResource
      tokensPromptCounter           <- Meter[F].counter[Long](Metrics.name("generation_tokens_prompt")).create.toResource
      tokensCompletionCounter       <- Meter[F].counter[Long](Metrics.name("generation_tokens_completion")).create.toResource
      nativeTokensPromptCounter     <- Meter[F].counter[Long](Metrics.name("generation_native_tokens_prompt")).create.toResource
      nativeTokensCompletionCounter <- Meter[F].counter[Long](Metrics.name("generation_native_tokens_completion")).create.toResource
      nativeTokensReasoningCounter  <- Meter[F].counter[Long](Metrics.name("generation_native_tokens_reasoning")).create.toResource
      nativeTokensCachedCounter     <- Meter[F].counter[Long](Metrics.name("generation_native_tokens_cached")).create.toResource
      numMediaPromptCounter         <- Meter[F].counter[Long](Metrics.name("generation_num_media_prompt")).create.toResource
      numMediaCompletionCounter     <- Meter[F].counter[Long](Metrics.name("generation_num_media_completion")).create.toResource
      numSearchResultsCounter       <- Meter[F].counter[Long](Metrics.name("generation_num_search_results")).create.toResource
      requestTokenCounter           <- Meter[F].counter[Long](Metrics.name("request_token_count")).create.toResource
      responseTokenCounter          <- Meter[F].counter[Long](Metrics.name("response_token_count")).create.toResource

      meters = Meters[F](
                 requestTokenCounter = requestTokenCounter,
                 responseTokenCounter = responseTokenCounter,
                 requestCounter = openRouterRequestCounter,
                 errorCounter = openRouterErrorCounter,
                 totalCostCounter = totalCostCounter,
                 usageCounter = usageCounter,
                 cacheDiscountCounter = cacheDiscountCounter,
                 upstreamInferenceCostCounter = upstreamInferenceCostCounter,
                 latencyCounter = latencyCounter,
                 moderationLatencyCounter = moderationLatencyCounter,
                 generationTimeCounter = generationTimeCounter,
                 tokensPromptCounter = tokensPromptCounter,
                 tokensCompletionCounter = tokensCompletionCounter,
                 nativeTokensPromptCounter = nativeTokensPromptCounter,
                 nativeTokensCompletionCounter = nativeTokensCompletionCounter,
                 nativeTokensReasoningCounter = nativeTokensReasoningCounter,
                 nativeTokensCachedCounter = nativeTokensCachedCounter,
                 numMediaPromptCounter = numMediaPromptCounter,
                 numMediaCompletionCounter = numMediaCompletionCounter,
                 numSearchResultsCounter = numSearchResultsCounter
               )

      httpClient <- createHttpClient(config)

      instrumentedClient <- instrumentHttp4sClient("openrouter", httpClient).toResource

      clientWithMiddleware = configureClient(instrumentedClient, config)

      baseClient = create(clientWithMiddleware, apiKey, config)
    yield observed(baseClient, statsSupervisor, meters.some)

  def mapK[F[_], G[_]](client: Client[F])(f: F ~> G): Client[G] = new Client[G]:
    def createChatCompletion(request: ChatCompletionRequest): G[ChatCompletionResponse] = f(client.createChatCompletion(request))
    def createCompletion(request: CompletionRequest): G[CompletionResponse]             = f(client.createCompletion(request))
    def listModels(): G[ModelsResponse]                                                 = f(client.listModels())
    def getGenerationStats(generationId: String): G[GenerationStats]                    = f(client.getGenerationStats(generationId))
