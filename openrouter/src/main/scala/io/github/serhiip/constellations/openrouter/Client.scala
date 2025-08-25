package io.github.serhiip.constellations.openrouter

import scala.concurrent.duration.*

import cats.effect.std.Supervisor
import cats.effect.syntax.resource.*
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import cats.~>

import org.typelevel.ci.CIString
import org.typelevel.log4cats.{Logger, LoggerFactory, StructuredLogger}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Counter, Meter}
import org.typelevel.otel4s.trace.{SpanContext, StatusCode, Tracer}

import io.github.serhiip.constellations.common.Observability.*
import fs2.io.net.Network
import io.circe.derivation.Configuration
import io.circe.{Codec, Decoder, Encoder, Json}
import org.http4s.*
import org.http4s.Method.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client as HTTPClient
import org.http4s.dsl.io.*
import org.http4s.headers.{Authorization, Referer, `Content-Type`}

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

case class ChatCompletionResponse(id: String, `object`: String, created: Long, model: String, choices: List[ChatCompletionChoice], usage: ChatCompletionUsage)
    derives Codec

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
    supportedParameters: Option[List[String]] = None
) derives Codec

case class ModelPricing(
    prompt: String,
    completion: String,
    image: Option[String] = None,
    request: Option[String] = None,
    webSearch: Option[String] = None,
    internalReasoning: Option[String] = None,
    inputCacheRead: Option[String] = None,
    inputCacheWrite: Option[String] = None
) derives Codec

case class ModelArchitecture(
    inputModalities: List[String],
    outputModalities: List[String],
    tokenizer: Option[String] = None,
    instructType: Option[String] = None
) derives Codec

case class ModelTopProvider(
    isModerated: Boolean,
    contextLength: Int,
    maxCompletionTokens: Int
) derives Codec

case class PerRequestLimits(promptTokens: Option[String] = None, completionTokens: Option[String] = None, requestTokens: Option[String] = None) derives Codec

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
      logHeaders: Boolean = true,
      logBody: Boolean = true,
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

  def apply[F[_]: Async: StructuredLogger](client: HTTPClient[F], apiKey: String, config: Config): Client[F] =
    create(client, apiKey, config)

  def observed[F[_]: Async: Tracer: StructuredLogger](delegate: Client[F], statsSupervisor: Supervisor[F], meters: Option[Meters[F]] = none): Client[F] =
    new Client[F]:
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
              result <- delegate.createChatCompletion(request).onError(_ => meters.traverse_(_.errorCounter.inc(attrs*)))
              _      <- statsSupervisor.supervise(getGenerationStats(result.id))
              _      <- meters.traverse_(_.requestTokenCounter.add(result.usage.promptTokens.toLong, attrs*))
              _      <- meters.traverse_(_.responseTokenCounter.add(result.usage.completionTokens.toLong, attrs*))
              _      <- logger.trace(s"Chat completion created with ${result.choices.size} choices, used ${result.usage.totalTokens} tokens")
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
              result <- delegate.createCompletion(request).onError(_ => meters.traverse_(_.errorCounter.inc(attrs*)))
              _      <- statsSupervisor.supervise(getGenerationStats(result.id))
              _      <- meters.traverse_(_.requestTokenCounter.add(result.usage.promptTokens.toLong, attrs*))
              _      <- meters.traverse_(_.responseTokenCounter.add(result.usage.completionTokens.toLong, attrs*))
              _      <- logger.trace(s"Completion created with ${result.choices.size} choices, used ${result.usage.totalTokens} tokens")
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
              result <- delegate.listModels().onError(_ => meters.traverse_(_.errorCounter.inc(attrs*)))
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
                                  meters.traverse_(_.errorCounter.inc(attrs*)) >> span.setStatus(StatusCode.Error, t.getMessage) >> logger
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
              _ <- meters.traverse_(_.totalCostCounter.add(result.totalCost, enrichedAttrs*))
              _ <- meters.traverse_(_.usageCounter.add(result.usage, enrichedAttrs*))
              _ <- result.cacheDiscount.traverse(discount => meters.traverse_(_.cacheDiscountCounter.add(discount, enrichedAttrs*)))
              _ <- meters.traverse_(_.upstreamInferenceCostCounter.add(result.upstreamInferenceCost, enrichedAttrs*))
              _ <- meters.traverse_(_.latencyCounter.add(result.latency.toLong, enrichedAttrs*))
              _ <- result.moderationLatency.traverse(latency => meters.traverse_(_.moderationLatencyCounter.add(latency.toLong, enrichedAttrs*)))
              _ <- meters.traverse_(_.generationTimeCounter.add(result.generationTime.toLong, enrichedAttrs*))
              _ <- meters.traverse_(_.tokensPromptCounter.add(result.tokensPrompt.toLong, enrichedAttrs*))
              _ <- meters.traverse_(_.tokensCompletionCounter.add(result.tokensCompletion.toLong, enrichedAttrs*))
              _ <- meters.traverse_(_.nativeTokensPromptCounter.add(result.nativeTokensPrompt.toLong, enrichedAttrs*))
              _ <- meters.traverse_(_.nativeTokensCompletionCounter.add(result.nativeTokensCompletion.toLong, enrichedAttrs*))
              _ <- meters.traverse_(_.nativeTokensReasoningCounter.add(result.nativeTokensReasoning.toLong, enrichedAttrs*))
              _ <- meters.traverse_(_.nativeTokensCachedCounter.add(result.nativeTokensCached.toLong, enrichedAttrs*))
              _ <- result.numMediaPrompt.traverse(count => meters.traverse_(_.numMediaPromptCounter.add(count.toLong, enrichedAttrs*)))
              _ <- result.numMediaCompletion.traverse(count => meters.traverse_(_.numMediaCompletionCounter.add(count.toLong, enrichedAttrs*)))
              _ <- result.numSearchResults.traverse(count => meters.traverse_(_.numSearchResultsCounter.add(count.toLong, enrichedAttrs*)))
            yield result

  private def create[F[_]: Async: StructuredLogger](client: HTTPClient[F], apiKey: String, config: Config): Client[F] =
    new:
      private val baseHeaders =
        val authHeaders    = Headers(Authorization(Credentials.Token(AuthScheme.Bearer, apiKey)), `Content-Type`(MediaType.application.json))
        val refererHeaders = Headers(config.appUrl.map(url => Referer(Uri.unsafeFromString(url))).toList)
        val titleHeaders   = Headers(config.appTitle.map(title => Header.Raw(CIString("X-Title"), title)).toList)

        authHeaders ++ refererHeaders ++ titleHeaders

      private def handleError[A](operation: String): Response[F] => F[Throwable] = response =>
        response.as[ErrorResponse].flatMap { errorResponse =>
          Logger[F].error(
            s"OpenRouter API error during $operation: code=${errorResponse.error.code}, message=${errorResponse.error.message}, metadata=${errorResponse.error.metadata.map(_.mkString(", ")).getOrElse("no metadata")}"
          ) >>
            new Exception(
              s"OpenRouter API error: ${errorResponse.error.message} (code: ${errorResponse.error.code}) ${errorResponse.error.metadata.map(_.mkString(", ")).getOrElse("no metadata")}"
            ).pure[F]
        }

      def createChatCompletion(request: ChatCompletionRequest): F[ChatCompletionResponse] =
        val uri = config.baseUri / "v1" / "chat" / "completions"
        client.expectOr[ChatCompletionResponse](Request[F](POST, uri).withHeaders(baseHeaders).withEntity(request))(handleError("chat completion"))

      def createCompletion(request: CompletionRequest): F[CompletionResponse] =
        val uri = config.baseUri / "v1" / "completions"
        client.expectOr[CompletionResponse](Request[F](POST, uri).withHeaders(baseHeaders).withEntity(request))(handleError("completion"))

      def listModels(): F[ModelsResponse] =
        client.expectOr[ModelsResponse](Request[F](GET, config.baseUri / "v1" / "models").withHeaders(baseHeaders))(handleError("list models"))

      def getGenerationStats(generationId: String): F[GenerationStats] =
        val uri = config.baseUri / "v1" / "generation" +? ("id" -> generationId)
        client.expectOr[GenerationStatsResponse](Request[F](GET, uri).withHeaders(baseHeaders))(handleError("get generation stats")).map(_.data)

  def resource[F[_]: Async: Network: StructuredLogger](apiKey: String, config: Config = Client.Config()): Resource[F, Client[F]] =
    import org.http4s.ember.client.EmberClientBuilder
    import org.http4s.client.middleware.{Retry, RetryPolicy}
    import org.http4s.client.middleware.Logger

    for client <- EmberClientBuilder
                    .default[F]
                    .withTimeout(config.timeout)
                    .withIdleConnectionTime(config.idleConnectionTime)
                    .build
                    .map: client =>
                      val retryPolicy  = RetryPolicy[F](
                        backoff = RetryPolicy.exponentialBackoff(maxWait = config.retryMaxWait, maxRetry = config.retryMaxAttempts),
                        retriable = {
                          case (GET -> Root / "api" / "v1" / "generation", Right(Response(Status.NotFound, _, _, _, _))) => true
                          case (_, result)                                                                               => result.isLeft
                        }
                      )
                      val retryClient  = Retry[F](retryPolicy)(client)
                      val loggedClient = Logger.colored[F](logHeaders = config.logHeaders, logBody = config.logBody)(retryClient)
                      apply(loggedClient, apiKey, config)
    yield client

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

      baseClient <- resource(apiKey, config)
    yield observed(baseClient, statsSupervisor, meters.some)

  def mapK[F[_], G[_]](client: Client[F])(f: F ~> G): Client[G] = new Client[G]:
    def createChatCompletion(request: ChatCompletionRequest): G[ChatCompletionResponse] =
      f(client.createChatCompletion(request))

    def createCompletion(request: CompletionRequest): G[CompletionResponse] =
      f(client.createCompletion(request))

    def listModels(): G[ModelsResponse] =
      f(client.listModels())

    def getGenerationStats(generationId: String): G[GenerationStats] =
      f(client.getGenerationStats(generationId))
