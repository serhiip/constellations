package io.github.serhiip.constellations

import java.util.UUID
import java.net.URI

import cats.effect.{IO, IOApp}
import cats.effect.std.{Console, Env}

import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.trace.Tracer.Implicits.noop

import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.executor.Stateful
import io.github.serhiip.constellations.google.Client
import fs2.io.file.{Files as Fs2Files}
import io.github.serhiip.constellations.handling.GoogleGenAI as HandlingGoogle
import com.google.genai.types.GenerateContentResponse
import io.github.serhiip.constellations.invoker.GoogleGenAI
import org.typelevel.log4cats.slf4j.Slf4jFactory
import io.github.serhiip.constellations.common.{Struct, Value, FunctionResponse}
import scala.annotation.experimental
import cats.effect.Clock
import cats.Applicative
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import java.time.ZoneId
import java.time.OffsetDateTime

trait CallableFunctions[F[_]]:
  def getCurrentTime(zone: Option[String]): F[Dispatcher.Result]
  def alert(text: String): F[Dispatcher.Result]

class DefaultFunctions[F[_]: Clock: Applicative: Logger] extends CallableFunctions[F]:
  def getCurrentTime(zone: Option[String]): F[Dispatcher.Result] =
    for {
      instant <- Clock[F].realTimeInstant
      nowZoned = OffsetDateTime.ofInstant(instant, ZoneId.of(zone.getOrElse("UTC")))
    } yield Dispatcher.Result.Response(
      FunctionResponse("getCurrentTime", Struct("time" -> Value.string(nowZoned.toString)))
    )

  def alert(text: String): F[Dispatcher.Result] =
    Logger[F]
      .info(s"ALERT: $text")
      .as(Dispatcher.Result.Response(FunctionResponse("echo", Struct("text" -> Value.string(text)))))

@experimental
object GoogleGenAIExample extends IOApp.Simple:
  def run: IO[Unit] =

    val factory = Slf4jFactory.create[IO]

    for {
      given StructuredLogger[IO] <- factory.create

      callableFunctions = DefaultFunctions[IO]

      projectOpt <- Env[IO].get("GOOGLE_CLOUD_PROJECT")
      project    <- projectOpt.liftTo[IO](new RuntimeException("GOOGLE_CLOUD_PROJECT is not set"))

      locationOpt <- Env[IO].get("GOOGLE_CLOUD_LOCATION")
      location     = locationOpt.getOrElse("europe-west4")

      modelOpt <- Env[IO].get("GOOGLE_GENAI_MODEL")
      model     = modelOpt.getOrElse("gemini-2.5-pro")

      result <- Client
                  .resource[IO](Client.Config(project = project, location = location))
                  .use { client =>

                    val dispatcher = Dispatcher.generate[IO, CallableFunctions](callableFunctions)

                    for
                      decls   <- dispatcher.getFunctionDeclarations
                      _       <- Logger[IO].info(s"Dispatcher: ${decls.map(_.name).mkString(", ")}")
                      invoker  = GoogleGenAI.chatCompletion(
                                   client,
                                   GoogleGenAI.Config(
                                     model = model,
                                     temperature = 2f.some,
                                     systemPrompt =
                                       "You are a helpful assistant that can answer questions and help with tasks. Use full function name when replying with function calls.".some
                                   ),
                                   decls
                                 )
                      handling = HandlingGoogle[IO]
                      files   <- Files[IO](URI.create("file:///tmp/"))
                      executor = Stateful[IO, GenerateContentResponse](
                                   Stateful.Config(functionCallLimit = 5),
                                   handling,
                                   Invoker[IO, GenerateContentResponse](invoker),
                                   files
                                 )
                      memory  <- Memory.inMemory[IO, UUID]
                      _       <- IO.println("Type 'exit' to quit.\n")
                      _       <- replLoop(dispatcher, executor, memory)
                    yield ()
                  }
                  .handleErrorWith { error => IO.println(s"Error occurred: ${error.getMessage}") }

    } yield result

  private def replLoop(
      dispatcher: Dispatcher[IO],
      executor: Stateful[IO, com.google.genai.types.GenerateContentResponse],
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
