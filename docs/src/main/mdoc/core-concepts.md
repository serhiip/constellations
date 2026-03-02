# Core Concepts

Constellations provides a set of composable abstractions for building AI-powered applications. This page introduces the core concepts and shows how they work together.

## Overview

The library is built around these key abstractions:

- **Dispatcher** - Routes AI function calls to your Scala implementations
- **Executor** - Orchestrates multi-step AI conversations  
- **Memory** - Stores conversation history and execution context
- **Invoker** - Handles communication with AI models
- **Handling** - Parses AI responses and extracts structured data

## The Big Picture

A typical Constellations workflow looks like this:

```
User Query → Invoker (AI Model) → Handling (Parse Response) 
    ↓
[If function calls] → Dispatcher → Your Functions
    ↓
Function Results → Executor (State Management) → Memory (Storage)
    ↓
Continue conversation...
```

## Type Safety

All components are built on Cats Effect for pure functional programming:

```scala mdoc:compile-only
import cats.effect.IO
import cats.effect.std.Console

// Everything is type-safe and effectful
val program: IO[Unit] = for
  _ <- Console[IO].println("Hello from Constellations!")
yield ()
```

## Automatic Schema Generation

One of Constellations' most powerful features is automatic schema generation:

```scala mdoc:silent
trait WeatherService[F[_]]:
  /** Gets current weather for a location */
  def getWeather(city: String, country: Option[String]): F[WeatherData]
  
  /** Gets weather forecast for multiple days */
  def getForecast(city: String, days: Int): F[List[ForecastDay]]

case class WeatherData(
  temperature: Double,
  conditions: String,
  humidity: Int
)

case class ForecastDay(
  date: String,
  high: Double,
  low: Double,
  conditions: String
)
```

The macro automatically generates:
- JSON Schema for function parameters
- Function declarations with descriptions
- Type-safe routing logic

## Learn More

- [Executor](executor.md) - Workflow orchestration
- [Dispatcher](dispatcher.md) - Function routing
- [Memory](memory.md) - Conversation storage
- [Invoker](invoker.md) - AI model communication
- [Handling](handling.md) - Response parsing
