package io.github.serhiip.constellations.schema

import scala.quoted.*

object DefaultValues:
  def defaultMethod(using Quotes)(tpe: quotes.reflect.TypeRepr, paramIndex: Int): Option[quotes.reflect.Symbol] =
    import quotes.reflect.*
    val companionClass = tpe.typeSymbol.companionClass
    if companionClass == Symbol.noSymbol then None
    else
      val n          = paramIndex + 1
      val candidates = List(s"$$lessinit$$greater$$default$$$n", s"apply$$default$$$n")
      candidates.flatMap(name => companionClass.declaredMethod(name)).find(_.paramSymss.forall(_.isEmpty))

  def hasUsableDefault(using Quotes)(tpe: quotes.reflect.TypeRepr, paramIndex: Int): Boolean =
    defaultMethod(tpe, paramIndex).isDefined

  def usableDefaultTerm(using Quotes)(tpe: quotes.reflect.TypeRepr, paramIndex: Int): Option[quotes.reflect.Term] =
    import quotes.reflect.*
    defaultMethod(tpe, paramIndex).map { method =>
      val selected = Select(Ref(tpe.typeSymbol.companionModule), method)
      val typeArgs = tpe.typeArgs
      if typeArgs.isEmpty then selected else selected.appliedToTypes(typeArgs)
    }
