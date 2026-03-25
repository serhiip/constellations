package io.github.serhiip.constellations

import scala.annotation.experimental
import scala.quoted.*

import cats.data.NonEmptyChain
import cats.data.Validated.{Invalid, Valid}
import cats.syntax.all.*
import cats.{Functor, Monad, Show, ~>}

import org.typelevel.log4cats.{LoggerFactory, StructuredLogger}
import org.typelevel.otel4s.trace.Tracer

import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Observability.*
import io.github.serhiip.constellations.dispatcher.*
import io.github.serhiip.constellations.dispatcher.naming.SnakeCaseNamingStrategy._

trait Dispatcher[F[_]]:
  def dispatch(call: FunctionCall): F[Dispatcher.Result]
  def getFunctionDeclarations: F[List[FunctionDeclaration]]

object Dispatcher:
  enum Result:
    case Response(result: FunctionResponse)
    case HumanInTheLoop

  def apply[F[_]: Tracer: LoggerFactory: Monad](delegate: Dispatcher[F]): F[Dispatcher[F]] = observed(delegate)

  private def observed[F[_]: Monad: Tracer: LoggerFactory](delegate: Dispatcher[F]): F[Dispatcher[F]] =
    LoggerFactory[F].create.map { logger =>
      given StructuredLogger[F] = logger
      new Dispatcher[F]:
        def dispatch(call: FunctionCall): F[Dispatcher.Result] =
          Tracer[F].span("dispatcher", "dispatch").logged { logger =>
            for
              _      <- logger.trace(s"Dispatching call: ${call.name}")
              result <- delegate.dispatch(call)
              _      <- logger.trace(s"Dispatch result: $result")
            yield result
          }

        def getFunctionDeclarations: F[List[FunctionDeclaration]] =
          Tracer[F].span("dispatcher", "get-function-declarations").logged { logger =>
            for
              decls <- delegate.getFunctionDeclarations
              _     <- logger.trace(s"Function declarations: ${decls.map(_.name).mkString(",")}")
            yield decls
          }
    }

  def noop[F[_]: cats.Applicative]: Dispatcher[F] = new Dispatcher[F]:
    def dispatch(call: FunctionCall): F[Dispatcher.Result] =
      throw new UnsupportedOperationException(s"Noop dispatcher does not support dispatching calls: ${call.name}")

    def getFunctionDeclarations: F[List[FunctionDeclaration]] = List.empty.pure[F]

  @experimental
  inline def generate[F[_]](inline component: Any, inline optionalOtherComponents: Any*): Dispatcher[F] =
    ${ macroImpl[F]('component, 'optionalOtherComponents) }

  @experimental
  private def macroImpl[F[_]: Type](componentExpr: Expr[Any], optionalExpr: Expr[Seq[Any]])(using quotes: Quotes): Expr[Dispatcher[F]] =
    import quotes.reflect.*

    def getCaseClassFields(tpe: TypeRepr): List[(String, TypeRepr, Boolean, Option[String])] =
      tpe.typeSymbol.caseFields.map { field =>
        val fieldName  = field.name
        val fieldTpe   = tpe.memberType(field)
        val isOptional = fieldTpe <:< TypeRepr.of[Option[Any]]
        val docstring  = field.docstring
        (fieldName, fieldTpe, !isOptional, docstring)
      }.toList

    def tpeToSchema(tpe: TypeRepr, posOpt: Option[Position]): Expr[Schema] =
      val errorPos = posOpt.getOrElse(Position.ofMacroExpansion)
      tpe.widen.simplified match
        case t if t =:= TypeRepr.of[String]                                                => '{ Schema.string() }
        case t if t =:= TypeRepr.of[Int]                                                   => '{ Schema.integer() }
        case t if t =:= TypeRepr.of[Long]                                                  => '{ Schema.integer() }
        case t if t =:= TypeRepr.of[Double]                                                => '{ Schema.number() }
        case t if t =:= TypeRepr.of[Float]                                                 => '{ Schema.number() }
        case t if t =:= TypeRepr.of[Boolean]                                               => '{ Schema.boolean() }
        case t if t <:< TypeRepr.of[Option[Any]]                                           =>
          val innerType   = t.typeArgs.head
          val innerSchema = tpeToSchema(innerType, posOpt)
          '{ $innerSchema.copy(nullable = Some(true)) }
        case t if t <:< TypeRepr.of[List[Any]] || t <:< TypeRepr.of[Seq[Any]]              =>
          val innerType   = t.typeArgs.head
          val itemsSchema = tpeToSchema(innerType, posOpt)
          '{ Schema.array(items = $itemsSchema) }
        case t if t.typeSymbol.flags.is(Flags.Case)                                        =>
          processCaseClassSchema(t, posOpt)
        case t if t.typeSymbol.flags.is(Flags.Sealed) || t.typeSymbol.flags.is(Flags.Enum) =>
          processEnumSchema(t)
        case other                                                                         =>
          report.errorAndAbort(s"Unsupported parameter type for schema generation: ${other.show}", errorPos)

    def processCaseClassSchema(tpe: TypeRepr, posOpt: Option[Position]): Expr[Schema] =
      val fields = getCaseClassFields(tpe)

      val propertiesExprs = fields.map { case (name, fieldTpe, _, docstring) =>
        val schemaExpr     = tpeToSchema(fieldTpe, posOpt)
        val schemaWithDesc = docstring match
          case Some(doc) => '{ $schemaExpr.copy(description = Some(${ Expr(doc) })) }
          case None      => schemaExpr
        '{ ${ Expr(name) } -> $schemaWithDesc }
      }

      val requiredExprs = fields.filter(_._3).map(f => Expr(f._1))

      val docstring         = tpe.typeSymbol.docstring
      val schemaDescription = Expr(docstring)

      '{
        Schema.obj(
          description = $schemaDescription,
          properties = Map(${ Varargs(propertiesExprs) }*),
          required = List(${ Varargs(requiredExprs) }*)
        )
      }

    def processEnumSchema(tpe: TypeRepr): Expr[Schema] =
      val sym          = tpe.typeSymbol
      val children     =
        if sym.flags.is(Flags.Enum) then
          // scala 3 enum
          sym.children.filter(_.isValDef).map(_.name)
        else
          // sealed trait
          sym.children.map(_.name)
      val childrenExpr = Expr(children.toList)
      '{ Schema.string(enm = $childrenExpr) }

    def processMethodForDeclaration(traitSym: Symbol)(method: Symbol): Expr[FunctionDeclaration] =
      val convertedMethodName = methodName(method.name)
      val qualifiedName       = s"${componentName(traitSym.name)}_$convertedMethodName"
      val docstring           = method.docstring

      val params = method.paramSymss.headOption.getOrElse(List.empty).filterNot(_.isTypeParam)

      val parametersSchemaExpr =
        if params.isEmpty then '{ None }
        else
          val propertiesExprs = params.map { param =>
            val paramName       = parameterName(param.name)
            val paramTpe        = param.info
            val paramSchemaExpr = tpeToSchema(paramTpe, param.pos)
            val doc             = param.docstring
            val schemaWithDesc  = doc match
              case Some(d) => '{ $paramSchemaExpr.copy(description = Some(${ Expr(d) })) }
              case None    => paramSchemaExpr
            '{ ${ Expr(paramName) } -> $schemaWithDesc }
          }

          val requiredExprs = params
            .filterNot(_.info <:< TypeRepr.of[Option[Any]])
            .map(p => Expr(parameterName(p.name)))

          '{
            Some(
              Schema.obj(
                properties = Map(${ Varargs(propertiesExprs) }*),
                required = List(${ Varargs(requiredExprs) }*)
              )
            )
          }

      '{
        FunctionDeclaration(
          name = ${ Expr(qualifiedName) },
          description = ${ Expr(docstring) },
          parameters = $parametersSchemaExpr
        )
      }

    def getMethodDeclarations(traitSym: Symbol): Expr[List[FunctionDeclaration]] =
      val methods = traitSym.declarations.filter(m =>
        m.isDefDef && !m.flags.is(Flags.Private) && !m.flags.is(Flags.Protected) && !m.flags.is(
          Flags.Synthetic
        ) && !m.flags.is(Flags.Artifact) && !m.flags
          .is(
            Flags.CaseAccessor
          ) && !m.flags.is(Flags.StableRealizable)
      )
      Expr.ofList(methods.map(processMethodForDeclaration(traitSym)))

    def processMethodForDispatch(repr: TypeRepr, from: Term)(
        method: Symbol
    ): (String, Expr[FunctionCall => F[Dispatcher.Result]]) =
      val qualifiedName: String = s"${componentName(repr.typeSymbol.name)}_${methodName(method.name)}"
      qualifiedName -> '{ (call: FunctionCall) =>
        ${
          val params = method.paramSymss.headOption.getOrElse(List.empty).filterNot(_.isTypeParam)

          val argExprs = params.map { param =>
            param.info.asType match
              case '[t] =>
                val decoder   =
                  Expr
                    .summon[Decoder[Value, t]]
                    .getOrElse(
                      report.errorAndAbort(
                        s"No Decoder[Value, ${param.info.show}] found for parameter '${param.name}' in '${method.fullName}'"
                      )
                    )
                val paramName = Expr(parameterName(param.name))
                '{
                  call.args.fields.get($paramName) match
                    case Some(value) => $decoder.decode(value, $paramName)
                    case None        =>
                      if ${ Expr(param.info <:< TypeRepr.of[Option[Any]]) } then Valid(None)
                      else Invalid(NonEmptyChain(Decoder.Error.MissingField($paramName)))
                }
              case _    =>
                report.errorAndAbort(
                  s"Unsupported parameter type in match: ${param.info.show}",
                  Symbol.spliceOwner.pos.get
                )
          }

          val validatedArgsExpr = '{ ${ Expr.ofList(argExprs) }.sequence }

          def callExpr =
            '{ (args: List[Any], callName: String) =>
              ${
                val terms      =
                  params.zipWithIndex.map { case (param, idx) =>
                    param.info.asType match
                      case '[t] => '{ args(${ Expr(idx) }).asInstanceOf[t] }.asExprOf[t].asTerm
                  }
                val applied    = Apply(Select(from, method), terms)
                val resultType = applied.tpe.widen.simplified
                resultType.asType match
                  case '[F[t]] =>
                    val functor =
                      Expr
                        .summon[Functor[F]]
                        .getOrElse(report.errorAndAbort("No cats.Functor given found for F", Position.ofMacroExpansion))
                    val encoder = '{ scala.compiletime.summonInline[ResultEncoder[t]] }
                    '{
                      $functor.map(${ applied.asExprOf[F[t]] })(value => $encoder.encode(callName, value))
                    }
                  case _       =>
                    report.errorAndAbort(
                      s"Unsupported return type '${resultType.show}' for method '${method.fullName}': expected F[...]",
                      Symbol.spliceOwner.pos.get
                    )
              }
            }

          '{
            $validatedArgsExpr
              .map(args => $callExpr(args, call.name))
              .valueOr: errors =>
                given Show[Decoder.Error] = Decoder.given_Show_Error
                val errorString           = errors.mkString_(delim = ", ")
                throw new IllegalArgumentException(
                  s"Failed to decode arguments for method '${${ Expr(qualifiedName) }}': $errorString"
                )
          }
        }
      }

    def processMethodsForDispatch(symbol: Symbol, term: Term) =
      val methods = symbol.declarations.filter(m =>
        m.isDefDef && !m.flags.is(Flags.Private) && !m.flags.is(Flags.Protected) && !m.flags.is(
          Flags.Synthetic
        ) && !m.flags.is(Flags.Artifact) && !m.flags
          .is(
            Flags.CaseAccessor
          ) && !m.flags.is(Flags.StableRealizable)
      )
      if methods.isEmpty then report.warning(s"Component ${symbol.fullName} has no public methods to route.", term.pos)
      methods.map(processMethodForDispatch(symbol.typeRef.dealias, term))

    def hasEffectType(tpe: TypeRepr): Boolean =
      tpe.dealias.simplified match
        case AppliedType(_, args) =>
          args match
            case List(arg) => arg =:= TypeRepr.of[F]
            case _         => false
        case _                    => false

    def resolveComponentTrait(tpe: TypeRepr, pos: Position): Symbol =
      val baseType   = tpe.widen.dealias
      val candidates = baseType.baseClasses.flatMap { sym =>
        if sym.flags.is(Flags.Trait) then
          val applied = baseType.baseType(sym).dealias
          if hasEffectType(applied) then Some(sym) else None
        else None
      }.distinct
      candidates match
        case sym :: Nil => sym
        case Nil        =>
          report.errorAndAbort(
            s"Component ${baseType.show} must be typed as a trait with effect type ${TypeRepr.of[F].show}",
            pos
          )
        case many       =>
          report.errorAndAbort(
            s"Component ${baseType.show} implements multiple traits with effect type ${TypeRepr.of[F].show}: ${many.map(_.fullName).mkString(", ")}",
            pos
          )

    def extractComponents(expr: Expr[Seq[Any]]): List[Term] =
      expr match
        case Varargs(args) => args.toList.map(_.asTerm)
        case _             =>
          def loop(term: Term, env: Map[Symbol, Term]): List[Term] = term match
            case Inlined(_, _, inner)                          => loop(inner, env)
            case Typed(inner, _)                               => loop(inner, env)
            case Block(stats, inner)                           =>
              val newBindings = stats.collect {
                case valDef: ValDef if valDef.rhs.nonEmpty =>
                  valDef.symbol -> valDef.rhs.get
              }.toMap
              loop(inner, env ++ newBindings)
            case ident: Ident if env.contains(ident.symbol)    => loop(env(ident.symbol), env)
            case Repeated(elems, _)                            => elems
            case Apply(Select(_, "apply"), args)               => args
            case Apply(TypeApply(Select(_, "apply"), _), args) => args
            case other                                         =>
              report.errorAndAbort(
                "Dispatcher.generate requires explicit component arguments. Avoid passing a Seq or collection.",
                other.pos
              )
          loop(expr.asTerm, Map.empty)

    val components = componentExpr.asTerm :: extractComponents(optionalExpr)

    val componentInfo = components.map { term =>
      val traitSym = resolveComponentTrait(term.tpe, term.pos)
      (term, traitSym)
    }

    val functionDeclarationsExpr =
      componentInfo
        .map { case (_, traitSym) => getMethodDeclarations(traitSym) }
        .reduceLeftOption((left, right) => '{ $left ++ $right })
        .getOrElse('{ List.empty[FunctionDeclaration] })

    val callables = componentInfo.flatMap { case (term, traitSym) => processMethodsForDispatch(traitSym, term) }

    '{
      new Dispatcher[F]:

        private val methodMap: Map[String, FunctionCall => F[Dispatcher.Result]] = Map(
          ${ Expr.ofList(callables.map { case (k, v) => '{ ${ Expr(k) } -> ${ v } } }) }*
        )

        def dispatch(call: FunctionCall): F[Dispatcher.Result] =
          methodMap.getOrElse(call.name, throw RuntimeException(s"No handler for ${call.name}"))(call)

        def getFunctionDeclarations: F[List[FunctionDeclaration]] = ${
          val app = Expr
            .summon[cats.Applicative[F]]
            .getOrElse(report.errorAndAbort("No cats.Applicative given found for F", Position.ofMacroExpansion))
          '{ $app.pure($functionDeclarationsExpr) }
        }
    }

  def mapK[F[_], G[_]](dispatcher: Dispatcher[F])(f: F ~> G): Dispatcher[G] = new Dispatcher[G]:
    def dispatch(call: FunctionCall): G[Dispatcher.Result]             = f(dispatcher.dispatch(call))
    def getFunctionDeclarations: G[List[FunctionDeclaration]]          = f(dispatcher.getFunctionDeclarations)
