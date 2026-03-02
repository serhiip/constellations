# Constellations

A Scala 3 library for building AI-powered applications with function calling capabilities. Constellations provides a type-safe, observable framework for integrating AI models with your Scala applications.

## Quick Start

```sbt
libraryDependencies ++= Seq(
  "io.github.serhiip" %% "constellations-core" % "<version>",
  "io.github.serhiip" %% "constellations-openrouter" % "<version>",
  "io.github.serhiip" %% "constellations-google-genai" % "<version>"
)
```

> [!NOTE]
>
> For Snapshots you will need to add the following resolver
> ```sbt
> resolvers += ("Maven snapshots" at "https://central.sonatype.com/repository/maven-snapshots/")
> ```

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
  name = "calculator_add",
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

## Dispatcher: Automatic Function Calling

The `Dispatcher` is the heart of Constellations, enabling seamless integration between AI models and your Scala code. It automatically handles:

### 1. Automatic Function Calling

Define your API as a Scala trait with methods returning `F[_]`:

```scala
import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.Dispatcher
import cats.effect.IO

trait WeatherService[F[_]]:
  def getWeather(city: String): F[WeatherReport]
  def listCities(): F[List[String]]

case class WeatherReport(
  temperature: Double,
  conditions: String,
  humidity: Int
) derives ValueEncoder

// Implementation
class WeatherServiceImpl extends WeatherService[IO]:
  def getWeather(city: String): IO[WeatherReport] =
    IO.pure(WeatherReport(72.5, "Sunny", 45))

  def listCities(): IO[List[String]] =
    IO.pure(List("New York", "London", "Tokyo"))

// Generate dispatcher (macros do all the work!)
val dispatcher = Dispatcher.generate[IO, WeatherService](new WeatherServiceImpl)
```

The macro automatically:
- Generates JSON schemas for function declarations
- Creates dispatch logic to route LLM calls to your methods
- Handles parameter decoding and error reporting

### 2. Return Type Translation: Case Class → Struct

When your methods return case classes, they're automatically converted to `Struct` for the LLM:

```scala
def getWeather(city: String): IO[WeatherReport]
// Returns: WeatherReport(72.5, "Sunny", 45)

// Automatically becomes JSON for the LLM:
// {
//   "temperature": 72.5,
//   "conditions": "Sunny",
//   "humidity": 45
// }
```

Just add `derives ValueEncoder` to your case classes:

```scala
case class WeatherReport(
  temperature: Double,
  conditions: String,
  humidity: Int
) derives ValueEncoder
```

### 3. Input Translation: Struct → Case Class

When the LLM provides structured data, it automatically converts to your case classes:

```scala
// LLM sends:
// {
//   "city": "San Francisco",
//   "include_forecast": true
// }

case class WeatherQuery(
  city: String,
  includeForecast: Boolean  // camelCase in Scala
)

def queryWeather(query: WeatherQuery): IO[WeatherReport]
```

### 4. Automatic camelCase → snake_case Translation

Constellations automatically converts between Scala conventions and LLM-optimized naming:

| Scala (your code) | LLM sees/calls | Notes |
|-------------------|----------------|-------|
| `getWeather` | `weather_service_get_weather` | Method name converted |
| `includeForecast` | `include_forecast` | Parameter names converted |
| `WeatherService` | `weather_service` | Service name converted |

**Example:**

```scala
trait UserService[F[_]]:
  def updateUserProfile(userId: String, newEmail: String): F[ProfileUpdated]

// LLM sees:
// Function: "user_service_update_user_profile"
// Parameters: {"user_id": "...", "new_email": "..."}
```

This provides:
- **Idiomatic Scala**: You write `camelCase` code
- **LLM optimization**: Models get `snake_case` (better accuracy)
- **Zero configuration**: Happens automatically

### Complete End-to-End Example with OpenRouter

Here's a runnable example using OpenRouter:

```scala
import scala.annotation.experimental
import cats.effect.{IO, IOApp}
import cats.effect.std.{Console, Env}
import cats.syntax.all.*
import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.dispatcher.ValueEncoder
import io.github.serhiip.constellations.executor.Stateful
import io.github.serhiip.constellations.handling.OpenRouter as ORHandling
import io.github.serhiip.constellations.invoker.OpenRouter
import io.github.serhiip.constellations.openrouter.{ChatCompletionResponse, Client}
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

// 1. Define your case classes with ValueEncoder
case class WeatherReport(city: String, temperatureC: Double, condition: String)
  derives ValueEncoder

case class SearchResult(query: String, results: List[String])
  derives ValueEncoder

// 2. Define your service trait
@experimental
trait AssistantFunctions[F[_]]:
  def getWeather(city: String): F[WeatherReport]
  def search(query: String): F[SearchResult]

// 3. Implement the service
@experimental
class AssistantFunctionsImpl[F[_]: cats.Applicative] extends AssistantFunctions[F]:
  def getWeather(city: String): F[WeatherReport] =
    WeatherReport(city, 21.5, "Sunny").pure[F]

  def search(query: String): F[SearchResult] =
    SearchResult(query, List(s"Result for: $query")).pure[F]

// 4. Run the application
@experimental
object AssistantApp extends IOApp.Simple:
  def run: IO[Unit] =
    val factory = Slf4jFactory.create[IO]
    given LoggerFactory[IO] = factory

    factory.create.flatMap { implicit logger =>
      for
        apiKeyOpt <- Env[IO].get("OPENROUTER_API_KEY")
        apiKey    <- apiKeyOpt.liftTo[IO](new RuntimeException("Set OPENROUTER_API_KEY"))
        model      = sys.env.getOrElse("OPENROUTER_MODEL", "anthropic/claude-3-haiku")

        _ <- Client.resource[IO](apiKey, Client.Config()).use { client =>
          // Generate dispatcher from trait
          Dispatcher(Dispatcher.generate[IO, AssistantFunctions](
            AssistantFunctionsImpl[IO]
          )).flatMap { dispatcher =>
            for
              // Get function declarations for the LLM
              decls <- dispatcher.getFunctionDeclarations
              _     <- IO.println(s"Available functions: ${decls.map(_.name).mkString(", ")}")
              // Output: Available functions: assistant_functions_get_weather, assistant_functions_search

              // Set up the executor
              rawInvoker = OpenRouter.chatCompletion(
                client,
                OpenRouter.Config(
                  model = model,
                  temperature = 0.7.some,
                  systemPrompt = "You are a helpful assistant. Use the provided tools when relevant.".some
                ),
                decls
              )
              invoker  = Invoker(rawInvoker)
              handling = ORHandling[IO]()
              files   <- Files[IO](java.net.URI.create("file:///tmp/"))
              executor = Stateful[IO, ChatCompletionResponse](
                Stateful.Config(functionCallLimit = 5),
                handling,
                invoker,
                files
              )
              memory  <- Memory.inMemory[IO, java.util.UUID]
              _       <- IO.println("Assistant ready! Type 'exit' to quit.\n")
              _       <- replLoop(dispatcher, executor, memory)
            yield ()
          }
        }
      yield ()
    }.handleErrorWith(err => IO.println(s"Error: ${err.getMessage}"))

  private def replLoop(
      dispatcher: Dispatcher[IO],
      executor: Executor[IO, ?, Executor.Step.ModelResponse],
      memory: Memory[IO, java.util.UUID]
  ): IO[Unit] =
    for
      _    <- Console[IO].print("You: ")
      line <- Console[IO].readLine
      _    <- if line == null || line.trim.equalsIgnoreCase("exit") then
                IO.println("Goodbye!")
              else
                executor
                  .execute(dispatcher, memory, line)
                  .flatMap {
                    case Right(resp) => IO.println(s"AI: ${resp.text.getOrElse("no response")}")
                    case Left(_)     => IO.println("AI: [interrupted]")
                  } >> replLoop(dispatcher, executor, memory)
    yield ()
```

**To run:**

```bash
export OPENROUTER_API_KEY="your-api-key"
export OPENROUTER_MODEL="anthropic/claude-3-haiku"  # Optional
sbt "examples/runMain io.github.serhiip.constellations.AssistantApp"
```

### How It Works

1. **You define**: A trait with methods like `def getWeather(city: String): F[WeatherReport]`
2. **Macro generates**:
   - Function schema: `{"name": "assistant_functions_get_weather", ...}`
   - Dispatch logic that maps LLM calls to your methods
3. **LLM sees**: snake_case names optimized for function calling
4. **Your code receives**: Properly typed parameters and returns case classes
5. **LLM receives**: JSON-structured responses automatically

The dispatcher handles all the complexity of:
- Parameter parsing and validation
- Type conversion (String → Int, JSON → Case Class, etc.)
- Error accumulation (missing fields, type mismatches)
- Method routing by name
- Response encoding

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
