package io.github.serhiip.constellations.invoker

import cats.data.NonEmptyChain as NEC
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.either.*
import io.circe.parser as circeParser
import munit.CatsEffectSuite

import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Codecs.given
import io.github.serhiip.constellations.dispatcher.Decoder
import io.github.serhiip.constellations.{Handling, Invoker}

final case class RawResponse(text: String)

object IdentityHandling:
  given Handling[RawResponse] with
    def getTextFromResponse(r: RawResponse) = Some(r.text)
    def getFunctionCalls(r: RawResponse)    = Nil.asRight
    def finishReason(r: RawResponse)        = FinishReason.Stop
    def structuredOutput(r: RawResponse)    =
      circeParser
        .decode[Struct](r.text)
        .leftMap(err => IllegalArgumentException(s"Failed to parse structured output as JSON object: ${err.getMessage()}"))

class StructuredInvokerSuite extends CatsEffectSuite:

  import Decoder.given
  import IdentityHandling.given

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
      invoker   = StructuredInvoker.apply[IO, RawResponse, SimpleModel](delegate)
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
    val invoker  = StructuredInvoker.apply[IO, RawResponse, SimpleModel](delegate)
    for
      attempt <- invoker.generate(history).attempt
      message  = attempt.left.map(_.getMessage())
    yield assertEquals(
      message,
      "Error at path 'name': Field is missing.; Error at path 'age': Field is missing.".asLeft
    )
  }
