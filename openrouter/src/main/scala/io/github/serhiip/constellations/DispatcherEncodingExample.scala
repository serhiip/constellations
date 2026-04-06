package io.github.serhiip.constellations

import java.net.URI
import java.util.UUID


import cats.Applicative
import cats.effect.{IO, IOApp}
import cats.effect.std.{Console, Env}
import cats.syntax.all.*

import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import org.typelevel.otel4s.metrics.Meter.Implicits.noop
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.trace.Tracer.Implicits.noop

import io.github.serhiip.constellations.dispatcher.ValueEncoder
import io.github.serhiip.constellations.executor.Stateful
import io.github.serhiip.constellations.handling.OpenRouter as ORHandling
import io.github.serhiip.constellations.invoker.OpenRouter
import io.github.serhiip.constellations.openrouter.{ChatCompletionResponse, Client}

final case class WeatherReport(city: String, temperatureC: Double, condition: String) derives ValueEncoder
final case class StoredNote(id: String) derives ValueEncoder

enum OperationStatus derives ValueEncoder:
  case Ok(message: String)
  case Warning(details: String)

trait ExampleFunctions[F[_]]:
  def getWeather(city: String): F[WeatherReport]
  def storeNote(note: String): F[StoredNote]
  def status(): F[OperationStatus]

class ExampleFunctionsImpl[F[_]: Applicative] extends ExampleFunctions[F]:
  def getWeather(city: String): F[WeatherReport] =
    WeatherReport(city, 21.5, "Sunny").pure[F]

  def storeNote(note: String): F[StoredNote] =
    StoredNote(s"note-${note.hashCode}").pure[F]

  def status(): F[OperationStatus] =
    OperationStatus.Ok("ready").pure[F]

trait DiagnosticFunctions[F[_]]:
  def ping(): F[String]

class DiagnosticFunctionsDefault[F[_]: Applicative] extends DiagnosticFunctions[F]:
  def ping(): F[String] =
    "pong".pure[F]

object DispatcherEncodingExample extends IOApp.Simple:
  def run: IO[Unit] =
    val factory = Slf4jFactory.create[IO]
    given LoggerFactory[IO] = factory
    factory.create.flatMap { implicit logger =>
      for
        apiKeyOpt                  <- Env[IO].get("OPENROUTER_API_KEY")
        apiKey                     <- apiKeyOpt.liftTo[IO](new RuntimeException("OPENROUTER_API_KEY is not set"))
        model                       = sys.env.getOrElse("OPENROUTER_MODEL", "google/gemini-3-pro-preview")
        _                          <- Client
                                        .resource[IO](apiKey, Client.Config())
                                        .use { client =>
                                          for
                                            dispatcher <- Dispatcher(
                                                            Dispatcher.generate[IO](
                                                              ExampleFunctionsImpl[IO],
                                                              DiagnosticFunctionsDefault[IO]
                                                            )
                                                          )
                                            decls      <- dispatcher.getFunctionDeclarations
                                            rawInvoker  = OpenRouter.chatCompletion(
                                                            client,
                                                            OpenRouter.Config(
                                                              model = model,
                                                              temperature = 0.7.some,
                                                              systemPrompt =
                                                                "You are a helpful assistant. Use the provided tools when relevant. Use full function names for tool calls.".some
                                                            ),
                                                            decls
                                                          )
                                            invoker     <- Invoker.observed(rawInvoker)
                                            handling    = ORHandling[IO]()
                                            files      <- Files[IO](URI.create("file:///tmp/"))
                                            rawExecutor = Stateful[IO, ChatCompletionResponse](
                                                            Stateful.Config(functionCallLimit = 5),
                                                            handling,
                                                            invoker,
                                                            files
                                                          )
                                            executor    = Executor(rawExecutor)
                                            rawMemory  <- Memory.inMemory[IO, UUID]
                                            memory      <- Memory(rawMemory)
                                            _          <- IO.println("Dispatcher encoding example with OpenRouter. Type 'exit' to quit.\n")
                                            _          <- replLoop(dispatcher, executor, memory)
                                          yield ()
                                        }
      yield ()
    }.handleErrorWith(err => IO.println(s"Error: ${err.getMessage}"))

  private def replLoop(
      dispatcher: Dispatcher[IO],
      executor: Executor[IO, ?, Executor.Step.ModelResponse],
      memory: Memory[IO, UUID]
  ): IO[Unit] =
    for
      _    <- Console[IO].print("You: ")
      line <- Console[IO].readLine
      _    <- if line == null || line.trim.equalsIgnoreCase("exit") then IO.println("Goodbye!")
              else
                executor
                  .execute(dispatcher, memory, line)
                  .flatMap {
                    case Right(resp) => IO.println(s"AI: ${resp.text.getOrElse("no response")}")
                    case Left(_)     => IO.println("AI: [interrupted]")
                  } >> replLoop(dispatcher, executor, memory)
    yield ()
