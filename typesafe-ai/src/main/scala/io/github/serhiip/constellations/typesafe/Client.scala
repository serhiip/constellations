package io.github.serhiip.constellations.typesafe

import scala.NamedTuple.AnyNamedTuple
import scala.concurrent.duration.*

import cats.Show
import cats.effect.syntax.resource.*
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import cats.~>
import fs2.io.net.Network
import io.circe.{Decoder, Json}
import org.http4s.*
import org.http4s.Method.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client as HTTPClient
import org.http4s.client.middleware.{Retry, RetryPolicy}
import org.http4s.client.middleware.Logger as Http4sLogger
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.{Authorization, `Content-Type`, `Retry-After`}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.{Logger, LoggerFactory, StructuredLogger}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Counter, Meter}
import org.typelevel.otel4s.trace.Tracer

import io.github.serhiip.constellations.common.Observability
import io.github.serhiip.constellations.common.Observability.*

trait Client[F[_]]:
  def systemOne[Q <: AnyNamedTuple](request: SystemOneRequest[Q]): F[SystemOneResponse[Q]]
  def listModels(): F[ListModelsResponse]

object Client:
  private val RequestIdHeader = CIString("x-typesafe-request-id")

  case class Config(
      baseUri: Uri = Uri.unsafeFromString("https://api.typesafe.ai"),
      timeout: FiniteDuration = 30.seconds,
      idleConnectionTime: FiniteDuration = 60.seconds,
      retryMaxWait: FiniteDuration = 30.seconds,
      retryMaxAttempts: Int = 5,
      logHeaders: Boolean = false,
      logBody: Boolean = false
  )

  case class Meters[F[_]](
      requestCounter: Counter[F, Long],
      errorCounter: Counter[F, Long],
      inputTokenCounter: Counter[F, Long],
      outputTokenCounter: Counter[F, Long]
  )

  enum Error extends RuntimeException:
    case BadRequest(message: String, body: Option[Json], requestId: Option[String])
    case Unauthorized(message: String, body: Option[Json], requestId: Option[String])
    case PermissionDenied(message: String, body: Option[Json], requestId: Option[String])
    case NotFound(message: String, body: Option[Json], requestId: Option[String])
    case UnprocessableEntity(message: String, body: Option[Json], requestId: Option[String])
    case RateLimited(message: String, retryAfter: Option[FiniteDuration], body: Option[Json], requestId: Option[String])
    case Overloaded(message: String, body: Option[Json], requestId: Option[String])
    case InternalServer(message: String, body: Option[Json], requestId: Option[String])
    case Unexpected(status: Int, message: String, body: Option[Json], requestId: Option[String])
    case AnswerMissing(questionId: String)
    case AnswerTypeMismatch(questionId: String, expected: String, actual: String)
    case AnswerOptionUnknown(questionId: String, value: String, expected: List[String])

    override def getMessage(): String = this.show

  object Error:
    given Show[Error] = Show.show {
      case BadRequest(msg, _, requestId)                    => s"Bad Request: $msg${formatRequestId(requestId)}"
      case Unauthorized(msg, _, requestId)                  => s"Unauthorized: $msg${formatRequestId(requestId)}"
      case PermissionDenied(msg, _, requestId)              => s"Permission Denied: $msg${formatRequestId(requestId)}"
      case NotFound(msg, _, requestId)                      => s"Not Found: $msg${formatRequestId(requestId)}"
      case UnprocessableEntity(msg, _, requestId)           => s"Unprocessable Entity: $msg${formatRequestId(requestId)}"
      case RateLimited(msg, retryAfter, _, requestId)       =>
        val retry = retryAfter.fold("")(d => s" (retry after $d)")
        s"Rate Limited: $msg$retry${formatRequestId(requestId)}"
      case Overloaded(msg, _, requestId)                    => s"Overloaded: $msg${formatRequestId(requestId)}"
      case InternalServer(msg, _, requestId)                => s"Internal Server Error: $msg${formatRequestId(requestId)}"
      case Unexpected(status, msg, _, requestId)            => s"Unexpected Error (status $status): $msg${formatRequestId(requestId)}"
      case AnswerMissing(questionId)                        => s"Answer missing for question id: $questionId"
      case AnswerTypeMismatch(questionId, expected, actual) =>
        s"Answer type mismatch for question id $questionId: expected $expected, got $actual"
      case AnswerOptionUnknown(questionId, value, expected) =>
        s"Unknown answer option for question id $questionId: got $value, expected one of ${expected.mkString(", ")}"
    }

    private def formatRequestId(requestId: Option[String]): String =
      requestId.fold("")(id => s" (request-id: $id)")

  def apply[F[_]: Async: StructuredLogger](client: HTTPClient[F], apiKey: String, config: Config = Config()): Client[F] =
    create(client, apiKey, config)

  def observed[F[_]: Async: Tracer: StructuredLogger](
      delegate: Client[F],
      meters: Option[Meters[F]] = none
  ): Client[F] =
    new:
      def systemOne[Q <: AnyNamedTuple](request: SystemOneRequest[Q]): F[SystemOneResponse[Q]] =
        val modelAttr         = Attribute("model", request.model)
        val questionCountAttr = Attribute("question_count", request.questions.length.toLong)
        val questionTypesAttr = Attribute(
          "question_types",
          request.questions.toSortedMap.values.map(Question.typeName).toList.distinct.sorted.mkString(",")
        )
        val attrs             = List(modelAttr, questionCountAttr, questionTypesAttr)
        Tracer[F]
          .span("typesafe-client", "system-one")(attrs*)
          .logged: logger =>
            for
              _      <- logger.trace(s"Evaluating ${request.questions.length} questions with model ${request.model}")
              _      <- meters.traverse_(_.requestCounter.inc(attrs*))
              result <- delegate.systemOne(request).onError {
                          case e: Client.Error => addResponseStatus(e).flatTap(_ => recordError(attrs, e))
                          case _               => meters.traverse_(_.errorCounter.inc(attrs*))
                        }
              _      <- addResponseStatus(200)
              _      <- meters.traverse_(m =>
                          result.usage.inputTokens.traverse_(n => m.inputTokenCounter.add(n.toLong, attrs*)) *>
                            result.usage.outputTokens.traverse_(n => m.outputTokenCounter.add(n.toLong, attrs*))
                        )
              _      <- logger.trace(
                          s"System One returned ${result.rawAnswers.size} answers, model=${result.model}, usage=${result.usage}"
                        )
            yield result

      def listModels(): F[ListModelsResponse] =
        val operationAttr = Attribute("operation", "list-models")
        Tracer[F]
          .span("typesafe-client", "list-models")(operationAttr)
          .logged: logger =>
            for
              _      <- logger.trace("Listing available models")
              _      <- meters.traverse_(_.requestCounter.inc(operationAttr))
              result <- delegate.listModels().onError {
                          case e: Client.Error => addResponseStatus(e).flatTap(_ => recordError(List(operationAttr), e))
                          case _               => meters.traverse_(_.errorCounter.inc(operationAttr))
                        }
              _      <- addResponseStatus(200)
              _      <- logger.trace(s"Found ${result.models.size} models")
            yield result

      private def recordError(attrs: Seq[Attribute[?]], error: Client.Error): F[Unit] =
        val errorCodeAttr = Attribute("error_code", errorCode(error))
        meters.traverse_(_.errorCounter.inc(attrs :+ errorCodeAttr))

      private def errorCode(error: Client.Error): String =
        error match
          case Client.Error.BadRequest(_, _, _)          => "400"
          case Client.Error.Unauthorized(_, _, _)        => "401"
          case Client.Error.PermissionDenied(_, _, _)    => "403"
          case Client.Error.NotFound(_, _, _)            => "404"
          case Client.Error.UnprocessableEntity(_, _, _) => "422"
          case Client.Error.RateLimited(_, _, _, _)      => "429"
          case Client.Error.Overloaded(_, _, _)          => "529"
          case Client.Error.InternalServer(_, _, _)      => "500"
          case Client.Error.Unexpected(status, _, _, _)  => status.toString
          case Client.Error.AnswerMissing(_)             => "answer_missing"
          case Client.Error.AnswerTypeMismatch(_, _, _)  => "answer_type_mismatch"
          case Client.Error.AnswerOptionUnknown(_, _, _) => "answer_option_unknown"

      private def addResponseStatus(code: Int): F[Unit] =
        Tracer[F].currentSpanOrNoop.flatMap(_.addAttributes(Attribute("response_status_code", code.toLong)))

      private def addResponseStatus(error: Client.Error): F[Unit] =
        addResponseStatus(statusFromError(error))

      private def statusFromError(error: Client.Error): Int =
        error match
          case Client.Error.BadRequest(_, _, _)          => 400
          case Client.Error.Unauthorized(_, _, _)        => 401
          case Client.Error.PermissionDenied(_, _, _)    => 403
          case Client.Error.NotFound(_, _, _)            => 404
          case Client.Error.UnprocessableEntity(_, _, _) => 422
          case Client.Error.RateLimited(_, _, _, _)      => 429
          case Client.Error.Overloaded(_, _, _)          => 529
          case Client.Error.InternalServer(_, _, _)      => 500
          case Client.Error.Unexpected(status, _, _, _)  => status
          case Client.Error.AnswerMissing(_)             => 422
          case Client.Error.AnswerTypeMismatch(_, _, _)  => 422
          case Client.Error.AnswerOptionUnknown(_, _, _) => 422

  private def create[F[_]: Async: StructuredLogger](client: HTTPClient[F], apiKey: String, config: Config): Client[F] =
    new:
      private val baseHeaders =
        Headers(
          Authorization(Credentials.Token(AuthScheme.Bearer, apiKey)),
          `Content-Type`(MediaType.application.json)
        )

      def systemOne[Q <: AnyNamedTuple](request: SystemOneRequest[Q]): F[SystemOneResponse[Q]] =
        val uri = config.baseUri / "v1" / "systemone"
        given Decoder[(String, Map[String, Answer[?]], Usage)] = SystemOneResponse.decodeRaw
        client
          .run(Request[F](POST, uri).withHeaders(baseHeaders).withEntity(request: SystemOneRequest[?]))
          .use { response =>
            decodeResponse[(String, Map[String, Answer[?]], Usage)]("system-one")(response).map {
              case (model, answers, usage) => SystemOneResponse(model, answers, usage, request)
            }
          }

      def listModels(): F[ListModelsResponse] =
        client
          .run(Request[F](GET, config.baseUri / "v1" / "models").withHeaders(baseHeaders))
          .use(decodeResponse[ListModelsResponse]("list-models"))

      private def decodeResponse[A: Decoder](operation: String)(response: Response[F]): F[A] =
        val requestId = response.headers.get(RequestIdHeader).map(_.head.value)
        if response.status.isSuccess then
          response.as[A].adaptError { case err =>
            Client.Error.Unexpected(response.status.code, s"Failed to decode $operation response: ${err.getMessage}", none, requestId)
          }
        else
          for
            body        <- response.as[Json].attempt.map(_.toOption)
            message      = extractErrorMessage(body).getOrElse(response.status.reason)
            retryAfter   = parseRetryAfter(response)
            domainError  = classifyError(response.status, message, body, requestId, retryAfter)
            _           <- Logger[F].error(domainError)(s"TypeSafe API error during $operation")
            result      <- domainError.raiseError[F, A]
          yield result

      private def extractErrorMessage(body: Option[Json]): Option[String] =
        body.flatMap { json =>
          val cursor = json.hcursor
          cursor
            .downField("error")
            .downField("message")
            .as[String]
            .orElse(cursor.downField("message").as[String])
            .orElse(cursor.downField("detail").as[String])
            .toOption
            .orElse(json.asString)
        }

      private def parseRetryAfter(response: Response[F]): Option[FiniteDuration] =
        response.headers.get[`Retry-After`].flatMap(_.retry.toOption.map(_.seconds))

      private def classifyError(
          status: Status,
          message: String,
          body: Option[Json],
          requestId: Option[String],
          retryAfter: Option[FiniteDuration]
      ): Client.Error =
        status.code match
          case 400                  => Client.Error.BadRequest(message, body, requestId)
          case 401                  => Client.Error.Unauthorized(message, body, requestId)
          case 403                  => Client.Error.PermissionDenied(message, body, requestId)
          case 404                  => Client.Error.NotFound(message, body, requestId)
          case 422                  => Client.Error.UnprocessableEntity(message, body, requestId)
          case 429                  => Client.Error.RateLimited(message, retryAfter, body, requestId)
          case 529                  => Client.Error.Overloaded(message, body, requestId)
          case code if code >= 500  => Client.Error.InternalServer(message, body, requestId)
          case code                 => Client.Error.Unexpected(code, message, body, requestId)

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
        case (_, Right(response)) if response.status.code == 429 || response.status.code == 529 => true
        case (_, result)                                                                        => result.isLeft
      }
    )
    val retryClient = Retry[F](retryPolicy)(client)
    Http4sLogger.colored[F](logHeaders = config.logHeaders, logBody = config.logBody)(retryClient)

  def resource[F[_]: Async: Network: StructuredLogger](
      apiKey: String,
      config: Config = Config()
  ): Resource[F, Client[F]] =
    createHttpClient(config).map(client => apply(configureClient(client, config), apiKey, config))

  def resourceObserved[F[_]: Async: Network: LoggerFactory: Tracer: Meter](
      apiKey: String,
      config: Config = Config()
  ): Resource[F, Client[F]] =
    for
      given StructuredLogger[F] <- LoggerFactory[F].create.toResource
      requestCounter            <- Meter[F].counter[Long](Observability.Metrics.name("typesafe_request_count")).create.toResource
      errorCounter              <- Meter[F].counter[Long](Observability.Metrics.name("typesafe_error_count")).create.toResource
      inputTokenCounter         <- Meter[F].counter[Long](Observability.Metrics.name("typesafe_input_token_count")).create.toResource
      outputTokenCounter        <- Meter[F].counter[Long](Observability.Metrics.name("typesafe_output_token_count")).create.toResource
      meters                     = Meters(requestCounter, errorCounter, inputTokenCounter, outputTokenCounter)
      httpClient                <- createHttpClient(config)
      baseClient                 = create(configureClient(httpClient, config), apiKey, config)
    yield observed(baseClient, meters.some)

  def mapK[F[_], G[_]](client: Client[F])(f: F ~> G): Client[G] = new Client[G]:
    def systemOne[Q <: AnyNamedTuple](request: SystemOneRequest[Q]): G[SystemOneResponse[Q]] = f(client.systemOne(request))
    def listModels(): G[ListModelsResponse]                                                  = f(client.listModels())
