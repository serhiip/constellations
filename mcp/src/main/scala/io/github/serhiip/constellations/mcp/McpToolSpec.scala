package io.github.serhiip.constellations.mcp

import scala.jdk.CollectionConverters.*

import cats.data.Reader
import cats.{Applicative, Functor, Monad}
import cats.effect.std.Dispatcher as CeDispatcher
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

import io.github.serhiip.constellations.Dispatcher
import io.github.serhiip.constellations.common.Codecs.given
import io.github.serhiip.constellations.common.Observability.*
import io.github.serhiip.constellations.common.{FunctionCall, FunctionDeclaration, Struct}

trait McpToolSpec[F[_]]:
  def fromDispatcher(dispatcher: Dispatcher[F]): F[List[McpToolSpec.AsyncToolSpecification]]

object McpToolSpec:
  type AsyncToolSpecification = McpServerFeatures.AsyncToolSpecification

  final case class Config(jsonMapper: McpJsonMapper)

  def apply[F[_]: Monad: Functor: Tracer: LoggerFactory](ceDispatcher: CeDispatcher[F]): Reader[Config, F[McpToolSpec[F]]] =
    core[F](ceDispatcher).map(_.flatMap(observed))

  def core[F[_]: Applicative: Functor](ceDispatcher: CeDispatcher[F]): Reader[Config, F[McpToolSpec[F]]] =
    Reader: config =>
      DispatcherToolSpec[F](ceDispatcher, config).pure[F]

  def observed[F[_]: Monad: Tracer: LoggerFactory](delegate: McpToolSpec[F]): F[McpToolSpec[F]] =
    LoggerFactory[F].create.map(TracedMcpToolSpec(delegate)(using Monad[F], Tracer[F], _))

  val defaultConfig: Config = Config(JacksonMcpJsonMapper(JsonMapper.builder().build()))

  final class DispatcherToolSpec[F[_]: Functor](ceDispatcher: CeDispatcher[F], config: Config) extends McpToolSpec[F]:
    def fromDispatcher(dispatcher: Dispatcher[F]): F[List[AsyncToolSpecification]] =
      dispatcher.getFunctionDeclarations.map(_.map(toAsyncToolSpecification(dispatcher, config.jsonMapper)))

    private def toAsyncToolSpecification(dispatcher: Dispatcher[F], jsonMapper: McpJsonMapper)(
        declaration: FunctionDeclaration
    ): AsyncToolSpecification =
      McpServerFeatures.AsyncToolSpecification
        .builder()
        .tool(toMcpTool(declaration, jsonMapper))
        .callHandler((_, request) =>
          val call = FunctionCall(declaration.name, Struct.fromMap(request.arguments().asScala.toMap), None)
          Mono
            .fromFuture(ceDispatcher.unsafeToCompletableFuture(dispatcher.dispatch(call).map(toCallToolResult)))
            .onErrorResume(error => Mono.just(toErrorResult(error)))
        )
        .build()

    private def toMcpTool(declaration: FunctionDeclaration, jsonMapper: McpJsonMapper): McpSchema.Tool =
      val builder = McpSchema.Tool.builder().name(declaration.name)
      declaration.description.foreach(builder.description)
      declaration.parameters.foreach(schema => builder.inputSchema(jsonMapper, schema.asJson.noSpaces))
      builder.build()

    private def toCallToolResult(result: Dispatcher.Result): McpSchema.CallToolResult =
      result match
        case Dispatcher.Result.Response(response) =>
          McpSchema.CallToolResult.builder().addTextContent(response.response.asJson.noSpaces).isError(false).build()
        case Dispatcher.Result.HumanInTheLoop     =>
          McpSchema.CallToolResult.builder().addTextContent("Human input is required to continue.").isError(true).build()

    private def toErrorResult(error: Throwable): McpSchema.CallToolResult =
      McpSchema.CallToolResult.builder().addTextContent(error.getMessage).isError(true).build()

  final class TracedMcpToolSpec[F[_]: Monad: Tracer: StructuredLogger](delegate: McpToolSpec[F]) extends McpToolSpec[F]:
    def fromDispatcher(dispatcher: Dispatcher[F]): F[List[AsyncToolSpecification]] =
      Tracer[F]
        .span("mcp-tool-spec", "from-dispatcher")
        .logged: logger =>
          for
            _     <- logger.trace("Generating MCP tool specifications from dispatcher")
            tools <- delegate.fromDispatcher(dispatcher)
            _     <- logger.trace(s"Generated ${tools.size} MCP tool specifications")
          yield tools
