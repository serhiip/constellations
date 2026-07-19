# Observability

Constellations components can be wrapped with OpenTelemetry tracing, log4cats logging, and metrics.

## ToolDispatcher

`ToolDispatcher.observed` returns **`F[ToolDispatcher[F]]`**. Required givens: `Tracer`, `LoggerFactory`, `Meter`, `MonadThrow`.

```scala mdoc:compile-only
import cats.MonadThrow
import cats.effect.IO
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import io.github.serhiip.constellations.ToolDispatcher

def withObservability[F[_]: Tracer: LoggerFactory: Meter: MonadThrow](
    raw: ToolDispatcher[F]
): F[ToolDispatcher[F]] =
  ToolDispatcher.observed(raw)
```

| Kind    | Name                                                        |
| ------- | ----------------------------------------------------------- |
| Span    | `constellations-tool-dispatcher-dispatch`                   |
| Span    | `constellations-tool-dispatcher-dispatch-all`               |
| Span    | `constellations-tool-dispatcher-get-function-declarations`  |
| Counter | `constellations/tool_dispatcher_dispatch_success_count`     |
| Counter | `constellations/tool_dispatcher_dispatch_error_count`       |
| Counter | `constellations/tool_dispatcher_dispatch_all_success_count` |
| Counter | `constellations/tool_dispatcher_dispatch_all_error_count`   |

Logs are enriched with trace/span IDs when a span is active.

## Stateful agent-error retries

`Stateful.apply` (observed) adds agent-error attributes to the **current** span and logs. Use `Stateful.pure` in tests.

Attributes on the active span for a failed batch: `retries_left`, `error` (comma-separated kinds: `argument-decoding-failed` / `unknown-function`), `agent_error_count`. Retry attempts are logged at warn; exhaustion at error.

## Similarity metrics

`Similarity.observed` (in **core**) records success/error counters and a latency histogram for `findClosest`:

- `constellations/similarity_find_closest_success_count`
- `constellations/similarity_find_closest_error_count`
- `constellations/similarity_find_closest_duration` (unit `s`)

Example: `Similarity.observed(RagEngine.similarity(...))` for metered RAG search.

## Logging

Use log4cats `StructuredLogger` / `LoggerFactory`. Observed wrappers attach trace context to loggers automatically.

## Related

- [ToolDispatcher](tool-dispatcher.md) — full tool routing guide
- [GCP RAG Engine](gcp-rag-engine.md) — similarity integration
