# MCP Server

Constellations supports the Model Context Protocol (MCP), allowing you to expose your functions as tools that can be used by AI assistants like Claude.

## Overview

The MCP module enables you to:
- **Expose functions as MCP tools** - Use your Dispatcher with MCP servers
- **Type-safe tool definitions** - Automatic schema generation
- **Local tool execution** - Run tools locally for privacy
- **Multiple tools** - Combine multiple dispatchers into one server

## Setup

Add the dependency:

```scala
libraryDependencies += "io.github.serhiip" %% "constellations-mcp" % "@VERSION@"
```

## Quick Example

Create an MCP server:

```scala
import io.github.serhiip.constellations.mcp.*
import cats.effect.IO
import cats.effect.std.Dispatcher as CeDispatcher

trait Calculator[F[_]]:
  def add(a: Int, b: Int): F[Int]

class CalculatorImpl extends Calculator[IO]:
  def add(a: Int, b: Int): IO[Int] = IO(a + b)

// Create dispatcher
val dispatcher = Dispatcher.generate[IO, Calculator](new CalculatorImpl)

// Convert to MCP tools
CeDispatcher.parallel[IO].use { ceDispatcher =>
  for
    toolSpecs <- McpToolSpec.fromDispatcher(dispatcher, ceDispatcher)
    // Register with MCP server
  yield ()
}
```

## Complete Server

Run the example server:

```bash
sbt "examples/runMain io.github.serhiip.constellations.examples.mcp.McpServerExample"
```

## Configuration with Claude Desktop

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

## Coming Soon

More MCP server examples and integration patterns.
