# Constellations

A Scala 3 library for building AI-powered applications with function calling capabilities. Constellations provides a type-safe, observable framework for integrating AI models with your Scala applications.

## Overview

Constellations enables you to:

- **Define functions as Scala traits** - Use familiar Scala syntax to define AI-callable functions
- **Automatic schema generation** - Scala 3 macros automatically generate JSON schemas from your trait definitions
- **Type-safe function dispatching** - Call Scala functions from AI model responses with compile-time safety
- **Conversation memory management** - Maintain and query conversation history
- **Built-in observability** - Comprehensive tracing, logging, and metrics with OpenTelemetry
- **OpenRouter integration** - Seamless integration with OpenRouter API for accessing various AI models

## Modules

### Core Module (`constellations-core`)

The core module provides the fundamental abstractions for function calling and conversation management.

#### Key Components

- **`Dispatcher[F[_]]`** - Automatically dispatches function calls to Scala methods using Scala 3 macros
- **`Executor[F[_], E, T]`** - Orchestrates AI workflows with memory management
- **`Memory[F[_], Id]`** - Stores conversation history and execution steps
- **`Invoker[F[_]]`** - Handles function invocation with error handling
- **Common types** - `FunctionCall`, `FunctionResponse`, `Schema`, `Value`, `Struct` for type-safe data handling

#### Example Usage

```scala
import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.Dispatcher

// Define your functions as a trait
trait Calculator[F[_]]:
  def add(a: Int, b: Int): F[Int]
  def multiply(a: Int, b: Int): F[Int]

// Create an implementation
val calculatorImpl = new Calculator[IO]:
  def add(a: Int, b: Int): IO[Int] = IO.pure(a + b)
  def multiply(a: Int, b: Int): IO[Int] = IO.pure(a * b)

// Generate a dispatcher (automatically creates JSON schemas and routing)
val dispatcher = Dispatcher.generate[IO, Calculator](calculatorImpl)

// Use the dispatcher
val functionCall = FunctionCall(
  name = "Calculator_add",
  args = Struct(Map("a" -> Value.number(5), "b" -> Value.number(3)))
)

dispatcher.dispatch(functionCall).flatMap {
  case Dispatcher.Result.Response(response) =>
    IO.println(s"Result: ${response.response}")
  case Dispatcher.Result.HumanInTheLoop =>
    IO.println("Human input required")
}
```

### OpenRouter Module (`constellations-openrouter`)

The OpenRouter module provides a fully-featured HTTP client for the OpenRouter API, with comprehensive observability and error handling.

#### Features

- **Chat Completions** - Generate conversational AI responses with function calling support
- **Text Completions** - Generate text completions
- **Model Management** - List available models and their capabilities
- **Generation Statistics** - Detailed metrics and cost tracking
- **Built-in Retry Logic** - Automatic retry with exponential backoff
- **Comprehensive Metrics** - Token usage, latency, cost tracking, and more
- **Tracing Integration** - Full OpenTelemetry tracing support

#### Example Usage

```scala
import io.github.serhiip.constellations.openrouter.*
import org.http4s.ember.client.EmberClientBuilder
import cats.effect.{IO, IOApp}

object Example extends IOApp.Simple:
  def run: IO[Unit] =
    val config = Client.Config(
      appUrl = Some("https://my-app.com"),
      appTitle = Some("My AI App")
    )

    Client.resource[IO]("your-api-key", config).use { client =>
      val request = ChatCompletionRequest(
        model = "anthropic/claude-3-haiku",
        messages = List(
          ChatMessage.user("What's 2 + 2?")
        ),
        temperature = Some(0.7)
      )

      client.createChatCompletion(request).flatMap { response =>
        IO.println(s"Response: ${response.choices.head.message.content}")
      }
    }
```

## Key Features

### Macro-Powered Schema Generation

Constellations uses Scala 3 macros to automatically generate JSON schemas from your trait definitions:

```scala
trait UserService[F[_]]:
  /** Create a new user */
  def createUser(name: String, email: String, age: Option[Int]): F[UserId]

  /** Find user by ID */
  def findUser(id: UserId): F[Option[User]]

  /** List users with pagination */
  def listUsers(limit: Int, offset: Int): F[List[User]]
```

The macro automatically generates:

- JSON schemas for all parameters
- Function declarations with descriptions
- Type-safe dispatching logic

### Built-in Observability

All components include comprehensive observability:

```scala
// Components are automatically wrapped with tracing and logging
val observedDispatcher = Dispatcher[IO](dispatcher)
val observedExecutor = Executor[IO, Error, Response](executor)
val observedMemory = Memory[IO, UUID](memory)
```

### Memory Management

Track conversation history and execution steps:

```scala
// Create in-memory storage
val memory = Memory.inMemory[IO, UUID]

// Record execution steps
memory.record(Executor.Step.UserQuery("What's the weather?", OffsetDateTime.now()))
memory.record(Executor.Step.Call(functionCall, OffsetDateTime.now()))

// Retrieve conversation history
val history = memory.retrieve
```

### Type-Safe Data Structures

Constellations provides type-safe data structures for AI interactions:

```scala
// Function calls with structured arguments
val call = FunctionCall(
  name = "createUser",
  args = Struct(
    "name" -> Value.string("John Doe"),
    "email" -> Value.string("john@example.com"),
    "age" -> Value.number(30)
  )
)

// Flexible value types
enum Value:
  case StringValue(value: String)
  case NumberValue(value: Double)
  case BoolValue(value: Boolean)
  case StructValue(value: Struct)
  case ListValue(value: List[Value])
```

## Advanced Usage

### Custom Memory Implementations

```scala
class DatabaseMemory[F[_]: Async, Id](repo: UserRepository[F, Id])
    extends Memory[F, Id]:
  def record(step: Executor.Step): F[Unit] =
    repo.saveStep(step)

  def retrieve: F[Chain[Executor.Step]] =
    repo.getAllSteps()

  def last: F[Option[(Id, Executor.Step)]] =
    repo.getLastStep()
```

### Effect Type Transformation

```scala
// Transform between different effect types
val ioClient: Client[IO] = ???
val futureClient: Client[Future] = Client.mapK(ioClient)(λ[IO ~> Future](_.unsafeToFuture()))
```

### Custom Executors

```scala
class CustomExecutor[F[_]: Async: Tracer: Logger](
  client: Client[F],
  dispatcher: Dispatcher[F]
) extends Executor[F, AppError, ChatResponse]:

  def execute(query: String): F[Either[AppError, ChatResponse]] =
    // Custom execution logic here

  def resume(): F[Either[AppError, ChatResponse]] =
    // Custom resume logic here
```

## Contributing

Contributions are welcome! Please feel free to submit pull requests, report issues, or suggest new features.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
