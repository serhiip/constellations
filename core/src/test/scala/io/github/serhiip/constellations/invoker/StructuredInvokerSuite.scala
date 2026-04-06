package io.github.serhiip.constellations.invoker

import cats.MonadThrow
import cats.data.NonEmptyChain as NEC
import cats.effect.IO
import cats.effect.kernel.Ref
import io.circe.parser as circeParser
import io.github.serhiip.constellations.invoker.StructuredInvoker
import io.github.serhiip.constellations.{Handling, Invoker}
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Codecs.given
import io.github.serhiip.constellations.dispatcher.Decoder
import munit.CatsEffectSuite
import cats.syntax.either.*

final case class RawResponse(text: String)

object IdentityHandling:
  def apply[F[_]: MonadThrow]: Handling[F, RawResponse] =
    new Handling[F, RawResponse]:
      def getTextFromResponse(r: RawResponse) = MonadThrow[F].pure(Some(r.text))
      def getFunctinoCalls(r: RawResponse)    = MonadThrow[F].pure(Nil)
      def finishReason(r: RawResponse)        = MonadThrow[F].pure(FinishReason.Stop)
      def structuredOutput(r: RawResponse)    =
        circeParser.decode[Struct](r.text) match
          case Right(s)  => MonadThrow[F].pure(s)
          case Left(err) =>
            MonadThrow[F].raiseError(
              IllegalArgumentException(s"Failed to parse structured output as JSON object: ${err.getMessage()}")
            )
      def getImages(r: RawResponse)           = MonadThrow[F].pure(Nil)

class StructuredInvokerSuite extends CatsEffectSuite:

  import Decoder.given

  final case class SimpleModel(name: String, age: Int)

  private def sampleHistory: NEC[Message] = NEC.one(Message.User(List(ContentPart.Text("hello"))))

  test("delegates generate to underlying invoker") {
    val history  = sampleHistory
    val goodJson = """{"name":"Alice","age":30}"""
    for
      ref      <- Ref[IO].of[Option[NEC[Message]]](None)
      delegate  = new Invoker[IO, RawResponse]:
                    def generate(h: NEC[Message]): IO[RawResponse] =
                      ref.set(Some(h)).flatMap(_ => IO.pure(RawResponse(goodJson)))
      handling  = IdentityHandling[IO]
      invoker   = StructuredInvoker.apply[IO, RawResponse, SimpleModel](delegate, handling)
      result   <- invoker.generate(history)
      captured <- ref.get
    yield
      assertEquals(result, SimpleModel("Alice", 30))
      assertEquals(captured, Some(history))
  }

  test("fails with StructuredDecodingFailed when struct does not decode to T") {
    val history  = sampleHistory
    val badJson  = """{"other":"Alice"}"""
    val delegate = new Invoker[IO, RawResponse]:
      def generate(h: NEC[Message]): IO[RawResponse] =
        IO.pure(RawResponse(badJson))
    val handling = IdentityHandling[IO]
    val invoker  = StructuredInvoker.apply[IO, RawResponse, SimpleModel](delegate, handling)
    for
      attempt <- invoker.generate(history).attempt
      message  = attempt.left.map(_.getMessage())
    yield assertEquals(
      message,
      "Error at path 'name': Field is missing.; Error at path 'age': Field is missing.".asLeft
    )
  }
