# MCP Server

Constellations can expose a [`ToolDispatcher`](tool-dispatcher.md) as [Model Context Protocol](https://modelcontextprotocol.io/) tools for assistants like Claude.

## Setup

```scala
libraryDependencies += "io.github.serhiip" %% "constellations-mcp" % "@VERSION@"
```

This depends on **`constellations-common`** (Scala **3.3.8+** for the common JAR; the MCP module itself is built for **3.7.x**).

## Quick example

1. Build a `ToolDispatcher` from your traits (`generate` / `to`)
2. Create `McpToolSpec` with a Cats Effect `Dispatcher`
3. Call `fromToolDispatcher` to get MCP tool specifications
4. Register them on an MCP server

```scala
import io.github.serhiip.constellations.ToolDispatcher
import io.github.serhiip.constellations.mcp.*
import cats.effect.IO
import cats.effect.std.Dispatcher

trait Calculator[F[_]]:
  def add(a: Int, b: Int): F[Int]

val calculator = new Calculator[IO]:
  def add(a: Int, b: Int): IO[Int] = IO.pure(a + b)

val toolDispatcher = ToolDispatcher.generate[IO](calculator)

Dispatcher.parallel[IO].use { dispatcher =>
  for
    mcpToolSpec <- McpToolSpec.core[IO](dispatcher).run(McpToolSpec.defaultConfig)
    toolSpecs   <- mcpToolSpec.fromToolDispatcher(toolDispatcher)
  yield toolSpecs
}
```

Tool names follow the usual naming contract (`Calculator_add`). See [ToolDispatcher](tool-dispatcher.md).

## Complete server

```bash
sbt "examples/runMain io.github.serhiip.constellations.examples.mcp.McpServerExample"
```

See also `examples/.../examples/mcp/README.md`.

## Claude Desktop

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "constellations": {
      "command": "sbt",
      "args": ["examples/runMain", "io.github.serhiip.constellations.examples.mcp.McpServerExample"]
    }
  }
}
```

## Related

- [ToolDispatcher](tool-dispatcher.md)
- [Getting started](getting-started.md) — install `constellations-common` for tools-only apps
