package io.github.serhiip.constellations.dispatcher

import java.time.OffsetDateTime
import java.util.UUID

import scala.compiletime.{constValue, erasedValue, summonInline}
import scala.deriving.Mirror

import io.github.serhiip.constellations.Dispatcher
import io.github.serhiip.constellations.common.{FunctionResponse, Struct, Value}

object SumType:
  private[dispatcher] val discriminatorField = "_type"

trait ValueEncoder[A]:
  def encode(value: A): Value

object ValueEncoder:
  def apply[A](using encoder: ValueEncoder[A]): ValueEncoder[A] = encoder

  inline def derived[A](using m: Mirror.Of[A]): ValueEncoder[A] =
    inline m match
      case product: Mirror.ProductOf[A] => derivedProduct(using product)
      case sum: Mirror.SumOf[A]         => derivedSum(using sum)

  given ValueEncoder[Value] with
    def encode(value: Value): Value = value

  given ValueEncoder[Struct] with
    def encode(value: Struct): Value = Value.struct(value)

  given ValueEncoder[String] with
    def encode(value: String): Value = Value.string(value)

  given ValueEncoder[Int] with
    def encode(value: Int): Value = Value.number(value)

  given ValueEncoder[Long] with
    def encode(value: Long): Value = Value.number(value)

  given ValueEncoder[Double] with
    def encode(value: Double): Value = Value.number(value)

  given ValueEncoder[Float] with
    def encode(value: Float): Value = Value.number(value)

  given ValueEncoder[Boolean] with
    def encode(value: Boolean): Value = Value.bool(value)

  given ValueEncoder[OffsetDateTime] with
    def encode(value: OffsetDateTime): Value = Value.string(value.toString)

  given ValueEncoder[UUID] with
    def encode(value: UUID): Value = Value.string(value.toString)

  given ValueEncoder[Unit] with
    def encode(value: Unit): Value = Value.NullValue

  given [A](using encoder: ValueEncoder[A]): ValueEncoder[Option[A]] with
    def encode(value: Option[A]): Value =
      value match
        case Some(inner) => encoder.encode(inner)
        case None        => Value.NullValue

  given [A](using encoder: ValueEncoder[A]): ValueEncoder[List[A]] with
    def encode(value: List[A]): Value = Value.list(value.map(encoder.encode))

  given [A](using encoder: ValueEncoder[A]): ValueEncoder[Seq[A]] with
    def encode(value: Seq[A]): Value = Value.list(value.toList.map(encoder.encode))

  given [A](using encoder: ValueEncoder[A]): ValueEncoder[Map[String, A]] with
    def encode(value: Map[String, A]): Value =
      Value.struct(value.map { case (key, value) => key -> encoder.encode(value) })

  inline given derivedProduct[A](using m: Mirror.ProductOf[A]): ValueEncoder[A] =
    val labels   = getLabels[m.MirroredElemLabels]
    val encoders = summonAll[m.MirroredElemTypes]
    new ProductEncoder[A](labels, encoders, m)

  inline given derivedSum[A](using m: Mirror.SumOf[A]): ValueEncoder[A] =
    val labels   = getLabels[m.MirroredElemLabels]
    val encoders = summonAll[m.MirroredElemTypes]
    new SumEncoder[A](labels, encoders, m)

  final class ProductEncoder[A](
      labels: List[String],
      encoders: List[ValueEncoder[?]],
      mirror: Mirror.ProductOf[A]
  ) extends ValueEncoder[A]:
    def encode(value: A): Value =
      val product = mirror.fromProduct(value.asInstanceOf[Product]).asInstanceOf[Product]
      val values  = product.productIterator.toList
      val fields  =
        labels
          .zip(values)
          .zip(encoders)
          .map { case ((label, fieldValue), encoder) =>
            label -> encoder.asInstanceOf[ValueEncoder[Any]].encode(fieldValue)
          }
      Value.struct(fields.toMap)

  final class SumEncoder[A](
      labels: List[String],
      encoders: List[ValueEncoder[?]],
      mirror: Mirror.SumOf[A]
  ) extends ValueEncoder[A]:
    def encode(value: A): Value =
      val ordinal = mirror.ordinal(value)
      val label   = labels(ordinal)
      val encoder = encoders(ordinal).asInstanceOf[ValueEncoder[Any]]
      val encoded = encoder.encode(value)
      val struct  = encoded match
        case Value.StructValue(valueStruct) => valueStruct
        case other                          => Struct("value" -> other)
      Value.struct(struct.fields.updated(SumType.discriminatorField, Value.string(label)))

  private inline def summonAll[T <: Tuple]: List[ValueEncoder[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => summonInline[ValueEncoder[h]] :: summonAll[t]

  private inline def getLabels[T <: Tuple]: List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => constValue[h].asInstanceOf[String] :: getLabels[t]

trait StructEncoder[A]:
  def encode(value: A): Struct

trait LowPriorityStructEncoder:
  given [A](using encoder: ValueEncoder[A]): StructEncoder[A] with
    def encode(value: A): Struct =
      encoder.encode(value) match
        case Value.StructValue(struct) => struct
        case other                     => Struct("value" -> other)

object StructEncoder extends LowPriorityStructEncoder:
  def apply[A](using encoder: StructEncoder[A]): StructEncoder[A] = encoder

trait ResultEncoder[A]:
  def encode(name: String, value: A): Dispatcher.Result

trait LowPriorityResultEncoder:
  given [A](using encoder: StructEncoder[A]): ResultEncoder[A] with
    def encode(name: String, value: A): Dispatcher.Result =
      Dispatcher.Result.Response(FunctionResponse(name, encoder.encode(value)))

object ResultEncoder extends LowPriorityResultEncoder:
  def apply[A](using encoder: ResultEncoder[A]): ResultEncoder[A] = encoder

  given ResultEncoder[Dispatcher.Result] with
    def encode(name: String, value: Dispatcher.Result): Dispatcher.Result = value

  given ResultEncoder[FunctionResponse] with
    def encode(name: String, value: FunctionResponse): Dispatcher.Result =
      Dispatcher.Result.Response(value)
