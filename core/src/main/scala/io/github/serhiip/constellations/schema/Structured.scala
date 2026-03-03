package io.github.serhiip.constellations.schema

import io.github.serhiip.constellations.common.Schema

trait Structured[T]:
  def schema: Schema

object Structured:
  def apply[T](using structured: Structured[T]): Structured[T] = structured

  final class Derived[T](val schema: Schema) extends Structured[T]

  inline def derived[T]: Structured[T] = Derived[T](Schema.derived[T])

  inline given [T]: Structured[T] = derived[T]
