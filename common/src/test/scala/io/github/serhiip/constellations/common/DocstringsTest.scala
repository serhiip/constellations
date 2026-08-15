package io.github.serhiip.constellations.common

import munit.FunSuite

class DocstringsTest extends FunSuite:
  test("sanitize should strip one-line Scaladoc delimiters") {
    assertEquals(Docstrings.sanitize("/** Adds two integers together */"), "Adds two integers together")
  }

  test("sanitize should strip leading stars from multiline Scaladoc") {
    val raw =
      """/**
        |   * Adds two integers
        |   * together
        |   */""".stripMargin
    assertEquals(Docstrings.sanitize(raw), "Adds two integers\ntogether")
  }

  test("sanitize should drop empty Option after stripping") {
    assertEquals(Docstrings.sanitize(Some("/**   */")), None)
    assertEquals(Docstrings.sanitize(Some("/** Adds two integers together */")), Some("Adds two integers together"))
  }
