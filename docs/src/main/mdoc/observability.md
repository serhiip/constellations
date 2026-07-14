# Observability

Constellations provides comprehensive observability with OpenTelemetry integration.

## Overview

All components include:
- **Distributed tracing** - Trace requests across components
- **Structured logging** - Contextual log messages
- **Metrics** - Performance and usage counters

## Tracing

Components are automatically traced:

```scala
import io.github.serhiip.constellations.ToolDispatcher
import org.typelevel.otel4s.trace.Tracer

// ToolDispatcher is automatically traced
val tracedDispatcher = ToolDispatcher[IO](dispatcher)
```

## Logging

Structured logging with trace context:

```scala
import org.typelevel.log4cats.StructuredLogger

// Logs include trace IDs automatically
logger.info("Processing function call")(...)
```

## Similarity metrics

`Similarity.observed` records success/error counters and a latency histogram for `findClosest`:

- `constellations/similarity_find_closest_success_count`
- `constellations/similarity_find_closest_error_count`
- `constellations/similarity_find_closest_duration` (unit `s`, buckets from 100ms to 15s)

Compose with `Similarity.observed` (e.g. `Similarity.observed(RagEngine.similarity(...))`) for traced/metered RAG search.

## Coming Soon

Detailed configuration examples for OpenTelemetry backends.
