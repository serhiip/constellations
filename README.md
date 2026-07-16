# Constellations

Type-safe Scala 3 toolkit for building AI applications with function calling, schema derivation, and observability.

## Quick Start

**Tools only** (`ToolDispatcher` + types):

```sbt
libraryDependencies += "io.github.serhiip" %% "constellations-common" % "<version>"
```

**Full stack** (conversation loop + provider):

```sbt
libraryDependencies ++= Seq(
  "io.github.serhiip" %% "constellations-core" % "<version>",
  "io.github.serhiip" %% "constellations-openrouter" % "<version>"
)
```

> [!NOTE]
> Snapshots require:
> ```sbt
> resolvers += ("Maven snapshots" at "https://central.sonatype.com/repository/maven-snapshots/")
> ```

## Scala versions

- `constellations-common` is built with **Scala 3.3.8** (LTS) so `ToolDispatcher` works on 3.3.8+ and 3.7.x
- Other modules (`core`, providers, `mcp`, …) require **Scala 3.7.x**

## Modules

- `constellations-common`: shared types, codecs, observability helpers, and `ToolDispatcher`
- `constellations-core`: execution, memory, invokers (depends on common transitively)
- `constellations-openrouter` / `constellations-google-genai` / `constellations-gcp-rag-engine` / `constellations-bedrock`: providers
- `constellations-mcp`: Model Context Protocol server support (depends on common)
- `constellations-examples`: runnable examples

## Core concepts

- `ToolDispatcher[F[_]]`: builds function declarations and routes calls to Scala methods
- `Executor[F[_], E, T]`: runs workflows with memory
- `Invoker[F[_], T]`: calls LLMs and returns model responses
- `Memory[F[_], Id]`: stores conversation steps
- Common types: `Schema`, `Struct`, `Value`, `FunctionCall`, `FunctionResponse`

## Example: dispatching a function call

```scala
import cats.effect.IO
import io.github.serhiip.constellations.ToolDispatcher
import io.github.serhiip.constellations.common.*

trait Calculator[F[_]]:
  def add(a: Int, b: Int): F[Int]

val impl = new Calculator[IO]:
  def add(a: Int, b: Int): IO[Int] = IO.pure(a + b)

val dispatcher = ToolDispatcher.generate[IO](impl)

val call = FunctionCall(
  name = "Calculator_add",
  args = Struct("a" -> Value.number(5), "b" -> Value.number(3))
)

dispatcher.dispatch(call).flatMap {
  case ToolDispatcher.Result.Response(response) => IO.println(response.response)
  case ToolDispatcher.Result.HumanInTheLoop     => IO.println("Human input required")
}
```

### Naming

- Tool name: `{TraitName}_{snake_case_method}` — e.g. `Calculator_add`, `WeatherService_get_weather`
- Parameters: snake_case (`intVal` → `int_val`)
- Trait/component names are **not** snake_cased

### Observability

`ToolDispatcher.apply` returns `F[ToolDispatcher[F]]` and needs `Tracer`, `LoggerFactory`, `Meter`, and `MonadThrow`:

```scala
for
  observed <- ToolDispatcher(dispatcher)
yield observed
```

## Schema derivation

`Schema.derived` / `ToSchema` (in `constellations-common`) build JSON Schema for case classes and sealed hierarchies. Use `@llmHint` for field metadata. See the [docs](https://serhiip.github.io/constellations/) for details.

## Documentation

Full guides (ToolDispatcher, encoding, values/schemas, MCP, providers):

- Site: generate with `sbt docs/mdoc` / Docusaurus, or browse the published docs
- Source: [`docs/src/main/mdoc/`](docs/src/main/mdoc/)

## Running examples

```bash
sbt "examples/runMain io.github.serhiip.constellations.examples.mcp.McpServerExample"
```

## Contributing

PRs and issues are welcome.

## License

MIT. See [LICENSE](LICENSE).
