# Examples

Complete working examples demonstrating Constellations features.

## Calculator

A simple calculator with addition, multiplication, and division:

```scala
trait Calculator[F[_]]:
  def add(a: Int, b: Int): F[Int]
  def multiply(a: Int, b: Int): F[Int]
  def divide(a: Double, b: Double): F[Double]
```

[See full example →](https://github.com/serhiip/constellations/tree/main/examples/src/main/scala/io/github/serhiip/constellations/examples)

## Weather Bot

An AI assistant that can check the weather:

- Uses OpenRouter for AI responses
- Makes real weather API calls
- Maintains conversation context

[See full example →](https://github.com/serhiip/constellations/tree/main/examples)

## MCP Server

A complete MCP server with multiple tools:

- Calculator tools
- Greeting functions
- Can be used with Claude Desktop

Run it:

```bash
sbt "examples/runMain io.github.serhiip.constellations.examples.mcp.McpServerExample"
```

## More Examples

Check the [examples directory](https://github.com/serhiip/constellations/tree/main/examples) for more complete projects.
