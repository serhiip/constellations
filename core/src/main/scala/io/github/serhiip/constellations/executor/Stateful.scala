package io.github.serhiip.constellations.executor

import java.net.URI

import cats.data.Validated.{Invalid, Valid}
import cats.data.{Chain, NonEmptyChain as NEC, RWST}
import cats.effect.Clock
import cats.kernel.Monoid
import cats.syntax.all.*
import cats.{MonadThrow, Parallel}

import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.Tracer

import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Observability.*

object Stateful:

  case class Config(
      functionCallLimit: Int,
      agentErrorRetryLimit: Int = 3,
      agentErrorInstruction: String =
        "Tool call failed. Correct the function name and/or arguments to match the tool schema, then retry the tool call.",
      skippedCallInstruction: String =
        "This tool call was not executed because other tool calls in the same batch failed validation. Resend it after fixing those errors."
  )

  enum Failure:
    case Interrupted
    case AgentRetriesExhausted(errors: NEC[AgentError])

  def apply[F[_]: Clock: Parallel: MonadThrow: Tracer: StructuredLogger, T: Handling](
      config: Config,
      invoker: Invoker[F, T],
      files: Files[F]
  )(using AssetsHandling[F, T]): Executor[F, Failure, Executor.Step.ModelResponse] =
    new Observed(config, summon, summon, invoker, files)

  def pure[F[_]: Clock: Parallel: MonadThrow, T: Handling](config: Config, invoker: Invoker[F, T], files: Files[F])(using
      AssetsHandling[F, T]
  ): Executor[F, Failure, Executor.Step.ModelResponse] =
    Pure(config, summon, summon, invoker, files)

  private case class State(iteration: Int, steps: Chain[Executor.Step], shouldInterrupt: Boolean, agentErrorRetries: Int)
  private object State:
    given Monoid[State] with
      override def combine(x: State, y: State): State =
        State(
          iteration = x.iteration + y.iteration,
          steps = x.steps |+| y.steps,
          shouldInterrupt = x.shouldInterrupt || y.shouldInterrupt,
          agentErrorRetries = x.agentErrorRetries + y.agentErrorRetries
        )
      override def empty: State                       = State(0, Chain.empty, false, 0)

  private class Pure[F[_]: Clock: Parallel: MonadThrow, T](
      config: Config,
      responseHandling: Handling[T],
      assetsHandling: AssetsHandling[F, T],
      invoker: Invoker[F, T],
      files: Files[F]
  ) extends Executor[F, Failure, Executor.Step.ModelResponse]:

    override def execute(
        callDispatcher: ToolDispatcher[F],
        history: Memory[F, ?],
        query: String,
        assets: List[URI]
    ): F[Either[Failure, Executor.Step.ModelResponse]] =
      for
        now      <- Clock[F].offsetDateTimeUtc
        queryStep = Executor.Step.UserQuery(query, now, assets)
        _        <- history.record(queryStep)
        result   <- resume(callDispatcher, history)
      yield result

    override def resume(callDispatcher: ToolDispatcher[F], history: Memory[F, ?]): F[Either[Failure, Executor.Step.ModelResponse]] =
      for
        previousSteps <- history.retrieve
        converted     <- previousSteps.traverse(messageFromStep)
        allSteps       = NEC.fromChain(converted).get // TODO: handle empty history in resume
        runResult     <- persistentLoop(callDispatcher, history).runEmpty(allSteps)
        (_, _, result) = runResult
        _             <- result.traverse(history.record)
      yield result

    private def persistentLoop(
        callDispatcher: ToolDispatcher[F],
        history: Memory[F, ?]
    ): Ctx[Either[Failure, Executor.Step.ModelResponse]] =
      Ctx.shouldInterrupt.ifM(Failure.Interrupted.asLeft[Executor.Step.ModelResponse].pure[Ctx], eval(callDispatcher, history))

    private def eval(callDispatcher: ToolDispatcher[F], history: Memory[F, ?]): Ctx[Either[Failure, Executor.Step.ModelResponse]] =
      for
        content    <- Ctx.allContent
        response   <- Ctx.liftF(invoker.generate(content))
        _          <- Ctx.tell(Chain.one(responseHandling.finishReason(response)))
        uris       <- Ctx.liftF(persistImages(response))
        calls      <- Ctx.liftF(responseHandling.getFunctionCalls(response).liftTo)
        dispatched <- Ctx.liftF(callDispatcher.dispatchAll(calls))
        failure    <- dispatched match
                        case Invalid(errors) => reportBatchFailure(history, calls, errors)
                        case Valid(results)  => Ctx.resetAgentErrorRetries >> persistResults(history, calls.zip(results)).as(none[Failure])
        iteration  <- Ctx.getIteration
        reply      <- failure.fold(
                        if calls.nonEmpty && iteration < config.functionCallLimit then Ctx.increment >> persistentLoop(callDispatcher, history)
                        else
                          Ctx
                            .liftF(Clock[F].offsetDateTimeUtc)
                            .map(Executor.Step.ModelResponse(responseHandling.getTextFromResponse(response), _, uris).asRight)
                      )(_.asLeft.pure[Ctx])
      yield reply

    private def persistImages(response: T): F[List[URI]] =
      for
        images <- assetsHandling.getImages(response)
        now    <- Clock[F].offsetDateTimeUtc
        uris   <- images.zipWithIndex.parTraverse { (image, idx) =>
                    val fileName = s"generated-image-${now.toInstant.toEpochMilli}-$idx.${image.extension}"
                    for
                      uri <- files.resolve(fileName)
                      _   <- files.writeStream(uri, image.bytes)
                    yield uri
                  }
      yield uris

    private def persistResults(history: Memory[F, ?], callResults: List[(FunctionCall, ToolDispatcher.Result)]): Ctx[Unit] =
      callResults.traverse_ { case (call, result) =>
        for
          now <- Ctx.liftF(Clock[F].offsetDateTimeUtc)
          _   <- persist(history, Executor.Step.Call(call, now))
          _   <- result match
                   case ToolDispatcher.Result.Response(response) =>
                     Ctx
                       .liftF(Clock[F].offsetDateTimeUtc)
                       .flatMap(at => persist(history, Executor.Step.Response(result = response, at = at)))
                   case ToolDispatcher.Result.HumanInTheLoop     => Ctx.interrupt
        yield ()
      }

    protected def reportBatchFailure(history: Memory[F, ?], calls: List[FunctionCall], errors: NEC[AgentError]): Ctx[Option[Failure]] =
      val errorsByCallId = errors.foldLeft(Map.empty[Option[String], NEC[AgentError]]): (acc, error) =>
        acc.updatedWith(error.functionCall.callId):
          case Some(existing) => existing.append(error).some
          case None           => NEC.one(error).some
      for
        retries <- Ctx.getAgentErrorRetries
        canRetry = retries < config.agentErrorRetryLimit
        _       <- calls.traverse_(persistBatchCallResult(history, errorsByCallId))
        _       <- Ctx.incrementAgentErrorRetries.whenA(canRetry)
      yield Option.unless(canRetry)(Failure.AgentRetriesExhausted(errors))

    private def persistBatchCallResult(history: Memory[F, ?], errorsByCallId: Map[Option[String], NEC[AgentError]])(
        call: FunctionCall
    ): Ctx[Unit] =
      val response = errorsByCallId.get(call.callId) match
        case Some(callErrors) => FunctionResponse.error(call, callErrors.mkString_(delim = "; "), config.agentErrorInstruction)
        case None             => FunctionResponse.skipped(call, config.skippedCallInstruction)
      for
        now <- Ctx.liftF(Clock[F].offsetDateTimeUtc)
        _   <- persist(history, Executor.Step.Call(call, now))
        at  <- Ctx.liftF(Clock[F].offsetDateTimeUtc)
        _   <- persist(history, Executor.Step.Response(result = response, at = at))
      yield ()

    private def persist(history: Memory[F, ?], step: Executor.Step) = Ctx.add(step) >> Ctx.liftF(history.record(step))

    private def messageFromStep(in: Executor.Step): F[Message] = in match
      case Executor.Step.UserQuery(text, at, assets)     =>
        assets
          .parTraverse(files.readFileAsBase64)
          .map { base64Encoded =>
            Message.User(ContentPart.Text(text) :: base64Encoded.map(ContentPart.Image.apply))
          }
      case Executor.Step.ModelResponse(text, at, assets) =>
        assets
          .parTraverse(files.readFileAsBase64)
          .map { base64Images =>
            Message.Assistant(text, base64Images.map(ContentPart.Image.apply))
          }
      case Executor.Step.Call(call, at)                  => Message.Tool(call).pure[F]
      case Executor.Step.Response(result, at)            => Message.ToolResult(result).pure[F]
      case Executor.Step.UserReply(text, at, _)          => Message.System(text).pure[F]
      case Executor.Step.System(content, at)             => Message.System(content).pure[F]

    protected type Ctx[A] = RWST[F, NEC[Message], Chain[FinishReason], State, A]
    protected object Ctx:
      export RWST.*
      def liftF[A](it: F[A]): Ctx[A]            = RWST.liftF(it)
      def add(e: Executor.Step): Ctx[Unit]      = modify(s => s.copy(steps = s.steps.append(e)))
      def increment: Ctx[Unit]                  = modify(s => s.copy(iteration = s.iteration + 1))
      def incrementAgentErrorRetries: Ctx[Unit] = modify(s => s.copy(agentErrorRetries = s.agentErrorRetries + 1))
      def resetAgentErrorRetries: Ctx[Unit]     = modify(s => s.copy(agentErrorRetries = 0))
      def getIteration: Ctx[Int]                = inspect(_.iteration)
      def getAgentErrorRetries: Ctx[Int]        = inspect(_.agentErrorRetries)
      def getSteps: Ctx[Chain[Executor.Step]]   = inspect(_.steps)
      def ask: Ctx[NEC[Message]]                = RWST.ask
      def allContent: Ctx[NEC[Message]]         =
        for
          initial     <- ask
          accumulated <- getSteps
          converted   <- liftF(accumulated.traverse(messageFromStep))
        yield initial.appendChain(converted)
      def interrupt: Ctx[Unit]                  = modify(s => s.copy(shouldInterrupt = true))
      def shouldInterrupt: Ctx[Boolean]         = inspect(_.shouldInterrupt)

  private class Observed[F[_]: Clock: Parallel: MonadThrow: Tracer: StructuredLogger, T](
      config: Config,
      responseHandling: Handling[T],
      assetsHandling: AssetsHandling[F, T],
      invoker: Invoker[F, T],
      files: Files[F]
  ) extends Pure[F, T](config, responseHandling, assetsHandling, invoker, files):

    override protected def reportBatchFailure(
        history: Memory[F, ?],
        calls: List[FunctionCall],
        errors: NEC[AgentError]
    ): Ctx[Option[Failure]] =
      for
        retries <- Ctx.getAgentErrorRetries
        kinds    = errors
                     .map {
                       case AgentError.ArgumentDecodingFailed(_, _) => "argument-decoding-failed"
                       case AgentError.UnknownFunction(_)           => "unknown-function"
                     }
                     .toList
                     .distinct
                     .mkString(",")
        attrs    = List(
                     Attribute("retries_left", (config.agentErrorRetryLimit - retries).toLong),
                     Attribute("error", kinds),
                     Attribute("agent_error_count", errors.length.toLong)
                   )
        result  <- super.reportBatchFailure(history, calls, errors)
        _       <- Ctx.liftF:
                     for
                       span <- Tracer[F].currentSpanOrNoop
                       _    <- span.addAttributes(attrs*)
                       _    <- span.logged: logger =>
                                 val message =
                                   s"AgentError batch (${errors.length} error(s), attempt ${retries + 1}/${config.agentErrorRetryLimit}): ${errors.map(_.getMessage).mkString_("; ")}"
                                 result.fold(logger.warn(message))(_ => logger.error(message))
                     yield ()
      yield result
