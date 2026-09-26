package io.github.serhiip.constellations.typesafe

import scala.Conversion
import scala.Conversion.into
import scala.NamedTuple.{AnyNamedTuple, NamedTuple}
import scala.compiletime.{constValue, error}

import cats.data.{NonEmptyList, NonEmptyMap}
import cats.syntax.all.*
import io.circe.derivation.{Configuration, ConfiguredEncoder}
import io.circe.syntax.*
import io.circe.{Encoder, Json}

given Configuration = Configuration.default.withSnakeCaseMemberNames
  .withDefaults
  .withDiscriminator("type")
  .withTransformConstructorNames(_.toLowerCase)

private given [V: Encoder]: Encoder[NonEmptyMap[String, V]] =
  Encoder.encodeMap[String, V].contramap(_.toSortedMap)

private given [A: Encoder]: Encoder[NonEmptyList[A]] =
  Encoder.encodeList[A].contramap(_.toList)

given Conversion[String, Json]          = Json.fromString(_)
given [A: Encoder]: Conversion[A, Json] = _.asJson

object Model:
  val jevLatest: String  = "jev-latest"
  val jevPreview: String = "jev-preview"

final case class ChatMessage(from: String, text: String) derives ConfiguredEncoder

enum State derives ConfiguredEncoder:
  case Text(value: String)
  case Structured(value: Json)
  case Chat(messages: NonEmptyList[ChatMessage])

object State:
  def structured[A: Encoder](value: A): State =
    Structured(value.asJson)

  def chat(messages: NonEmptyList[ChatMessage]): State =
    Chat(messages)

  def chat(first: ChatMessage, rest: ChatMessage*): State =
    chat(NonEmptyList(first, rest.toList))

  given Conversion[String, State]          = Text(_)
  given [A: Encoder]: Conversion[A, State] = structured(_)

enum Instructions derives ConfiguredEncoder:
  case Text(value: String)
  case Structured(value: Json)

object Instructions:
  def structured[A: Encoder](value: A): Instructions =
    Structured(value.asJson)

  given Conversion[String, Instructions]          = Text(_)
  given [A: Encoder]: Conversion[A, Instructions] = structured(_)

final case class NoulCriteria(`true`: Option[Json] = None, `false`: Option[Json] = None)

object NoulCriteria:
  def apply(yes: into[Json], no: into[Json]): NoulCriteria =
    new NoulCriteria(yes.some, no.some)

  given Encoder[NoulCriteria] =
    ConfiguredEncoder.derived[NoulCriteria].mapJson(_.dropNullValues)

enum Question:
  case Noul(instructions: Instructions, criteria: Option[NoulCriteria] = None)
  case Choice[N <: Tuple](instructions: Instructions, criteria: NonEmptyMap[String, Json])
  case Score[L <: Int](instructions: Instructions, criteria: NonEmptyList[Json])

object Question:
  def noul(instructions: into[Instructions], criteria: Option[NoulCriteria] = None): Question.Noul =
    Question.Noul(instructions, criteria)

  inline def noul[N <: Tuple, V <: Tuple](instructions: into[Instructions], criteria: NamedTuple[N, V])(using
      JsonFields[V]
  ): Question.Noul =
    inline if !constValue[AllContainedIn[N, ("yes", "no")]] then error("noul criteria labels must be yes and/or no")
    else
      val ns     = Fields.names[N]
      val vs     = summon[JsonFields[V]](criteria.toTuple)
      val mapped = ns
        .zip(vs)
        .map {
          case ("yes", v) => "true"  -> v
          case ("no", v)  => "false" -> v
          case (k, v)     => k       -> v
        }
        .toMap
      Question.Noul(
        instructions,
        NoulCriteria(mapped.get("true"), mapped.get("false")).some
      )

  inline def choice[N <: Tuple, V <: Tuple](instructions: into[Instructions], criteria: NamedTuple[N, V])(using
      JsonFields[V]
  ): Question.Choice[N] =
    inline if constValue[Tuple.Size[V]] == 0 then error("choice criteria must be non-empty")
    else inline if !constValue[NoneContainedIn[N, AnswerMembers]] then
      error("choice option labels are exposed as fields on the answer and must not shadow its members: selected, choice, confidence, probability, probabilities")
    else
      val ns      = Fields.names[N]
      val vs      = summon[JsonFields[V]](criteria.toTuple)
      val entries = NonEmptyList.fromListUnsafe(ns.zip(vs))
      Question.Choice(instructions, NonEmptyMap.of(entries.head, entries.tail*))

  inline def score[L <: Tuple](instructions: into[Instructions], levels: L)(using
      JsonFields[L]
  ): Question.Score[Tuple.Size[L]] =
    inline if constValue[Tuple.Size[L]] < 2 || constValue[Tuple.Size[L]] > 10 then error("score criteria must have between 2 and 10 levels")
    else
      val values = summon[JsonFields[L]](levels)
      Question.Score(instructions, NonEmptyList.fromListUnsafe(values))

  object Dynamic:
    def choice(
        instructions: into[Instructions],
        criteria: NonEmptyMap[String, Json]
    ): Question.Choice[Tuple] =
      Question.Choice(instructions, criteria)

    def choice(
        instructions: into[Instructions],
        first: (String, String),
        rest: (String, String)*
    ): Question.Choice[Tuple] =
      val entries = NonEmptyList(first, rest.toList).map { case (k, v) => k -> Json.fromString(v) }
      choice(instructions, NonEmptyMap.of(entries.head, entries.tail*))

    def score(
        instructions: into[Instructions],
        criteria: NonEmptyList[Json]
    ): Question.Score[Int] =
      Question.Score(instructions, criteria)

    def score(
        instructions: into[Instructions],
        lowest: into[Json],
        next: into[Json],
        rest: into[Json]*
    ): Question.Score[Int] =
      score(instructions, NonEmptyList(lowest, next :: rest.toList))

  given Encoder[Question] =
    ConfiguredEncoder.derived[Question].mapJsonObject(_.filter((_, value) => !value.isNull))

  private[typesafe] def typeName(value: Question | Answer[?]): String =
    value match
      case _: Noul | _: Answer.Noul           => "noul"
      case _: Choice[?] | _: Answer.Choice[?] => "choice"
      case _: Score[?] | _: Answer.Score[?]   => "score"

final case class SystemOneRequest[Q <: AnyNamedTuple] private (
    state: State,
    questions: NonEmptyMap[String, Question],
    model: String
)

object SystemOneRequest:
  inline def apply[N <: Tuple, V <: Tuple](
      state: into[State],
      questions: NamedTuple[N, V],
      model: String = Model.jevLatest
  )(using QuestionFields[V]): SystemOneRequest[NamedTuple[N, V]] =
    inline if constValue[Tuple.Size[V]] == 0 then error("questions must be non-empty")
    else
      val ns      = Fields.names[N]
      val vs      = summon[QuestionFields[V]](questions.toTuple)
      val entries = NonEmptyList.fromListUnsafe(ns.zip(vs))
      make(state, NonEmptyMap.of(entries.head, entries.tail*), model)

  def dynamic(
      state: into[State],
      questions: NonEmptyMap[String, Question],
      model: String = Model.jevLatest
  ): SystemOneRequest[AnyNamedTuple] =
    make(state, questions, model)

  def dynamic(
      state: into[State],
      first: (String, Question),
      rest: (String, Question)*
  ): SystemOneRequest[AnyNamedTuple] =
    dynamic(state, NonEmptyMap.of(first, rest*))

  private def make[Q <: AnyNamedTuple](
      state: State,
      questions: NonEmptyMap[String, Question],
      model: String
  ): SystemOneRequest[Q] =
    new SystemOneRequest(state, questions, model)

  given Encoder[SystemOneRequest[?]] =
    ConfiguredEncoder.derived[SystemOneRequest[AnyNamedTuple]].contramap { request =>
      make[AnyNamedTuple](request.state, request.questions, request.model)
    }
