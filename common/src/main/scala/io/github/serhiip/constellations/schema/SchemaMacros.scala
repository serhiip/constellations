package io.github.serhiip.constellations.schema

import scala.quoted.*

import io.github.serhiip.constellations.common.{Schema, llmHint}

object SchemaMacros:
  final case class Hint(
      format: Option[String] = None,
      title: Option[String] = None,
      description: Option[String] = None,
      nullable: Option[Boolean] = None,
      default: Option[String] = None,
      minItems: Option[Long] = None,
      maxItems: Option[Long] = None,
      minProperties: Option[Long] = None,
      maxProperties: Option[Long] = None,
      minimum: Option[Double] = None,
      maximum: Option[Double] = None,
      minLength: Option[Long] = None,
      maxLength: Option[Long] = None,
      pattern: Option[String] = None,
      example: Option[String] = None,
      enm: Option[List[String]] = None
  )

  def deriveImpl[A: Type](using Quotes): Expr[Schema] =
    import quotes.reflect.*

    def readHint(sym: Symbol): Option[Expr[Hint]] =
      sym.annotations.collectFirst {
        case ann if ann.tpe <:< TypeRepr.of[llmHint] =>
          val hintExpr = ann.asExprOf[llmHint]
          '{
            val hint = $hintExpr
            Hint(
              format = hint.format,
              title = hint.title,
              description = hint.description,
              nullable = hint.nullable,
              default = hint.default,
              minItems = hint.minItems,
              maxItems = hint.maxItems,
              minProperties = hint.minProperties,
              maxProperties = hint.maxProperties,
              minimum = hint.minimum,
              maximum = hint.maximum,
              minLength = hint.minLength,
              maxLength = hint.maxLength,
              pattern = hint.pattern,
              example = hint.example,
              enm = hint.enm
            )
          }
      }

    def applyHint(schemaExpr: Expr[Schema], hintOpt: Option[Expr[Hint]]): Expr[Schema] =
      hintOpt match
        case None           => schemaExpr
        case Some(hintExpr) =>
          '{
            val schema = $schemaExpr
            val hint   = $hintExpr
            schema.copy(
              format = hint.format.orElse(schema.format),
              title = hint.title.orElse(schema.title),
              description = hint.description.orElse(schema.description),
              nullable = hint.nullable.orElse(schema.nullable),
              default = hint.default.orElse(schema.default),
              minItems = hint.minItems.orElse(schema.minItems),
              maxItems = hint.maxItems.orElse(schema.maxItems),
              minProperties = hint.minProperties.orElse(schema.minProperties),
              maxProperties = hint.maxProperties.orElse(schema.maxProperties),
              minimum = hint.minimum.orElse(schema.minimum),
              maximum = hint.maximum.orElse(schema.maximum),
              minLength = hint.minLength.orElse(schema.minLength),
              maxLength = hint.maxLength.orElse(schema.maxLength),
              pattern = hint.pattern.orElse(schema.pattern),
              example = hint.example.orElse(schema.example),
              enm = hint.enm.getOrElse(schema.enm)
            )
          }

    def normalizeDocstring(raw: Option[String]): Option[String] =
      raw.map(_.stripPrefix("/**").stripSuffix("*/").trim).filter(_.nonEmpty)

    def withDocstring(schemaExpr: Expr[Schema], docstring: Option[String]): Expr[Schema] =
      normalizeDocstring(docstring).fold(schemaExpr)(doc => '{ $schemaExpr.copy(description = Some(${ Expr(doc) })) })

    def getCaseClassFields(tpe: TypeRepr): List[(String, TypeRepr, Boolean, Option[Expr[Hint]], Option[String])] =
      val paramsByName   =
        tpe.typeSymbol.primaryConstructor.paramSymss.flatten
          .filterNot(_.isTypeParam)
          .map(param => param.name -> param)
          .toMap
      val defaultsByName = paramsByName.view.mapValues(_.flags.is(Flags.HasDefault)).toMap
      tpe.typeSymbol.caseFields.map { field =>
        val fieldName  = field.name
        val fieldTpe   = tpe.memberType(field)
        val isOptional = fieldTpe <:< TypeRepr.of[Option[Any]]
        val hasDefault = defaultsByName.getOrElse(fieldName, false)
        val paramOpt   = paramsByName.get(fieldName)
        val hint       = paramOpt.flatMap(readHint).orElse(readHint(field))
        val docstring  = paramOpt.flatMap(_.docstring).orElse(field.docstring)
        (fieldName, fieldTpe, !isOptional && !hasDefault, hint, docstring)
      }.toList

    def resolveSchema(tpe: TypeRepr, posOpt: Option[Position]): Expr[Schema] =
      tpe.asType match
        case '[t] =>
          Expr.summon[ToSchema[t]].fold(structuralSchema(tpe, posOpt))(toSchema => '{ $toSchema.schema })

    def structuralSchema(tpe: TypeRepr, posOpt: Option[Position]): Expr[Schema] =
      val errorPos = posOpt.getOrElse(Position.ofMacroExpansion)
      tpe.widen.simplified match
        case t if t <:< TypeRepr.of[Option[Any]]                                           =>
          val innerType   = t.typeArgs.head
          val innerSchema = resolveSchema(innerType, posOpt)
          '{ $innerSchema.copy(nullable = Some(true)) }
        case t if t <:< TypeRepr.of[List[Any]] || t <:< TypeRepr.of[Seq[Any]]              =>
          val innerType   = t.typeArgs.head
          val itemsSchema = resolveSchema(innerType, posOpt)
          '{ Schema.array(items = $itemsSchema) }
        case t if t.typeSymbol.flags.is(Flags.Case)                                        =>
          processCaseClassSchema(t, posOpt)
        case t if t.typeSymbol.flags.is(Flags.Sealed) || t.typeSymbol.flags.is(Flags.Enum) =>
          processEnumSchema(t)
        case other                                                                         =>
          report.errorAndAbort(s"Unsupported parameter type for schema generation: ${other.show}", errorPos)

    def processCaseClassSchema(tpe: TypeRepr, posOpt: Option[Position]): Expr[Schema] =
      val fields = getCaseClassFields(tpe)

      val propertiesExprs = fields.map { case (name, fieldTpe, _, hint, docstring) =>
        val schemaExpr     = withDocstring(resolveSchema(fieldTpe, posOpt), docstring)
        val schemaWithHint = applyHint(schemaExpr, hint)
        '{ ${ Expr(name) } -> $schemaWithHint }
      }

      val requiredExprs  = fields.filter(_._3).map(f => Expr(f._1))
      val baseSchemaExpr =
        withDocstring(
          '{
            Schema.obj(
              properties = Map(${ Varargs(propertiesExprs) }*),
              required = List(${ Varargs(requiredExprs) }*)
            )
          },
          tpe.typeSymbol.docstring
        )
      applyHint(baseSchemaExpr, readHint(tpe.typeSymbol))

    def processEnumSchema(tpe: TypeRepr): Expr[Schema] =
      val sym            = tpe.typeSymbol
      val children       = sym.children.map(_.name)
      val childrenExpr   = Expr(children.toList)
      val baseSchemaExpr = withDocstring('{ Schema.string(enm = $childrenExpr) }, sym.docstring)
      applyHint(baseSchemaExpr, readHint(sym))

    val pos = Some(Position.ofMacroExpansion)
    structuralSchema(TypeRepr.of[A], pos)
