package io.github.serhiip.constellations.schema

import java.time.OffsetDateTime

import scala.annotation.nowarn
import scala.quoted.*

import io.github.serhiip.constellations.common.Schema
import io.github.serhiip.constellations.naming.Configuration

trait ToSchema[T]:
  def schemaWith(config: Configuration): Schema
  def schema: Schema = schemaWith(Configuration.default)

object ToSchema:
  def apply[T](using toSchema: ToSchema[T]): ToSchema[T] = toSchema

  def instance[T](s: Schema): ToSchema[T] = new:
    def schemaWith(config: Configuration): Schema = s

  def instance[T](build: Configuration => Schema): ToSchema[T] = new:
    def schemaWith(config: Configuration): Schema = build(config)

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

  given optionSchema[A](using inner: ToSchema[A]): ToSchema[Option[A]] = new:
    def schemaWith(config: Configuration): Schema = inner.schemaWith(config).copy(nullable = Some(true))

  given listSchema[A](using inner: ToSchema[A]): ToSchema[List[A]] = new:
    def schemaWith(config: Configuration): Schema = Schema.array(items = inner.schemaWith(config))

  given seqSchema[A](using inner: ToSchema[A]): ToSchema[Seq[A]] = new:
    def schemaWith(config: Configuration): Schema = Schema.array(items = inner.schemaWith(config))

  @nowarn // TODO: https://github.com/scala/scala3/issues/22951#issuecomment-2791671643
  inline def derived[T]: ToSchema[T] = ${ derivedImpl[T] }

  private def derivedImpl[T: Type](using Quotes): Expr[ToSchema[T]] =
    '{
      new ToSchema[T]:
        def schemaWith(config: Configuration): Schema = ${ SchemaMacros.deriveWithImpl[T]('config) }
        override lazy val schema: Schema              = schemaWith(Configuration.default)
    }

  inline given [T]: ToSchema[T] = derived[T]
