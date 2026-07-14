package io.github.serhiip.constellations.dispatcher.naming

trait NamingStrategy:
  def componentName(name: String): String
  def methodName(name: String): String
  def parameterName(name: String): String
