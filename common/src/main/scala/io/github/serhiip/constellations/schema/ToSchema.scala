package io.github.serhiip.constellations.schema

import java.time.OffsetDateTime

import scala.annotation.nowarn

import io.github.serhiip.constellations.common.Schema

trait ToSchema[T]:
  def schema: Schema

object ToSchema:
  def apply[T](using toSchema: ToSchema[T]): ToSchema[T] = toSchema

  def instance[T](s: Schema): ToSchema[T] = new { override val schema = s }

  given ToSchema[String]         = instance(Schema.string())
  given ToSchema[OffsetDateTime] = instance(Schema.string(format = Some("date-time")))

  given ToSchema[Int]  = instance(
    Schema.integer(format = Some("int32"), minimum = Some(Int.MinValue.toDouble), maximum = Some(Int.MaxValue.toDouble))
  )
  given ToSchema[Long] = instance(
    Schema.integer(format = Some("int64"), minimum = Some(Long.MinValue.toDouble), maximum = Some(Long.MaxValue.toDouble))
  )

  given ToSchema[Double]  = instance(Schema.number())
  given ToSchema[Float]   = instance(Schema.number())
  given ToSchema[Boolean] = instance(Schema.boolean())

  given optionSchema[A](using inner: ToSchema[A]): ToSchema[Option[A]] =
    instance(inner.schema.copy(nullable = Some(true)))

  given listSchema[A](using inner: ToSchema[A]): ToSchema[List[A]] =
    instance(Schema.array(items = inner.schema))

  given seqSchema[A](using inner: ToSchema[A]): ToSchema[Seq[A]] =
    instance(Schema.array(items = inner.schema))

  @nowarn // TODO: https://github.com/scala/scala3/issues/22951#issuecomment-2791671643
  inline def derived[T]: ToSchema[T] = instance(Schema.derived[T])

  inline given [T]: ToSchema[T] = derived[T]
