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
import io.github.serhiip.constellations.Dispatcher
import org.typelevel.otel4s.trace.Tracer

// Dispatcher is automatically traced
val tracedDispatcher = Dispatcher[IO](dispatcher)
```

## Logging

Structured logging with trace context:

```scala
import org.typelevel.log4cats.StructuredLogger

// Logs include trace IDs automatically
logger.info("Processing function call")(...)
```

## Coming Soon

Detailed configuration examples for OpenTelemetry backends.
