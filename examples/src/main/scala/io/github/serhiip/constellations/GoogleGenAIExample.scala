package io.github.serhiip.constellations

import java.util.UUID
import java.net.URI

import cats.effect.{IO, IOApp}
import cats.effect.std.{Console, Env}

import org.typelevel.log4cats.StructuredLogger

import io.github.serhiip.constellations.dispatcher.ValueEncoder
import io.github.serhiip.constellations.executor.Stateful
import io.github.serhiip.constellations.google.Client
import io.github.serhiip.constellations.handling.GoogleGenAI as HandlingGoogle
import com.google.genai.types.GenerateContentResponse
import io.github.serhiip.constellations.invoker.GoogleGenAI
import org.typelevel.log4cats.slf4j.Slf4jFactory
import cats.effect.Clock
import cats.Functor
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import java.time.ZoneId
import java.time.OffsetDateTime

final case class CurrentTime(time: String) derives ValueEncoder
final case class AlertResult(message: String) derives ValueEncoder

trait CallableFunctions[F[_]]:
  def getCurrentTime(zone: Option[String]): F[CurrentTime]
  def alert(text: String): F[AlertResult]

class DefaultFunctions[F[_]: Clock: Functor: Logger] extends CallableFunctions[F]:
  def getCurrentTime(zone: Option[String]): F[CurrentTime] =
    for {
      instant <- Clock[F].realTimeInstant
      nowZoned = OffsetDateTime.ofInstant(instant, ZoneId.of(zone.getOrElse("UTC")))
    } yield CurrentTime(nowZoned.toString)

  def alert(text: String): F[AlertResult] =
    Logger[F]
      .info(s"ALERT: $text")
      .as(AlertResult(text))

@scala.annotation.experimental
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
                                   invoker,
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
