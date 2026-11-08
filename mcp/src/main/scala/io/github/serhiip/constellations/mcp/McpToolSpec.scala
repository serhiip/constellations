package io.github.serhiip.constellations.mcp

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.*

import cats.data.Reader
import cats.{Functor, Monad}
import cats.effect.Outcome
import cats.effect.kernel.{MonadCancel, Sync}
import cats.effect.std.Dispatcher
import cats.syntax.all.*

import io.circe.syntax.*
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.{McpServerFeatures, McpStatelessServerFeatures}
import io.modelcontextprotocol.spec.McpSchema
import org.typelevel.log4cats.{LoggerFactory, StructuredLogger}
import org.typelevel.otel4s.metrics.{Counter, Meter}
import org.typelevel.otel4s.trace.Tracer
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper

import io.github.serhiip.constellations.ToolDispatcher
import io.github.serhiip.constellations.common.Codecs.given
import io.github.serhiip.constellations.common.Observability.*
import io.github.serhiip.constellations.common.{FunctionCall, FunctionDeclaration, Schema, Struct}

trait McpToolSpec[F[_]]:
  def fromToolDispatcher(toolDispatcher: ToolDispatcher[F]): F[List[McpToolSpec.AsyncToolSpecification]]
  def fromStatelessToolDispatcher(toolDispatcher: ToolDispatcher[F]): F[List[McpToolSpec.StatelessAsyncToolSpecification]]

object McpToolSpec:
  type AsyncToolSpecification          = McpServerFeatures.AsyncToolSpecification
  type StatelessAsyncToolSpecification = McpStatelessServerFeatures.AsyncToolSpecification

  final case class Config(jsonMapper: McpJsonMapper)

  def apply[F[_]: Sync: Tracer: LoggerFactory: Meter](dispatcher: Dispatcher[F]): Reader[Config, F[McpToolSpec[F]]] =
    Reader: config =>
      for
        logger <- LoggerFactory[F].create
        bridge <- ReactorBridge.observed(dispatcher, logger)
        traced <- observed(ToolDispatcherSpec(config, bridge))
      yield traced

  def core[F[_]: Sync](dispatcher: Dispatcher[F]): Reader[Config, F[McpToolSpec[F]]] =
    Reader: config =>
      ToolDispatcherSpec[F](config, ReactorBridge[F](dispatcher)).pure[F]

  def observed[F[_]: Monad: Tracer: LoggerFactory](delegate: McpToolSpec[F]): F[McpToolSpec[F]] =
    LoggerFactory[F].create.map(TracedMcpToolSpec(delegate)(using Monad[F], Tracer[F], _))

  val defaultConfig: Config = Config(JacksonMcpJsonMapper(JsonMapper.builder().build()))

  private final class ToolDispatcherSpec[F[_]: Functor](config: Config, bridge: ReactorBridge[F]) extends McpToolSpec[F]:
    def fromToolDispatcher(toolDispatcher: ToolDispatcher[F]): F[List[AsyncToolSpecification]] =
      toolDispatcher.getFunctionDeclarations.map(_.map(toAsyncToolSpecification(toolDispatcher, config.jsonMapper)))

    def fromStatelessToolDispatcher(toolDispatcher: ToolDispatcher[F]): F[List[StatelessAsyncToolSpecification]] =
      toolDispatcher.getFunctionDeclarations.map(_.map(toStatelessAsyncToolSpecification(toolDispatcher, config.jsonMapper)))

    private def toAsyncToolSpecification(toolDispatcher: ToolDispatcher[F], jsonMapper: McpJsonMapper)(
        declaration: FunctionDeclaration
    ): AsyncToolSpecification =
      McpServerFeatures.AsyncToolSpecification
        .builder()
        .tool(toMcpTool(declaration, jsonMapper))
        .callHandler((_, request) => dispatch(toolDispatcher, declaration)(request))
        .build()

    private def toStatelessAsyncToolSpecification(toolDispatcher: ToolDispatcher[F], jsonMapper: McpJsonMapper)(
        declaration: FunctionDeclaration
    ): StatelessAsyncToolSpecification =
      McpStatelessServerFeatures.AsyncToolSpecification
        .builder()
        .tool(toMcpTool(declaration, jsonMapper))
        .callHandler((_, request) => dispatch(toolDispatcher, declaration)(request))
        .build()

    private def dispatch(toolDispatcher: ToolDispatcher[F], declaration: FunctionDeclaration)(
        request: McpSchema.CallToolRequest
    ): Mono[McpSchema.CallToolResult] =
      val args = Option(request.arguments()).fold(Map.empty)(_.asScala.toMap)
      val call = FunctionCall(declaration.name, Struct.fromMap(args), None)
      bridge
        .toMono(toolDispatcher.dispatch(call).map(toCallToolResult))
        .onErrorResume(error => Mono.just(toErrorResult(error)))

    private def toMcpTool(declaration: FunctionDeclaration, jsonMapper: McpJsonMapper): McpSchema.Tool =
      val builder = McpSchema.Tool.builder().name(declaration.name)
      declaration.description.foreach(builder.description)
      val schema  = declaration.parameters.getOrElse(Schema.obj())
      builder.inputSchema(jsonMapper, schema.asJson.noSpaces)
      builder.build()

    private def toCallToolResult(result: ToolDispatcher.Result): McpSchema.CallToolResult =
      result match
        case ToolDispatcher.Result.Response(response) =>
          McpSchema.CallToolResult.builder().addTextContent(response.response.asJson.noSpaces).isError(false).build()
        case ToolDispatcher.Result.HumanInTheLoop     =>
          McpSchema.CallToolResult.builder().addTextContent("Human input is required to continue.").isError(true).build()

    private def toErrorResult(error: Throwable): McpSchema.CallToolResult =
      McpSchema.CallToolResult.builder().addTextContent(error.getMessage).isError(true).build()

  final class TracedMcpToolSpec[F[_]: Monad: Tracer: StructuredLogger](delegate: McpToolSpec[F]) extends McpToolSpec[F]:
    def fromToolDispatcher(toolDispatcher: ToolDispatcher[F]): F[List[AsyncToolSpecification]] =
      trace("from-tool-dispatcher", delegate.fromToolDispatcher(toolDispatcher))

    def fromStatelessToolDispatcher(toolDispatcher: ToolDispatcher[F]): F[List[StatelessAsyncToolSpecification]] =
      trace("from-stateless-tool-dispatcher", delegate.fromStatelessToolDispatcher(toolDispatcher))

    private def trace[A](spanName: String, generate: F[List[A]]): F[List[A]] =
      Tracer[F]
        .span("mcp-tool-spec", spanName)
        .logged: logger =>
          for
            _     <- logger.trace("Generating MCP tool specifications from tool dispatcher")
            tools <- generate
            _     <- logger.trace(s"Generated ${tools.size} MCP tool specifications")
          yield tools

  private trait ReactorBridge[F[_]]:
    def toMono[A](fa: F[A]): Mono[A]

  private object ReactorBridge:
    def apply[F[_]: Sync](dispatcher: Dispatcher[F]): ReactorBridge[F] =
      Pure(dispatcher)

    def observed[F[_]: Sync: Meter](dispatcher: Dispatcher[F], logger: StructuredLogger[F]): F[ReactorBridge[F]] =
      Meter[F]
        .counter[Long](Metrics.component("mcp_tool")("cancellation")("failure_count"))
        .withDescription("Failed fiber cancellations while bridging an effect to Mono")
        .create
        .map(Observed(dispatcher, logger, _))

    private class Pure[F[_]: Sync](dispatcher: Dispatcher[F]) extends ReactorBridge[F]:
      protected def onCancellationFailure(error: Throwable): Unit = ()

      def toMono[A](fa: F[A]): Mono[A] =
        Mono.create: sink =>
          val (_, cancelToken) = dispatcher.unsafeToFutureCancelable(
            MonadCancel[F].guaranteeCase(fa):
              case Outcome.Succeeded(result) => result.map(sink.success)
              case Outcome.Errored(e)        => Sync[F].delay(sink.error(e))
              case Outcome.Canceled()        => Sync[F].delay(sink.success())
          )
          sink.onCancel(() => cancelToken().failed.foreach(onCancellationFailure)(ExecutionContext.parasitic)): Unit

    private class Observed[F[_]: Sync](
        dispatcher: Dispatcher[F],
        logger: StructuredLogger[F],
        cancellationFailures: Counter[F, Long]
    ) extends Pure[F](dispatcher):
      override protected def onCancellationFailure(error: Throwable): Unit =
        dispatcher.unsafeRunAndForget(
          logger.error(error)(s"MCP: Error during fiber cancelation: ${error.getMessage}") *> cancellationFailures.inc()
        )
