# Dispatcher

The Dispatcher is the heart of Constellations' function calling system. It uses Scala 3 macros to automatically route AI function calls to your Scala implementations with compile-time safety.

## Overview

The Dispatcher trait provides:
- **Automatic schema generation** - JSON schemas from your trait definitions
- **Type-safe routing** - Compile-time verification of function signatures
- **Macro-powered dispatch** - Zero runtime reflection overhead

```scala
// Core Dispatcher API
trait Dispatcher[F[_]]:
  def dispatch(call: FunctionCall): F[Dispatcher.Result]
  def getFunctionDeclarations: F[List[FunctionDeclaration]]
```

## Basic Usage

Define your API as a trait with docstrings:

```scala mdoc:silent
import scala.annotation.experimental
import cats.effect.IO
import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.Dispatcher
import io.github.serhiip.constellations.common.*

// Define your functions as a trait
trait Calculator[F[_]]:
  /** Adds two integers together */
  def add(a: Int, b: Int): F[Int]
  
  /** Multiplies two integers */
  def multiply(a: Int, b: Int): F[Int]
  
  /** Divides a by b. Throws if b is 0. */
  def divide(a: Double, b: Double): F[Double]
```

Create an implementation:

```scala mdoc:silent
val calculator = new Calculator[IO]:
  def add(a: Int, b: Int): IO[Int] = IO.pure(a + b)
  def multiply(a: Int, b: Int): IO[Int] = IO.pure(a * b)
  def divide(a: Double, b: Double): IO[Double] =
    if b == 0 then IO.raiseError(new ArithmeticException("Division by zero"))
    else IO.pure(a / b)
```

Generate a Dispatcher:

```scala mdoc:silent
import scala.annotation.experimental

@experimental
def createDispatcher(): Dispatcher[IO] = 
  Dispatcher.generate[IO, Calculator](calculator)

val dispatcher = createDispatcher()
```

## Dispatching Calls

The macro generates a map from function names to handlers:

```scala mdoc
import io.github.serhiip.constellations.common.*
import cats.effect.unsafe.implicits.global

// Create a function call
val call = FunctionCall(
  name = "Calculator_add",
  args = Struct(Map(
    "a" -> Value.number(10),
    "b" -> Value.number(5)
  ))
)

// Dispatch and get result
val result = dispatcher.dispatch(call).unsafeRunSync()
result match
  case Dispatcher.Result.Response(response) =>
    println(s"Response: ${response.response}")
  case Dispatcher.Result.HumanInTheLoop =>
    println("Human approval required")
```

## Function Declarations

Get metadata about available functions:

```scala mdoc
val declarations = dispatcher.getFunctionDeclarations.unsafeRunSync()
declarations.foreach { decl =>
  println(s"- ${decl.name}: ${decl.description.getOrElse("No description")}")
}
```

## Advanced Features

### Effect Transformation

Transform between effect types:

```scala mdoc:silent
import cats.~>
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

// Transform IO to Future
val futureDispatcher: Dispatcher[Future] = 
  dispatcher.mapK(new (IO ~> Future) {
    def apply[A](fa: IO[A]): Future[A] = fa.unsafeToFuture()
  })
```

### Observability

Add tracing and logging:

The Dispatcher can be wrapped with observability (tracing and logging):

```scala
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.log4cats.StructuredLogger

// Wrap with observability (requires implicit Tracer and Logger)
val observedDispatcher = Dispatcher[IO](dispatcher)
```

For testing without observability, use the dispatcher directly or provide no-op implementations.

## Error Handling

The Dispatcher handles various error cases:

- **Missing function** - Throws `RuntimeException` if function not found
- **Invalid arguments** - Throws `IllegalArgumentException` with detailed decoding errors
- **Wrong types** - Reports expected vs actual types
- **Runtime failures** - Propagates exceptions from your implementations

## Schema Generation Details

The macro generates JSON schemas automatically:

- **Primitives** - Int, Double, String, Boolean
- **Options** - Marked as nullable in schema
- **Collections** - Arrays with item types
- **Case classes** - Objects with properties
- **Sealed traits** - Enums with variant names

## Best Practices

1. **Add docstrings** - They become function descriptions
2. **Use descriptive names** - "Calculator_add" is clearer than "add"
3. **Prefer specific types** - Use case classes over Maps
4. **Handle errors** - Return Either or raise exceptions
5. **Use Option for optional** - Rather than default values
