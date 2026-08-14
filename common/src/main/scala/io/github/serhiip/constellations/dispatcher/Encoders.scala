package io.github.serhiip.constellations.dispatcher

import java.time.OffsetDateTime
import java.util.UUID

import scala.compiletime.{constValue, erasedValue, summonInline}
import scala.deriving.Mirror

import io.github.serhiip.constellations.ToolDispatcher
import io.github.serhiip.constellations.common.{FunctionCall, FunctionResponse, Struct, Value}
import io.github.serhiip.constellations.naming.Configuration

trait ValueEncoder[A]:
  def encode(value: A, config: Configuration = Configuration.default): Value

object ValueEncoder:
  def apply[A](using encoder: ValueEncoder[A]): ValueEncoder[A] = encoder

  inline def derived[A](using m: Mirror.Of[A]): ValueEncoder[A] =
    inline m match
      case product: Mirror.ProductOf[A] => derivedProduct(using product)
      case sum: Mirror.SumOf[A]         => derivedSum(using sum)

  given ValueEncoder[Value] with
    def encode(value: Value, config: Configuration = Configuration.default): Value = value

  given ValueEncoder[Struct] with
    def encode(value: Struct, config: Configuration = Configuration.default): Value = Value.struct(value)

  given ValueEncoder[String] with
    def encode(value: String, config: Configuration = Configuration.default): Value = Value.string(value)

  given ValueEncoder[Int] with
    def encode(value: Int, config: Configuration = Configuration.default): Value = Value.number(value)

  given ValueEncoder[Long] with
    def encode(value: Long, config: Configuration = Configuration.default): Value = Value.number(value)

  given ValueEncoder[Double] with
    def encode(value: Double, config: Configuration = Configuration.default): Value = Value.number(value)

  given ValueEncoder[Float] with
    def encode(value: Float, config: Configuration = Configuration.default): Value = Value.number(value)

  given ValueEncoder[Boolean] with
    def encode(value: Boolean, config: Configuration = Configuration.default): Value = Value.bool(value)

  given ValueEncoder[OffsetDateTime] with
    def encode(value: OffsetDateTime, config: Configuration = Configuration.default): Value = Value.string(value.toString)

  given ValueEncoder[UUID] with
    def encode(value: UUID, config: Configuration = Configuration.default): Value = Value.string(value.toString)

  given ValueEncoder[Unit] with
    def encode(value: Unit, config: Configuration = Configuration.default): Value = Value.NullValue

  given [A](using encoder: ValueEncoder[A]): ValueEncoder[Option[A]] with
    def encode(value: Option[A], config: Configuration = Configuration.default): Value =
      value match
        case Some(inner) => encoder.encode(inner, config)
        case None        => Value.NullValue

  given [A](using encoder: ValueEncoder[A]): ValueEncoder[List[A]] with
    def encode(value: List[A], config: Configuration = Configuration.default): Value =
      Value.list(value.map(encoder.encode(_, config)))

  given [A](using encoder: ValueEncoder[A]): ValueEncoder[Seq[A]] with
    def encode(value: Seq[A], config: Configuration = Configuration.default): Value =
      Value.list(value.toList.map(encoder.encode(_, config)))

  given [A](using encoder: ValueEncoder[A]): ValueEncoder[Map[String, A]] with
    def encode(value: Map[String, A], config: Configuration = Configuration.default): Value =
      Value.struct(value.map { case (key, value) => key -> encoder.encode(value, config) })

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
    def encode(value: A, config: Configuration = Configuration.default): Value =
      val product = mirror.fromProduct(value.asInstanceOf[Product]).asInstanceOf[Product]
      val values  = product.productIterator.toList
      val fields  =
        labels
          .zip(values)
          .zip(encoders)
          .map { case ((label, fieldValue), encoder) =>
            config.transformMemberNames(label) -> encoder.asInstanceOf[ValueEncoder[Any]].encode(fieldValue, config)
          }
      Value.struct(fields.toMap)

  final class SumEncoder[A](
      labels: List[String],
      encoders: List[ValueEncoder[?]],
      mirror: Mirror.SumOf[A]
  ) extends ValueEncoder[A]:
    def encode(value: A, config: Configuration = Configuration.default): Value =
      val ordinal = mirror.ordinal(value)
      val label   = config.transformConstructorNames(labels(ordinal))
      val encoder = encoders(ordinal).asInstanceOf[ValueEncoder[Any]]
      val encoded = encoder.encode(value, config)
      val struct  = encoded match
        case Value.StructValue(valueStruct) => valueStruct
        case other                          => Struct("value" -> other)
      Value.struct(struct.fields.updated(config.discriminator, Value.string(label)))

  private inline def summonAll[T <: Tuple]: List[ValueEncoder[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => summonInline[ValueEncoder[h]] :: summonAll[t]

  private inline def getLabels[T <: Tuple]: List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => constValue[h].asInstanceOf[String] :: getLabels[t]

trait StructEncoder[A]:
  def encode(value: A, config: Configuration = Configuration.default): Struct

trait LowPriorityStructEncoder:
  given [A](using encoder: ValueEncoder[A]): StructEncoder[A] with
    def encode(value: A, config: Configuration = Configuration.default): Struct =
      encoder.encode(value, config) match
        case Value.StructValue(struct) => struct
        case other                     => Struct("value" -> other)

object StructEncoder extends LowPriorityStructEncoder:
  def apply[A](using encoder: StructEncoder[A]): StructEncoder[A] = encoder

trait ResultEncoder[A]:
  def encode(call: FunctionCall, value: A, config: Configuration = Configuration.default): ToolDispatcher.Result

trait LowPriorityResultEncoder:
  given [A](using encoder: StructEncoder[A]): ResultEncoder[A] with
    def encode(call: FunctionCall, value: A, config: Configuration = Configuration.default): ToolDispatcher.Result =
      ToolDispatcher.Result.Response(FunctionResponse(call, encoder.encode(value, config)))

object ResultEncoder extends LowPriorityResultEncoder:
  def apply[A](using encoder: ResultEncoder[A]): ResultEncoder[A] = encoder

  given ResultEncoder[ToolDispatcher.Result] with
    def encode(call: FunctionCall, value: ToolDispatcher.Result, config: Configuration = Configuration.default): ToolDispatcher.Result =
      value match
        case ToolDispatcher.Result.Response(fr)   => ToolDispatcher.Result.Response(fr.copy(call = call))
        case ToolDispatcher.Result.HumanInTheLoop => ToolDispatcher.Result.HumanInTheLoop

  given ResultEncoder[FunctionResponse] with
    def encode(call: FunctionCall, value: FunctionResponse, config: Configuration = Configuration.default): ToolDispatcher.Result =
      ToolDispatcher.Result.Response(FunctionResponse(call, value.response))
