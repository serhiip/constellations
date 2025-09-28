package io.github.serhiip.constellations.executor

import java.time.OffsetDateTime

import cats.Monad
import cats.data.{Chain, NonEmptyChain as NEC, RWST}
import cats.effect.Clock
import cats.kernel.Monoid
import cats.syntax.apply.*
import cats.syntax.either.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*

import io.github.serhiip.constellations.*
import io.github.serhiip.constellations.common.*
//import io.github.serhiip.constellations.dispatcher.*

object Stateful:

  case class Config(functionCallLimit: Int)

  case object Interruption

  def apply[F[_]: Clock: Monad, T](
      config: Config,
      responseHandling: Handling[F, T],
      invoker: Invoker[F, T]
  ): Stateful[F, T] = new Stateful[F, T](config, responseHandling, invoker)

  private case class State(iteration: Int, steps: Chain[Executor.Step], shouldInterrupt: Boolean)
  private object State:
    given Monoid[State] with
      override def combine(x: State, y: State): State =
        State(
          iteration = x.iteration + y.iteration,
          steps = Chain.concat(x.steps, y.steps),
          shouldInterrupt = x.shouldInterrupt || y.shouldInterrupt
        )
      override def empty: State                       = State(0, Chain.empty, false)

final class Stateful[F[_]: Clock: Monad, T](
    config: Stateful.Config,
    responseHandling: Handling[F, T],
    invoker: Invoker[F, T]
) extends Executor[F, Stateful.Interruption.type, String]:

  import Stateful.*

  override def execute(
      callDispatcher: Dispatcher[F],
      history: Memory[F, ?],
      query: String
  ): F[Either[Interruption.type, String]] =
    for
      now      <- Clock[F].offsetDateTimeUtc
      queryStep = Executor.Step.UserQuery(query, now)
      _        <- history.record(queryStep)
      result   <- resume(callDispatcher, history)
    yield result

  override def resume(callDispatcher: Dispatcher[F], history: Memory[F, ?]): F[Either[Interruption.type, String]] =
    for
      previousSteps <- history.retrieve
      converted      = previousSteps.map(messageFromStep)
      allSteps       = NEC.fromChain(converted).get // TODO: handle empty history in resume
      result        <- persistentLoop(callDispatcher, history).runEmptyA(allSteps)
      finishedAt    <- Clock[F].offsetDateTimeUtc
      _             <- result.traverse(response => history.record(Executor.Step.ModelResponse(response, finishedAt)))
    yield result

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
      (ask, getSteps).mapN((initial, accumulated) => initial.appendChain(accumulated.map(messageFromStep)))
    def interrupt: Ctx[Unit]                = modify(s => s.copy(shouldInterrupt = true))
    def shouldInterrupt: Ctx[Boolean]       = inspect(_.shouldInterrupt)

  private def persistentLoop(
      callDispatcher: Dispatcher[F],
      history: Memory[F, ?]
  ): Ctx[Either[Interruption.type, String]] =
    Ctx.shouldInterrupt.ifM(Ctx.pure(Interruption.asLeft), eval(callDispatcher, history))

  private def eval(callDispatcher: Dispatcher[F], history: Memory[F, ?]) =
    for
      content   <- Ctx.allContent
      response  <- Ctx.liftF(invoker.generate(content))
      _         <- Ctx.tellF(responseHandling.finishReason(response).map(Chain.one))
      calls     <- Ctx.liftF(responseHandling.getFunctinoCalls(response))
      _         <- Chain.fromSeq(calls).traverse(handleCall(callDispatcher, history))
      iteration <- Ctx.getIteration
      reply     <- if (calls.nonEmpty && iteration < config.functionCallLimit) then {
                     Ctx.increment >> persistentLoop(callDispatcher, history)
                   } else Ctx.liftF(responseHandling.getTextFromResponse(response).map(_.asRight))
    yield reply

  private def handleCall(callDispatcher: Dispatcher[F], history: Memory[F, ?])(call: FunctionCall) =
    for
      callStep <- Ctx.liftF(Clock[F].offsetDateTimeUtc.map(Executor.Step.Call.apply.curried(call)))
      _        <- persist(history, callStep)
      result   <- Ctx.liftF(callDispatcher.dispatch(call))
      _        <- result match
                    case Dispatcher.Result.Response(result) =>
                      val withCallId = result.copy(functionCallId = call.callId)
                      Ctx.liftF(Clock[F].offsetDateTimeUtc.map(Executor.Step.Response.apply.curried(withCallId))) >>= persist
                        .curried(history)
                    case Dispatcher.Result.HumanInTheLoop   => Ctx.interrupt
    yield ()

  private def persist(history: Memory[F, ?], step: Executor.Step) = Ctx.add(step) >> Ctx.liftF(history.record(step))

  private def messageFromStep(in: Executor.Step): Message = in match
    case Executor.Step.UserQuery(text, at)     => Message.User(ContentPart.Text(text) :: Nil)
    case Executor.Step.ModelResponse(text, at) => Message.Assistant(text)
    case Executor.Step.Call(call, at)          => Message.Tool(call)
    case Executor.Step.Response(result, at)    => Message.ToolResult(result)
    case Executor.Step.UserReply(text, at, _)  => Message.System(text)
