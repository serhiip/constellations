package io.github.serhiip.constellations.executor

import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyChain as NEC, ValidatedNec}
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.all.*
import munit.CatsEffectSuite

import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.dispatcher.Decoder

final case class TestResponse(text: Option[String], calls: List[FunctionCall])

object TestResponseHandling:
  given Handling[TestResponse] with
    def getTextFromResponse(r: TestResponse): Option[String]                     = r.text
    def getFunctionCalls(r: TestResponse): Either[Throwable, List[FunctionCall]] = r.calls.asRight
    def finishReason(r: TestResponse): FinishReason                              =
      if r.calls.nonEmpty then FinishReason.ToolCalls else FinishReason.Stop
    def structuredOutput(r: TestResponse): Either[Throwable, Struct]             = Struct.empty.asRight

  given AssetsHandling[IO, TestResponse] with
    def getImages(response: TestResponse): IO[List[GeneratedImage[IO]]] = List.empty.pure[IO]

class StatefulSuite extends CatsEffectSuite:

  import TestResponseHandling.given

  private val noopFiles: Files[IO] = new:
    def readFileAsBase64(uri: URI): IO[String]                          = IO.pure("")
    def writeStream(target: URI, bytes: fs2.Stream[IO, Byte]): IO[Unit] = IO.unit
    def resolve(relative: String): IO[URI]                              = IO.pure(URI.create(s"file:///tmp/$relative"))

  private def scriptedInvoker(responses: Ref[IO, List[TestResponse]]): Invoker[IO, TestResponse] =
    new:
      def generate(history: NEC[Message]): IO[TestResponse] =
        responses.modify {
          case head :: tail => (tail, head)
          case Nil          => (Nil, TestResponse(Some("no more responses"), Nil))
        }

  private def dispatcherFromPrepare(prepareFn: FunctionCall => ValidatedNec[AgentError, IO[ToolDispatcher.Result]]): ToolDispatcher[IO] =
    new:
      def prepare(call: FunctionCall): ValidatedNec[AgentError, IO[ToolDispatcher.Result]] =
        prepareFn(call)

      def dispatch(call: FunctionCall): IO[ToolDispatcher.Result] =
        prepare(call) match
          case Valid(run)    => run
          case Invalid(errs) => errs.head.raiseError[IO, ToolDispatcher.Result]

      def dispatchAll(calls: List[FunctionCall]): IO[ValidatedNec[AgentError, List[ToolDispatcher.Result]]] =
        calls.traverse(prepare) match
          case Valid(runs)     => runs.sequence.map(Valid(_))
          case Invalid(errors) => (errors.invalid: ValidatedNec[AgentError, List[ToolDispatcher.Result]]).pure[IO]

      def getFunctionDeclarations: IO[List[FunctionDeclaration]] = List.empty.pure[IO]

  private def failingThenSucceedingDispatcher(attempts: AtomicInteger, failuresBeforeSuccess: Int): ToolDispatcher[IO] =
    dispatcherFromPrepare { call =>
      val n = attempts.getAndIncrement()
      if n < failuresBeforeSuccess then
        AgentError
          .ArgumentDecodingFailed(
            call,
            NEC.one(Decoder.Error.InvalidStringValue("when", "bad", "OffsetDateTime"))
          )
          .invalidNec
      else
        ToolDispatcher.Result
          .Response(FunctionResponse(call, Struct("value" -> Value.string("ok"))))
          .pure[IO]
          .validNec
    }

  private def alwaysFailingDispatcher(mkError: FunctionCall => AgentError): ToolDispatcher[IO] =
    dispatcherFromPrepare(call => mkError(call).invalidNec)

  private val toolCall = FunctionCall("ScheduleApi_at", Struct("when" -> Value.string("bad")), None)

  test("retries AgentError and succeeds when the model corrects arguments") {
    for
      responses <- Ref[IO].of(
                     List(
                       TestResponse(None, List(toolCall)),
                       TestResponse(None, List(toolCall)),
                       TestResponse(Some("scheduled"), Nil)
                     )
                   )
      attempts   = AtomicInteger(0)
      memory    <- Memory.inMemory[IO, UUID]
      executor   = Stateful.pure[IO, TestResponse](
                     Stateful.Config(functionCallLimit = 5, agentErrorRetryLimit = 3),
                     scriptedInvoker(responses),
                     noopFiles
                   )
      dispatcher = failingThenSucceedingDispatcher(attempts, failuresBeforeSuccess = 1)
      result    <- executor.execute(dispatcher, memory, "schedule something")
      steps     <- memory.retrieve
    yield
      assertEquals(result.map(_.text), Right(Some("scheduled")))
      assertEquals(attempts.get(), 2)
      assert(steps.exists {
        case Executor.Step.Response(fr, _) => fr.response.fields.contains("error")
        case _                             => false
      })
  }

  test("returns AgentRetriesExhausted when retry limit is reached") {
    for
      responses <- Ref[IO].of(
                     List.fill(5)(TestResponse(None, List(toolCall)))
                   )
      memory    <- Memory.inMemory[IO, UUID]
      executor   = Stateful.pure[IO, TestResponse](
                     Stateful.Config(functionCallLimit = 10, agentErrorRetryLimit = 2),
                     scriptedInvoker(responses),
                     noopFiles
                   )
      result    <- executor.execute(
                     alwaysFailingDispatcher(call =>
                       AgentError.ArgumentDecodingFailed(
                         call,
                         NEC.one(Decoder.Error.InvalidStringValue("when", "bad", "OffsetDateTime"))
                       )
                     ),
                     memory,
                     "schedule something"
                   )
    yield result match
      case Left(Stateful.Failure.AgentRetriesExhausted(errors)) =>
        errors.head match
          case err: AgentError.ArgumentDecodingFailed => assertEquals(err.call.name, "ScheduleApi_at")
          case other                                  => fail(s"Unexpected error: $other")
      case other => fail(s"Expected AgentRetriesExhausted, got $other")
  }

  test("resets agent error retry counter after a successful function call") {
    val badCall  = FunctionCall("ScheduleApi_at", Struct("when" -> Value.string("bad")), "call-bad".some)
    val goodCall = FunctionCall("ScheduleApi_at", Struct("when" -> Value.string("ok")), "call-ok".some)
    val dispatcher = dispatcherFromPrepare {
      case call if call.args.fields.get("when").contains(Value.string("bad")) =>
        AgentError
          .ArgumentDecodingFailed(
            call,
            NEC.one(Decoder.Error.InvalidStringValue("when", "bad", "OffsetDateTime"))
          )
          .invalidNec
      case call                                                               =>
        ToolDispatcher.Result
          .Response(FunctionResponse(call, Struct("value" -> Value.string("ok"))))
          .pure[IO]
          .validNec
    }
    for
      // With limit=1, a failure sets retries=1. Without a reset after the successful call,
      // the next failure would exhaust immediately instead of being allowed to retry.
      responses <- Ref[IO].of(
                     List(
                       TestResponse(None, List(badCall)),
                       TestResponse(None, List(goodCall)),
                       TestResponse(None, List(badCall)),
                       TestResponse(Some("done"), Nil)
                     )
                   )
      memory    <- Memory.inMemory[IO, UUID]
      executor   = Stateful.pure[IO, TestResponse](
                     Stateful.Config(functionCallLimit = 10, agentErrorRetryLimit = 1),
                     scriptedInvoker(responses),
                     noopFiles
                   )
      result    <- executor.execute(dispatcher, memory, "schedule something")
      steps     <- memory.retrieve
      errorCount = steps.count {
                     case Executor.Step.Response(fr, _) => fr.response.fields.contains("error")
                     case _                             => false
                   }
    yield
      assertEquals(result.map(_.text), Right(Some("done")))
      assertEquals(errorCount, 2L)
  }

  test("dispatchAll executes effects when every call is Valid") {
    val call     = FunctionCall("GreetingApi_greet", Struct("name" -> Value.string("Ada")))
    val executed = AtomicInteger(0)
    val dispatcher = dispatcherFromPrepare { c =>
      (IO.delay(executed.incrementAndGet()) *>
        ToolDispatcher.Result
          .Response(FunctionResponse(c, Struct("value" -> Value.string("Hello, Ada"))))
          .pure[IO]).validNec
    }
    for
      responses <- Ref[IO].of(
                     List(
                       TestResponse(None, List(call)),
                       TestResponse(Some("done"), Nil)
                     )
                   )
      memory    <- Memory.inMemory[IO, UUID]
      executor   = Stateful.pure[IO, TestResponse](
                     Stateful.Config(functionCallLimit = 5, agentErrorRetryLimit = 3),
                     scriptedInvoker(responses),
                     noopFiles
                   )
      result    <- executor.execute(dispatcher, memory, "greet")
      steps     <- memory.retrieve
      okStep     = steps.collectFirst {
                     case Executor.Step.Response(fr, _) if fr.response.fields.get("value").contains(Value.string("Hello, Ada")) => fr
                   }
    yield
      assertEquals(result.map(_.text), Right(Some("done")))
      assertEquals(executed.get(), 1)
      assert(okStep.isDefined)
  }

  test("dispatchAll reports AgentError without executing when prepare is Invalid") {
    val call       = FunctionCall("ScheduleApi_at", Struct("when" -> Value.string("bad")))
    val dispatcher = dispatcherFromPrepare { c =>
      AgentError
        .ArgumentDecodingFailed(
          c,
          NEC.one(Decoder.Error.InvalidStringValue("when", "bad", "OffsetDateTime"))
        )
        .invalidNec
    }
    for
      responses <- Ref[IO].of(
                     List(
                       TestResponse(None, List(call)),
                       TestResponse(Some("done"), Nil)
                     )
                   )
      memory    <- Memory.inMemory[IO, UUID]
      executor   = Stateful.pure[IO, TestResponse](
                     Stateful.Config(functionCallLimit = 5, agentErrorRetryLimit = 3),
                     scriptedInvoker(responses),
                     noopFiles
                   )
      result    <- executor.execute(dispatcher, memory, "schedule something")
      steps     <- memory.retrieve
      errorStep  = steps.collectFirst {
                     case Executor.Step.Response(fr, _) if fr.response.fields.contains("error") => fr
                   }
    yield
      assertEquals(result.map(_.text), Right(Some("done")))
      assert(errorStep.isDefined)
      assert(errorStep.get.response.fields.contains("system_instruction"))
  }

  test("invalid batch does not execute valid sibling calls and reports skipped plus errors") {
    val okCall     = FunctionCall("GreetingApi_greet", Struct("name" -> Value.string("Ada")), "call-ok".some)
    val badCall    = FunctionCall("ScheduleApi_at", Struct("when" -> Value.string("bad")), "call-bad".some)
    val executed   = AtomicInteger(0)
    val dispatcher = dispatcherFromPrepare {
      case call if call.name == okCall.name =>
        (IO.delay(executed.incrementAndGet()) *>
          ToolDispatcher.Result
            .Response(FunctionResponse(call, Struct("value" -> Value.string("ok"))))
            .pure[IO]).validNec
      case call                             =>
        AgentError
          .ArgumentDecodingFailed(
            call,
            NEC.one(Decoder.Error.InvalidStringValue("when", "bad", "OffsetDateTime"))
          )
          .invalidNec
    }
    for
      responses <- Ref[IO].of(
                     List(
                       TestResponse(None, List(badCall, okCall)),
                       TestResponse(Some("done"), Nil)
                     )
                   )
      memory    <- Memory.inMemory[IO, UUID]
      executor   = Stateful.pure[IO, TestResponse](
                     Stateful.Config(functionCallLimit = 5, agentErrorRetryLimit = 3),
                     scriptedInvoker(responses),
                     noopFiles
                   )
      result    <- executor.execute(dispatcher, memory, "schedule then greet")
      steps     <- memory.retrieve
      errorStep  = steps.collectFirst {
                     case Executor.Step.Response(fr, _) if fr.response.fields.contains("error") => fr
                   }
      skipStep   = steps.collectFirst {
                     case Executor.Step.Response(fr, _) if fr.response.fields.contains("skipped") => fr
                   }
    yield
      assertEquals(result.map(_.text), Right(Some("done")))
      assertEquals(executed.get(), 0)
      assert(errorStep.isDefined)
      assert(skipStep.isDefined)
      assert(errorStep.get.response.fields.contains("system_instruction"))
      assert(!errorStep.get.response.fields.contains("completed_functions"))
  }

  test("retries UnknownFunction the same way as argument decoding failures") {
    for
      responses <- Ref[IO].of(
                     List.fill(5)(TestResponse(None, List(FunctionCall("Missing_tool", Struct.empty))))
                   )
      memory    <- Memory.inMemory[IO, UUID]
      executor   = Stateful.pure[IO, TestResponse](
                     Stateful.Config(functionCallLimit = 10, agentErrorRetryLimit = 1),
                     scriptedInvoker(responses),
                     noopFiles
                   )
      result    <- executor.execute(alwaysFailingDispatcher(AgentError.UnknownFunction(_)), memory, "call missing tool")
    yield result match
      case Left(Stateful.Failure.AgentRetriesExhausted(errors)) =>
        errors.head match
          case err: AgentError.UnknownFunction => assertEquals(err.call.name, "Missing_tool")
          case other                           => fail(s"Unexpected error: $other")
      case other => fail(s"Expected AgentRetriesExhausted(UnknownFunction), got $other")
  }

  test("matches batch AgentErrors by callId when the same function appears twice") {
    val badA       = FunctionCall("ScheduleApi_at", Struct("when" -> Value.string("bad-a")), "call-a".some)
    val badB       = FunctionCall("ScheduleApi_at", Struct("when" -> Value.string("bad-b")), "call-b".some)
    val dispatcher = dispatcherFromPrepare { call =>
      AgentError
        .ArgumentDecodingFailed(
          call,
          NEC.one(Decoder.Error.InvalidStringValue("when", "bad", "OffsetDateTime"))
        )
        .invalidNec
    }
    for
      responses <- Ref[IO].of(
                     List(
                       TestResponse(None, List(badA, badB)),
                       TestResponse(Some("done"), Nil)
                     )
                   )
      memory    <- Memory.inMemory[IO, UUID]
      executor   = Stateful.pure[IO, TestResponse](
                     Stateful.Config(functionCallLimit = 5, agentErrorRetryLimit = 3),
                     scriptedInvoker(responses),
                     noopFiles
                   )
      result    <- executor.execute(dispatcher, memory, "schedule twice")
      steps     <- memory.retrieve
      errorIds   = steps.collect {
                     case Executor.Step.Response(fr, _) if fr.response.fields.contains("error") => fr.call.callId
                   }
    yield
      assertEquals(result.map(_.text), Right(Some("done")))
      assertEquals(errorIds.toList.toSet, Set("call-a".some, "call-b".some))
  }
