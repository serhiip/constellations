package io.github.serhiip.constellations.schema

import io.github.serhiip.constellations.common.Schema
import scala.annotation.nowarn

trait ToSchema[T]:
  def schema: Schema

object ToSchema:
  def apply[T](using toSchema: ToSchema[T]): ToSchema[T] = toSchema

  @nowarn // TODO: https://github.com/scala/scala3/issues/22951#issuecomment-2791671643
  inline def derived[T]: ToSchema[T] = new { override val schema = Schema.derived[T] }

  inline given [T]: ToSchema[T] = derived[T]
