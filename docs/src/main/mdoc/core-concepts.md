# Core Concepts

Constellations splits into a **common** foundation (tools and types) and **core** orchestration (the agent loop).

## Common vs core

| Layer | Module | What you get |
|-------|--------|----------------|
| **Common** | `constellations-common` | `ToolDispatcher`, `Value` / `Struct` / `Schema`, encoders/decoders, messages |
| **Core** | `constellations-core` | `Executor`, `Memory`, `Invoker`, `Handling`, `Similarity` |

Start with [ToolDispatcher](tool-dispatcher.md) if you only need to expose Scala methods as LLM tools. Add core when you want a multi-step conversation loop with memory.

```
                    constellations-common
                 +--------------------------+
                 | ToolDispatcher + types   |
                 +------------^-------------+
                              |
User -> Invoker -> Handling --+--> ToolDispatcher -> your F[_] methods
         |                    |
         v                    |
      Executor <-- Memory ----+
         |
         v
   continue chat...
```

## Key abstractions

- **[ToolDispatcher](tool-dispatcher.md)** — macro-generated tool routing (common)
- **[Values & schemas](value-and-types.md)** / **[Encoding](encoding-decoding.md)** — shared data model (common)
- **[Messages](messages.md)** — chat + tool messages (common)
- **Executor** — orchestrates steps with memory (core)
- **Memory** — stores conversation history (core)
- **Invoker** — calls the LLM (core + providers)
- **Handling** — parses model responses into text / tool calls (core + providers)

## Type safety

Effects are expressed with Cats Effect:

```scala mdoc:compile-only
import cats.effect.IO
import cats.effect.std.Console

val program: IO[Unit] =
  Console[IO].println("Hello from Constellations!")
```

## Schema generation

Traits used with `ToolDispatcher.generate` get JSON schemas and declarations from method signatures and docstrings. Standalone derivation uses `Schema.derived` / `ToSchema` — see [Values & schemas](value-and-types.md).

## Learn more

- Common: [ToolDispatcher](tool-dispatcher.md), [Encoding & decoding](encoding-decoding.md), [Observability](observability.md)
- Core: [Executor](executor.md), [Memory](memory.md), [Invoker](invoker.md), [Handling](handling.md)
- Providers: [OpenRouter](openrouter.md), [Google GenAI](google-genai.md)
