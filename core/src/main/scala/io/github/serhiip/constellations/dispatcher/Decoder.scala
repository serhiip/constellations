package io.github.serhiip.constellations.dispatcher

import java.time.OffsetDateTime
import java.util.UUID

import scala.compiletime.{constValue, erasedValue, summonInline}
import scala.deriving.Mirror

import cats.Show
import cats.data.ValidatedNec
import cats.syntax.all.*

import io.github.serhiip.constellations.common.{Struct, Value}

trait Decoder[P, A]:
  def decode(proto: P, path: String = "root"): ValidatedNec[Decoder.Error, A]

object Decoder:
  def apply[P, A](using d: Decoder[P, A]): Decoder[P, A] = d

  enum Error(val path: String):
    case MissingField(override val path: String)                                                    extends Error(path)
    case WrongType(override val path: String, expected: String, actual: String)                     extends Error(path)
    case InvalidStringValue(override val path: String, value: String, targetType: String, cause: Option[Throwable] = None)
        extends Error(path)
    case MissingDiscriminator(override val path: String)                                            extends Error(path)
    case UnknownDiscriminator(override val path: String, typeName: String, knownTypes: Seq[String]) extends Error(path)

  given Show[Error] with
    def show(e: Error): String =
      val details = e match
        case Error.MissingField(_)                                 => "Field is missing."
        case Error.WrongType(_, expected, actual)                  => s"Expected type $expected, but got $actual."
        case Error.InvalidStringValue(_, value, targetType, cause) =>
          val causeMsg = cause.map(c => s" Cause: ${c.getMessage}").getOrElse("")
          s"Cannot parse '$value' into $targetType.$causeMsg"
        case Error.MissingDiscriminator(_)                         => "Sum type discriminator field '_type' is missing."
        case Error.UnknownDiscriminator(_, typeName, knownTypes)   =>
          s"Unknown type '$typeName'. Expected one of: ${knownTypes.mkString(", ")}."
      s"Error at path '${e.path}': $details"

  given Decoder[Value, String] with
    def decode(value: Value, path: String): ValidatedNec[Error, String] =
      value match
        case Value.StringValue(s) => s.validNec
        case _                    => Error.WrongType(path, "String", value.getClass.getSimpleName).invalidNec

  given Decoder[Value, Int] with
    def decode(value: Value, path: String): ValidatedNec[Error, Int] =
      value match
        case Value.NumberValue(n) =>
          if n.isWhole && n >= Int.MinValue && n <= Int.MaxValue then n.toInt.validNec
          else Error.InvalidStringValue(path, n.toString, "Int").invalidNec
        case _                    => Error.WrongType(path, "Number", value.getClass.getSimpleName).invalidNec

  given Decoder[Value, Long] with
    def decode(value: Value, path: String): ValidatedNec[Error, Long] =
      value match
        case Value.NumberValue(n) =>
          if n.isWhole && n >= Long.MinValue && n <= Long.MaxValue then n.toLong.validNec
          else Error.InvalidStringValue(path, n.toString, "Long").invalidNec
        case _                    => Error.WrongType(path, "Number", value.getClass.getSimpleName).invalidNec

  given Decoder[Value, Float] with
    def decode(value: Value, path: String): ValidatedNec[Error, Float] =
      value match
        case Value.NumberValue(n) =>
          if n >= Float.MinValue && n <= Float.MaxValue then n.toFloat.validNec
          else Error.InvalidStringValue(path, n.toString, "Float").invalidNec
        case _                    => Error.WrongType(path, "Number", value.getClass.getSimpleName).invalidNec

  given Decoder[Value, Double] with
    def decode(value: Value, path: String): ValidatedNec[Error, Double] =
      value match
        case Value.NumberValue(n) => n.validNec
        case _                    => Error.WrongType(path, "Number", value.getClass.getSimpleName).invalidNec

  given Decoder[Value, Boolean] with
    def decode(value: Value, path: String): ValidatedNec[Error, Boolean] =
      value match
        case Value.BoolValue(b) => b.validNec
        case _                  => Error.WrongType(path, "Boolean", value.getClass.getSimpleName).invalidNec

  given Decoder[Value, OffsetDateTime] with
    def decode(value: Value, path: String): ValidatedNec[Error, OffsetDateTime] =
      value match
        case Value.StringValue(s) =>
          Either
            .catchNonFatal(OffsetDateTime.parse(s))
            .leftMap(err => Error.InvalidStringValue(path, s, "OffsetDateTime", Some(err)))
            .toValidatedNec
        case _                    => Error.WrongType(path, "String", value.getClass.getSimpleName).invalidNec

  given Decoder[Value, UUID] with
    def decode(value: Value, path: String): ValidatedNec[Error, UUID] =
      value match
        case Value.StringValue(s) =>
          Either
            .catchNonFatal(UUID.fromString(s))
            .leftMap(err => Error.InvalidStringValue(path, s, "UUID", Some(err)))
            .toValidatedNec
        case _                    => Error.WrongType(path, "String", value.getClass.getSimpleName).invalidNec

  given Decoder[Value, Struct] with
    def decode(value: Value, path: String): ValidatedNec[Error, Struct] =
      value match
        case Value.StructValue(s) => s.validNec
        case _                    => Error.WrongType(path, "Struct", value.getClass.getSimpleName).invalidNec

  given Decoder[Value, List[Value]] with
    def decode(value: Value, path: String): ValidatedNec[Error, List[Value]] =
      value match
        case Value.ListValue(l) => l.validNec
        case _                  => Error.WrongType(path, "List", value.getClass.getSimpleName).invalidNec

  given [A](using d: Decoder[Value, A]): Decoder[Value, Option[A]] with
    def decode(value: Value, path: String): ValidatedNec[Error, Option[A]] =
      value match
        case Value.NullValue => None.validNec
        case _               => d.decode(value, path).map(Some(_))

  given [A](using d: Decoder[Value, A]): Decoder[Value, List[A]] with
    def decode(value: Value, path: String): ValidatedNec[Error, List[A]] =
      value match
        case Value.ListValue(values) =>
          values.zipWithIndex.traverse { case (v, i) =>
            d.decode(v, s"$path[$i]")
          }
        case _                       => Error.WrongType(path, "List", value.getClass.getSimpleName).invalidNec

  given [A](using d: Decoder[Value, A]): Decoder[Value, Map[String, A]] with
    def decode(value: Value, path: String): ValidatedNec[Error, Map[String, A]] =
      value match
        case Value.StructValue(Struct(fields)) =>
          fields.toList
            .traverse { case (k, v) =>
              d.decode(v, s"$path.$k").map(k -> _)
            }
            .map(_.toMap)
        case _                                 => Error.WrongType(path, "Struct", value.getClass.getSimpleName).invalidNec

  final class CaseClassDecoder[A](
      decoders: => List[Decoder[Value, ?]],
      labels: => List[String],
      mirror: Mirror.ProductOf[A]
  ) extends Decoder[Struct, A]:
    def decode(s: Struct, path: String): ValidatedNec[Error, A] =
      val fields          = s.fields
      val validatedFields = labels.zip(decoders).map { (label, decoder) =>
        val fieldPath = if path == "root" then label else s"$path.$label"
        fields.get(label) match
          case Some(value) => decoder.decode(value, fieldPath)
          case None        => Error.MissingField(fieldPath).invalidNec
      }
      validatedFields.sequence.map(decoded => mirror.fromProduct(Tuple.fromArray(decoded.toArray)))

  inline given derivedProduct[A](using m: Mirror.ProductOf[A]): Decoder[Struct, A] =
    lazy val decoders = summonAll[m.MirroredElemTypes]
    lazy val labels   = getLabels[m.MirroredElemLabels]
    new CaseClassDecoder[A](decoders, labels, m)

  final class SumTypeDecoder[A](
      decoders: => Map[String, Decoder[Struct, ? <: A]]
  ) extends Decoder[Struct, A]:
    def decode(s: Struct, path: String): ValidatedNec[Error, A] =
      val fields   = s.fields
      val typeName = fields.get(SumType.discriminatorField).flatMap {
        case Value.StringValue(s) => Some(s)
        case _                    => None
      }

      typeName match
        case None    =>
          Error.MissingDiscriminator(s"$path.${SumType.discriminatorField}").invalidNec
        case Some(t) =>
          decoders.get(t) match
            case None          =>
              Error.UnknownDiscriminator(s"$path.${SumType.discriminatorField}", t, decoders.keys.toSeq).invalidNec
            case Some(decoder) =>
              decoder.decode(s, path).asInstanceOf[ValidatedNec[Error, A]]

  inline given derivedSum[A](using m: Mirror.SumOf[A]): Decoder[Struct, A] =
    lazy val decoders = summonSumDecoders[m.MirroredElemTypes]
    lazy val labels   = getLabels[m.MirroredElemLabels]
    val map           = labels.zip(decoders).toMap.asInstanceOf[Map[String, Decoder[Struct, ? <: A]]]
    new SumTypeDecoder[A](map)

  given [A](using d: => Decoder[Struct, A]): Decoder[Value, A] with
    def decode(v: Value, path: String): ValidatedNec[Error, A] =
      v match
        case Value.StructValue(s) => d.decode(s, path)
        case _                    => Error.WrongType(path, "Struct", v.getClass.getSimpleName).invalidNec

  private inline def summonAll[T <: Tuple]: List[Decoder[Value, ?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => summonInline[Decoder[Value, h]] :: summonAll[t]

  private inline def summonSumDecoders[T <: Tuple]: List[Decoder[Struct, ?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => summonInline[Decoder[Struct, h]] :: summonSumDecoders[t]

  private inline def getLabels[T <: Tuple]: List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => constValue[h].asInstanceOf[String] :: getLabels[t]
