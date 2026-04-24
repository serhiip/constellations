package io.github.serhiip.constellations.mcp

import scala.jdk.CollectionConverters.*

import cats.effect.IO
import cats.effect.std.Dispatcher as CeDispatcher
import munit.CatsEffectSuite

import io.modelcontextprotocol.spec.McpSchema

import io.github.serhiip.constellations.Dispatcher

class McpToolSpecTest extends CatsEffectSuite:

  trait Calculator[F[_]]:
    def add(a: Int, b: Int): F[Int]

  object Calculator extends Calculator[IO]:
    def add(a: Int, b: Int): IO[Int] = IO.pure(a + b)

  test("fromDispatcher exposes dispatcher methods as MCP tools") {
    CeDispatcher
      .parallel[IO]
      .use: ceDispatcher =>
        for
          mcpToolSpec <- McpToolSpec.core[IO](ceDispatcher).run(McpToolSpec.defaultConfig)
          tools       <- mcpToolSpec.fromDispatcher(Dispatcher.generate[IO](Calculator))
          tool         = tools.head
        yield
          assertEquals(tools.size, 1)
          assertEquals(tool.tool().name(), "Calculator_add")
          assertEquals(tool.tool().inputSchema().required().asScala.toList, List("a", "b"))
  }

  test("generated tool dispatches MCP arguments through the dispatcher") {
    CeDispatcher
      .parallel[IO]
      .use: ceDispatcher =>
        for
          mcpToolSpec <- McpToolSpec.core[IO](ceDispatcher).run(McpToolSpec.defaultConfig)
          tools       <- mcpToolSpec.fromDispatcher(Dispatcher.generate[IO](Calculator))
          request      =
            McpSchema.CallToolRequest("Calculator_add", Map[String, Object]("a" -> Integer.valueOf(1), "b" -> Integer.valueOf(2)).asJava)
          result      <- IO.blocking(tools.head.callHandler().apply(null, request).block())
        yield
          val content = result.content().get(0).asInstanceOf[McpSchema.TextContent]
          assertEquals(content.text(), """{"value":3.0}""")
          assertEquals(result.isError().booleanValue(), false)
  }
