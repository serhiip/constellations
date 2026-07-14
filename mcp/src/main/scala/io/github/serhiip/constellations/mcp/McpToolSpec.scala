package io.github.serhiip.constellations.mcp

import scala.jdk.CollectionConverters.*

import cats.data.Reader
import cats.{Applicative, Functor, Monad}
import cats.effect.std.Dispatcher
import cats.syntax.all.*

import io.circe.syntax.*
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import org.typelevel.log4cats.{LoggerFactory, StructuredLogger}
import org.typelevel.otel4s.trace.Tracer
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper

import io.github.serhiip.constellations.ToolDispatcher
import io.github.serhiip.constellations.common.Codecs.given
import io.github.serhiip.constellations.common.Observability.*
import io.github.serhiip.constellations.common.{FunctionCall, FunctionDeclaration, Struct}

trait McpToolSpec[F[_]]:
  def fromToolDispatcher(toolDispatcher: ToolDispatcher[F]): F[List[McpToolSpec.AsyncToolSpecification]]

object McpToolSpec:
  type AsyncToolSpecification = McpServerFeatures.AsyncToolSpecification

  final case class Config(jsonMapper: McpJsonMapper)

  def apply[F[_]: Monad: Functor: Tracer: LoggerFactory](dispatcher: Dispatcher[F]): Reader[Config, F[McpToolSpec[F]]] =
    core[F](dispatcher).map(_.flatMap(observed))

  def core[F[_]: Applicative: Functor](dispatcher: Dispatcher[F]): Reader[Config, F[McpToolSpec[F]]] =
    Reader: config =>
      ToolDispatcherSpec[F](dispatcher, config).pure[F]

  def observed[F[_]: Monad: Tracer: LoggerFactory](delegate: McpToolSpec[F]): F[McpToolSpec[F]] =
    LoggerFactory[F].create.map(TracedMcpToolSpec(delegate)(using Monad[F], Tracer[F], _))

  val defaultConfig: Config = Config(JacksonMcpJsonMapper(JsonMapper.builder().build()))

  final class ToolDispatcherSpec[F[_]: Functor](dispatcher: Dispatcher[F], config: Config) extends McpToolSpec[F]:
    def fromToolDispatcher(toolDispatcher: ToolDispatcher[F]): F[List[AsyncToolSpecification]] =
      toolDispatcher.getFunctionDeclarations.map(_.map(toAsyncToolSpecification(toolDispatcher, config.jsonMapper)))

    private def toAsyncToolSpecification(toolDispatcher: ToolDispatcher[F], jsonMapper: McpJsonMapper)(
        declaration: FunctionDeclaration
    ): AsyncToolSpecification =
      McpServerFeatures.AsyncToolSpecification
        .builder()
        .tool(toMcpTool(declaration, jsonMapper))
        .callHandler((_, request) =>
          val call = FunctionCall(declaration.name, Struct.fromMap(request.arguments().asScala.toMap), None)
          Mono
            .fromFuture(dispatcher.unsafeToCompletableFuture(toolDispatcher.dispatch(call).map(toCallToolResult)))
            .onErrorResume(error => Mono.just(toErrorResult(error)))
        )
        .build()

    private def toMcpTool(declaration: FunctionDeclaration, jsonMapper: McpJsonMapper): McpSchema.Tool =
      val builder = McpSchema.Tool.builder().name(declaration.name)
      declaration.description.foreach(builder.description)
      declaration.parameters.foreach(schema => builder.inputSchema(jsonMapper, schema.asJson.noSpaces))
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
      Tracer[F]
        .span("mcp-tool-spec", "from-tool-dispatcher")
        .logged: logger =>
          for
            _     <- logger.trace("Generating MCP tool specifications from tool dispatcher")
            tools <- delegate.fromToolDispatcher(toolDispatcher)
            _     <- logger.trace(s"Generated ${tools.size} MCP tool specifications")
          yield tools
