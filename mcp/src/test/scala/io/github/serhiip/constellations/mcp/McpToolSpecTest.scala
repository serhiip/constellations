package io.github.serhiip.constellations.mcp

import java.util as ju

import scala.jdk.CollectionConverters.*

import cats.effect.IO
import cats.effect.std.Dispatcher
import munit.CatsEffectSuite

import io.modelcontextprotocol.spec.McpSchema

import io.github.serhiip.constellations.ToolDispatcher

class McpToolSpecTest extends CatsEffectSuite:

  trait Calculator[F[_]]:
    def add(a: Int, b: Int): F[Int]

  object Calculator extends Calculator[IO]:
    def add(a: Int, b: Int): IO[Int] = IO.pure(a + b)

  final case class MethodPath(className: String, methodName: String)

  trait MethodsApi[F[_]]:
    def fixedMethods(methods: List[MethodPath]): F[List[String]]

  object MethodsApi extends MethodsApi[IO]:
    def fixedMethods(methods: List[MethodPath]): IO[List[String]] =
      IO.pure(methods.map(m => s"${m.className}.${m.methodName}"))

  test("fromToolDispatcher exposes dispatcher methods as MCP tools") {
    Dispatcher
      .parallel[IO]
      .use: dispatcher =>
        for
          mcpToolSpec <- McpToolSpec.core[IO](dispatcher).run(McpToolSpec.defaultConfig)
          tools       <- mcpToolSpec.fromToolDispatcher(ToolDispatcher.generate[IO](Calculator))
          tool         = tools.head
        yield
          assertEquals(tools.size, 1)
          assertEquals(tool.tool().name(), "calculator_add")
          assertEquals(tool.tool().inputSchema().required().asScala.toList, List("a", "b"))
  }

  test("generated tool dispatches MCP arguments through the dispatcher") {
    Dispatcher
      .parallel[IO]
      .use: dispatcher =>
        for
          mcpToolSpec <- McpToolSpec.core[IO](dispatcher).run(McpToolSpec.defaultConfig)
          tools       <- mcpToolSpec.fromToolDispatcher(ToolDispatcher.generate[IO](Calculator))
          request      =
            McpSchema.CallToolRequest("calculator_add", Map[String, Object]("a" -> Integer.valueOf(1), "b" -> Integer.valueOf(2)).asJava)
          result      <- IO.blocking(tools.head.callHandler().apply(null, request).block())
        yield
          val content = result.content().get(0).asInstanceOf[McpSchema.TextContent]
          assertEquals(content.text(), """{"value":3.0}""")
          assertEquals(result.isError().booleanValue(), false)
  }

  test("generated tool decodes nested Java List/Map arguments from Jackson-style payloads") {
    Dispatcher
      .parallel[IO]
      .use: dispatcher =>
        for
          mcpToolSpec <- McpToolSpec.core[IO](dispatcher).run(McpToolSpec.defaultConfig)
          tools       <- mcpToolSpec.fromToolDispatcher(ToolDispatcher.generate[IO](MethodsApi))
          nested       = ju.ArrayList[ju.Map[String, Object]]()
          first        = ju.LinkedHashMap[String, Object]()
          _            = first.put("class_name", "Foo")
          _            = first.put("method_name", "bar")
          second       = ju.LinkedHashMap[String, Object]()
          _            = second.put("class_name", "Baz")
          _            = second.put("method_name", "qux")
          _            = nested.add(first)
          _            = nested.add(second)
          args         = ju.HashMap[String, Object]()
          _            = args.put("methods", nested)
          request      = McpSchema.CallToolRequest("methods_api_fixed_methods", args)
          result      <- IO.blocking(tools.head.callHandler().apply(null, request).block())
        yield
          val content = result.content().get(0).asInstanceOf[McpSchema.TextContent]
          assertEquals(result.isError().booleanValue(), false, clue = content.text())
          assertEquals(content.text(), """{"value":["Foo.bar","Baz.qux"]}""")
  }
