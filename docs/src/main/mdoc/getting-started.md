# Getting Started

Welcome to Constellations - a type-safe library for building AI-powered applications with function calling capabilities in Scala 3.

## Installation

Add the following to your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "io.github.serhiip" %% "constellations-core" % "@VERSION@",
  "io.github.serhiip" %% "constellations-openrouter" % "@VERSION@"
)
```

## Quick Example

Here's a simple example showing how to define AI-callable functions:

```scala mdoc:silent
import scala.annotation.experimental
import cats.effect.IO
import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.Dispatcher
import io.github.serhiip.constellations.common.*

// Define your functions as a trait
trait Calculator[F[_]]:
  /** Adds two numbers together */
  def add(a: Int, b: Int): F[Int]
  
  /** Multiplies two numbers */
  def multiply(a: Int, b: Int): F[Int]

// Create an implementation
val calculatorImpl = new Calculator[IO]:
  def add(a: Int, b: Int): IO[Int] = IO.pure(a + b)
  def multiply(a: Int, b: Int): IO[Int] = IO.pure(a * b)

// Generate a dispatcher with automatic schema generation
@experimental
def createDispatcher(): Dispatcher[IO] = 
  Dispatcher.generate[IO, Calculator](calculatorImpl)

val dispatcher = createDispatcher()
```

Now you can use the dispatcher to handle AI function calls:

```scala mdoc
// Create a function call
val call = FunctionCall(
  name = "Calculator_add",
  args = Struct(Map(
    "a" -> Value.number(5),
    "b" -> Value.number(3)
  ))
)

// Execute and get the result
import cats.effect.unsafe.implicits.global

val result = dispatcher.dispatch(call).unsafeRunSync()
result match
  case Dispatcher.Result.Response(response) =>
    println(s"Result: ${response.response}")
  case Dispatcher.Result.HumanInTheLoop =>
    println("Human approval required")
```

## Core Concepts

Constellations is built around several key abstractions:

1. **Dispatcher** - Routes function calls to your Scala implementations using compile-time macros
2. **Executor** - Orchestrates AI conversations with memory and state management
3. **Memory** - Stores conversation history and execution steps
4. **Invoker** - Handles AI model interactions with retry logic
5. **Handling** - Parses AI responses and extracts function calls

## Project Structure

The library is organized into several modules:

- **constellations-core** - Core abstractions and type-safe function dispatching
- **constellations-openrouter** - OpenRouter API integration
- **constellations-google-genai** - Google GenAI integration
- **constellations-mcp** - Model Context Protocol server support

## Next Steps

- Learn about [Core Concepts](core-concepts.md)
- Explore the [Dispatcher](dispatcher.md) for automatic function routing
- Set up [OpenRouter](openrouter.md) or [Google GenAI](google-genai.md) integration
- Check out the [Examples](examples.md) for complete working projects
