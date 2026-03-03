package io.github.serhiip.constellations.common

import scala.annotation.StaticAnnotation

final class llmHint(
    val format: Option[String] = None,
    val title: Option[String] = None,
    val description: Option[String] = None,
    val nullable: Option[Boolean] = None,
    val default: Option[String] = None,
    val minItems: Option[Long] = None,
    val maxItems: Option[Long] = None,
    val minProperties: Option[Long] = None,
    val maxProperties: Option[Long] = None,
    val minimum: Option[Double] = None,
    val maximum: Option[Double] = None,
    val minLength: Option[Long] = None,
    val maxLength: Option[Long] = None,
    val pattern: Option[String] = None,
    val example: Option[String] = None,
    val enm: Option[List[String]] = None
) extends StaticAnnotation
