package io.github.serhiip.constellations.schema

import io.github.serhiip.constellations.common.Schema

trait ToSchema[T]:
  def schema: Schema

object ToSchema:
  def apply[T](using toSchema: ToSchema[T]): ToSchema[T] = toSchema

  final class Derived[T](val schema: Schema) extends ToSchema[T]

  inline def derived[T]: ToSchema[T] = Derived[T](Schema.derived[T])

  inline given [T]: ToSchema[T] = derived[T]
