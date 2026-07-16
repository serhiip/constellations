package io.github.serhiip.constellations.executor

import java.net.URI

import cats.data.{Chain, NonEmptyChain as NEC, RWST}
import cats.effect.Clock
import cats.kernel.Monoid
import cats.syntax.all.*
import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.common.*
import cats.{MonadThrow, Parallel}

object Stateful:

  case class Config(functionCallLimit: Int)

  case object Interruption

  def apply[F[_]: Clock: Parallel: MonadThrow, T: Handling](
      config: Config,
      invoker: Invoker[F, T],
      files: Files[F]
  )(using AssetsHandling[F, T]): Stateful[F, T] =
    new Stateful[F, T](config, summon, summon, invoker, files)

  private case class State(iteration: Int, steps: Chain[Executor.Step], shouldInterrupt: Boolean)
  private object State:
    given Monoid[State] with
      override def combine(x: State, y: State): State =
        State(
          iteration = x.iteration + y.iteration,
          steps = x.steps |+| y.steps,
          shouldInterrupt = x.shouldInterrupt || y.shouldInterrupt
        )
      override def empty: State                       = State(0, Chain.empty, false)

final class Stateful[F[_]: Clock: Parallel: MonadThrow, T](
    config: Stateful.Config,
    responseHandling: Handling[T],
    assetsHandling: AssetsHandling[F, T],
    invoker: Invoker[F, T],
    files: Files[F]
) extends Executor[F, Stateful.Interruption.type, Executor.Step.ModelResponse]:

  import Stateful.*

  override def execute(
      callDispatcher: ToolDispatcher[F],
      history: Memory[F, ?],
      query: String,
      assets: List[URI]
  ): F[Either[Interruption.type, Executor.Step.ModelResponse]] =
    for
      now      <- Clock[F].offsetDateTimeUtc
      queryStep = Executor.Step.UserQuery(query, now, assets)
      _        <- history.record(queryStep)
      result   <- resume(callDispatcher, history)
    yield result

  override def resume(callDispatcher: ToolDispatcher[F], history: Memory[F, ?]): F[Either[Interruption.type, Executor.Step.ModelResponse]] =
    for
      previousSteps     <- history.retrieve
      converted         <- previousSteps.traverse(messageFromStep)
      allSteps           = NEC.fromChain(converted).get // TODO: handle empty history in resume
      runResult         <- persistentLoop(callDispatcher, history).runEmpty(allSteps)
      (_, state, result) = runResult
      _                 <- result.traverse(history.record)
    yield result

  private def persistentLoop(
      callDispatcher: ToolDispatcher[F],
      history: Memory[F, ?]
  ): Ctx[Either[Interruption.type, Executor.Step.ModelResponse]] =
    Ctx.shouldInterrupt.ifM(Ctx.pure(Interruption.asLeft), eval(callDispatcher, history))

  private def eval(callDispatcher: ToolDispatcher[F], history: Memory[F, ?]): Ctx[Either[Interruption.type, Executor.Step.ModelResponse]] =
    for
      content   <- Ctx.allContent
      response  <- Ctx.liftF(invoker.generate(content))
      _         <- Ctx.tell(Chain.one(responseHandling.finishReason(response)))
      uris      <- Ctx.liftF(persistImages(response))
      calls     <- Ctx.liftF(responseHandling.getFunctionCalls(response).liftTo[F])
      _         <- Chain.fromSeq(calls).traverse(handleCall(callDispatcher, history))
      iteration <- Ctx.getIteration
      reply     <- if (calls.nonEmpty && iteration < config.functionCallLimit) then {
                     Ctx.increment >> persistentLoop(callDispatcher, history)
                   } else {
                     val text = responseHandling.getTextFromResponse(response)
                     for now <- Ctx.liftF(Clock[F].offsetDateTimeUtc)
                     yield Executor.Step.ModelResponse(text, now, uris).asRight
                   }
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

  private def handleCall(callDispatcher: ToolDispatcher[F], history: Memory[F, ?])(call: FunctionCall) =
    for
      now     <- Ctx.liftF(Clock[F].offsetDateTimeUtc)
      callStep = Executor.Step.Call(call, now)
      _       <- persist(history, callStep)
      result  <- Ctx.liftF(callDispatcher.dispatch(call))
      _       <- result match
                   case ToolDispatcher.Result.Response(result) =>
                     for
                       now <- Ctx.liftF(Clock[F].offsetDateTimeUtc)
                       _   <- persist(history, Executor.Step.Response(result = result.copy(functionCallId = call.callId), at = now))
                     yield ()
                   case ToolDispatcher.Result.HumanInTheLoop   => Ctx.interrupt
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

  private type Ctx[T] = RWST[F, NEC[Message], Chain[FinishReason], State, T]
  private object Ctx:
    export RWST.*
    def liftF[T](it: F[T]): Ctx[T]          = RWST.liftF(it)
    def add(e: Executor.Step): Ctx[Unit]    = modify(s => s.copy(steps = s.steps.append(e)))
    def increment: Ctx[Unit]                = modify(s => s.copy(iteration = s.iteration + 1))
    def getIteration: Ctx[Int]              = inspect(_.iteration)
    def getSteps: Ctx[Chain[Executor.Step]] = inspect(_.steps)
    def ask: Ctx[NEC[Message]]              = RWST.ask
    def allContent: Ctx[NEC[Message]]       =
      for
        initial     <- ask
        accumulated <- getSteps
        converted   <- liftF(accumulated.traverse(messageFromStep))
      yield initial.appendChain(converted)
    def interrupt: Ctx[Unit]                = modify(s => s.copy(shouldInterrupt = true))
    def shouldInterrupt: Ctx[Boolean]       = inspect(_.shouldInterrupt)
