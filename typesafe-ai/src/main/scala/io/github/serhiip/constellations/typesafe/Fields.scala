package io.github.serhiip.constellations.typesafe

import scala.NamedTuple.NamedTuple
import scala.annotation.implicitNotFound
import scala.compiletime.constValueTuple
import scala.compiletime.ops.int.{+, -}

import io.circe.syntax.*
import io.circe.{Encoder, Json}

type AllContainedIn[N <: Tuple, Allowed <: Tuple] <: Boolean = N match
  case EmptyTuple => true
  case h *: t     =>
    Tuple.Contains[Allowed, h] match
      case true  => AllContainedIn[t, Allowed]
      case false => false

type NoneContainedIn[N <: Tuple, Forbidden <: Tuple] <: Boolean = N match
  case EmptyTuple => true
  case h *: t     =>
    Tuple.Contains[Forbidden, h] match
      case true  => false
      case false => NoneContainedIn[t, Forbidden]

type AnswerMembers = ("selected", "choice", "confidence", "probability", "probabilities", "copy", "toString", "hashCode", "equals")

type Slots[N <: Int] <: Tuple = N match
  case 0 => EmptyTuple
  case _ => Any *: Slots[N - 1]

type IndicesFrom[V <: Tuple, I <: Int] <: Int = V match
  case EmptyTuple => Nothing
  case _ *: t     => I | IndicesFrom[t, I + 1]

type LevelsBelow[N <: Int] = IndicesFrom[Slots[N], 0]

type TypedAnswer[Q <: Question] <: Answer[?] = Q match
  case Question.Noul      => Answer.Noul
  case Question.Choice[n] => Answer.Choice[n]
  case Question.Score[l]  => Answer.Score[l]

type OptionFlags[N <: Tuple] = NamedTuple[N, Tuple.Map[N, [X <: Tuple.Union[N]] =>> Boolean]]

trait JsonFields[V <: Tuple]:
  def apply(values: V): List[Json]

object JsonFields:
  given JsonFields[EmptyTuple] = _ => Nil

  given [H: Encoder, T <: Tuple](using tail: JsonFields[T]): JsonFields[H *: T] = { case h *: rest => h.asJson :: tail(rest) }

@implicitNotFound("All named-tuple fields of SystemOneRequest must be Question instances, but ${V} is not")
trait QuestionFields[V <: Tuple]:
  def apply(values: V): List[Question]

object QuestionFields:
  given QuestionFields[EmptyTuple] = _ => Nil

  given [H <: Question, T <: Tuple](using tail: QuestionFields[T]): QuestionFields[H *: T] = { case h *: rest => h :: tail(rest) }

@implicitNotFound("choice options ${N} must be literal labels known at compile time")
trait ChoiceOptions[N <: Tuple]:
  def narrow(selected: String): Option[Tuple.Union[N]]

object ChoiceOptions:
  given ChoiceOptions[EmptyTuple] = _ => None

  given [H <: String: ValueOf, T <: Tuple](using tail: ChoiceOptions[T]): ChoiceOptions[H *: T] =
    selected => if selected == valueOf[H] then Some(valueOf[H]) else tail.narrow(selected)

trait LevelIndices[V <: Tuple, I <: Int]:
  def narrow(index: Int): Option[IndicesFrom[V, I]]

object LevelIndices:
  given [I <: Int]: LevelIndices[EmptyTuple, I] = _ => None

  given [H, T <: Tuple, I <: Int: ValueOf](using tail: LevelIndices[T, I + 1]): LevelIndices[H *: T, I] =
    index => Option.when(index == valueOf[I])(valueOf[I]).orElse(tail.narrow(index))

@implicitNotFound("score levels ${L} must be a literal level count known at compile time")
trait ScoreLevels[L <: Int]:
  def narrow(index: Int): Option[LevelsBelow[L]]

object ScoreLevels:
  given [L <: Int](using indices: LevelIndices[Slots[L], 0]): ScoreLevels[L] = indices.narrow(_)

object Fields:
  inline def names[N <: Tuple]: List[String] =
    constValueTuple[N].productIterator.map(_.toString).toList
