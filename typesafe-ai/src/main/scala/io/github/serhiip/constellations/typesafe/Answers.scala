package io.github.serhiip.constellations.typesafe

import scala.NamedTuple
import scala.NamedTuple.AnyNamedTuple
import scala.compiletime.summonInline
import scala.reflect.ClassTag

import cats.syntax.either.*
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.{Decoder, Json}

sealed trait Answer[+Q <: Question]

object Answer:
  final case class Noul(noul: Double) extends Answer[Question.Noul]

  final case class Choice[N <: Tuple](selected: String, probabilities: Map[String, Double], confidence: Double)
      extends Answer[Question.Choice[N]],
        Selectable:

    type Fields = OptionFlags[N]

    def selectDynamic(option: String): Boolean              = option == selected
    def probability(option: Tuple.Union[N]): Option[Double] = probabilities.get(option.toString)
    inline def choice: Option[Tuple.Union[N]]               = summonInline[ChoiceOptions[N]].narrow(selected)

  final case class Score[L <: Int](score: Double, legend: Map[Int, Json], probabilities: Map[Int, Double], confidence: Double)
      extends Answer[Question.Score[L]]:

    def probability(level: LevelsBelow[L]): Option[Double] = probabilities.get(level)
    inline def level: Option[LevelsBelow[L]]               =
      probabilities.toList
        .sortBy((index, _) => index)
        .maxByOption((_, likelihood) => likelihood)
        .flatMap((index, _) => summonInline[ScoreLevels[L]].narrow(index))

  private given Configuration =
    summon[Configuration].withTransformMemberNames:
      case "selected" => "choice"
      case other      => other

  given ConfiguredCodec[Answer[?]] = ConfiguredCodec.derived

final class Answers[Q <: AnyNamedTuple] private (
    private val answers: Map[String, Answer[?]],
    private val questions: Map[String, Question]
) extends Selectable:
  type Fields = NamedTuple.Map[Q, [X <: Tuple.Union[NamedTuple.DropNames[Q]]] =>> Either[Client.Error, TypedAnswer[X & Question]]]

  def selectDynamic(name: String): Any =
    (answers.get(name), questions.get(name)) match
      case (None, _) | (Some(_), None)    => Left(Client.Error.AnswerMissing(name))
      case (Some(answer), Some(question)) => validate(name, question, answer)

  private def validate(name: String, question: Question, answer: Answer[?]): Either[Client.Error, Answer[?]] =
    (question, answer) match
      case (_: Question.Noul, a: Answer.Noul)           =>
        Right(a)
      case (q: Question.Choice[?], a: Answer.Choice[?]) =>
        val allowed = q.criteria.toSortedMap.keys.toList
        Either.cond(allowed.contains(a.selected), a, Client.Error.AnswerOptionUnknown(name, a.selected, allowed))
      case (q: Question.Score[?], a: Answer.Score[?])   =>
        val maxLevel = q.criteria.size - 1
        val invalid  = (a.probabilities.keys ++ a.legend.keys).filter(i => i < 0 || i > maxLevel).toList
        Either
          .fromOption(invalid.headOption, ifNone = a)
          .map(bad => Client.Error.AnswerOptionUnknown(name, bad.toString, (0 to maxLevel).map(_.toString).toList))
          .swap
      case (_, other)                                   =>
        Left(Client.Error.AnswerTypeMismatch(name, Question.typeName(question), Question.typeName(other)))

object Answers:
  def apply[Q <: AnyNamedTuple](answers: Map[String, Answer[?]], questions: Map[String, Question]): Answers[Q] =
    new Answers(answers, questions)

final case class Usage(inputTokens: Option[Int] = None, outputTokens: Option[Int] = None) derives ConfiguredCodec

final class SystemOneResponse[Q <: AnyNamedTuple] private (
    val model: String,
    val answers: Answers[Q],
    val rawAnswers: Map[String, Answer[?]],
    val usage: Usage
):
  def noul(id: String): Either[Client.Error, Answer.Noul]        = typed[Answer.Noul](id, "noul")
  def choice(id: String): Either[Client.Error, Answer.Choice[?]] = typed[Answer.Choice[?]](id, "choice")
  def score(id: String): Either[Client.Error, Answer.Score[?]]   = typed[Answer.Score[?]](id, "score")

  private def typed[A <: Answer[?]](id: String, expected: String)(using ClassTag[A]): Either[Client.Error, A] =
    rawAnswers.get(id) match
      case Some(a: A)  => Right(a)
      case Some(other) => Left(Client.Error.AnswerTypeMismatch(id, expected, Question.typeName(other)))
      case None        => Left(Client.Error.AnswerMissing(id))

object SystemOneResponse:
  def apply[Q <: AnyNamedTuple](
      model: String,
      answers: Map[String, Answer[?]],
      usage: Usage,
      questions: Map[String, Question]
  ): SystemOneResponse[Q] =
    new SystemOneResponse(model, Answers(answers, questions), answers, usage)

  def apply[Q <: AnyNamedTuple](
      model: String,
      answers: Map[String, Answer[?]],
      usage: Usage,
      request: SystemOneRequest[Q]
  ): SystemOneResponse[Q] =
    apply(model, answers, usage, request.questions.toSortedMap)

  private[typesafe] final case class Raw(model: String, answers: Map[String, Answer[?]], usage: Usage = Usage())
      derives ConfiguredCodec

  private[typesafe] def decodeRaw: Decoder[(String, Map[String, Answer[?]], Usage)] =
    Decoder[Raw].map(Tuple.fromProductTyped)

final case class ModelMetadata(name: String, description: String, releaseDate: String) derives ConfiguredCodec

final case class ListModelsResponse(models: List[ModelMetadata]) derives ConfiguredCodec
