package io.github.serhiip.constellations

import java.time.OffsetDateTime

import scala.quoted.*

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyChain, Validated, ValidatedNec}
import cats.syntax.all.*
import cats.{Applicative, Functor, MonadThrow, ~>}

import org.typelevel.log4cats.{LoggerFactory, StructuredLogger}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.{Counter, Meter}
import org.typelevel.otel4s.trace.Tracer

import io.github.serhiip.constellations.common.*
import io.github.serhiip.constellations.common.Observability.*
import io.github.serhiip.constellations.dispatcher.*
import io.github.serhiip.constellations.dispatcher.naming.SnakeCaseNamingStrategy.*

trait ToolDispatcher[F[_]]:
  def dispatch(call: FunctionCall): F[ToolDispatcher.Result]
  def dispatchAll(calls: List[FunctionCall]): F[ValidatedNec[AgentError, List[ToolDispatcher.Result]]]
  def getFunctionDeclarations: F[List[FunctionDeclaration]]
  def prepare(call: FunctionCall): ValidatedNec[AgentError, F[ToolDispatcher.Result]]

object ToolDispatcher:
  enum Result:
    case Response(result: FunctionResponse)
    case HumanInTheLoop

  def observed[F[_]: Tracer: LoggerFactory: MonadThrow: Meter](delegate: ToolDispatcher[F]): F[ToolDispatcher[F]] =
    Meters.create[F].flatMap(observed(delegate, _))

  final case class Meters[F[_]](
      dispatchSuccess: Counter[F, Long],
      dispatchError: Counter[F, Long],
      dispatchAllSuccess: Counter[F, Long],
      dispatchAllError: Counter[F, Long]
  )

  object Meters:
    def create[F[_]: Meter: Applicative]: F[Meters[F]] =
      val dispatch    = Metrics.component("tool_dispatcher")("dispatch")
      val dispatchAll = Metrics.component("tool_dispatcher")("dispatch_all")
      (
        Meter[F].counter[Long](dispatch("success_count")).create,
        Meter[F].counter[Long](dispatch("error_count")).create,
        Meter[F].counter[Long](dispatchAll("success_count")).create,
        Meter[F].counter[Long](dispatchAll("error_count")).create
      ).mapN(Meters.apply)

  private def observed[F[_]: MonadThrow: Tracer: LoggerFactory](
      delegate: ToolDispatcher[F],
      meters: Meters[F]
  ): F[ToolDispatcher[F]] =
    LoggerFactory[F].create.map { logger =>
      given StructuredLogger[F] = logger
      new ToolDispatcher[F]:
        def prepare(call: FunctionCall): ValidatedNec[AgentError, F[ToolDispatcher.Result]] = delegate.prepare(call)

        def dispatch(call: FunctionCall): F[ToolDispatcher.Result] =
          val traced = Tracer[F].span("tool-dispatcher", "dispatch")(dispatchSpanAttributes(call)*).logged { logger =>
            for
              _      <- logger.debug(s"Dispatching call: ${call.name} with args ${call.args}")
              result <- delegate.dispatch(call)
              _      <- logger.trace(s"Dispatch result: $result")
            yield result
          }
          traced.withOperationCounters(meters.dispatchSuccess, meters.dispatchError)

        def dispatchAll(calls: List[FunctionCall]): F[ValidatedNec[AgentError, List[ToolDispatcher.Result]]] =
          val traced =
            Tracer[F].span("tool-dispatcher", "dispatch-all")(Attribute("function_call_count", calls.size.toLong)).logged { logger =>
              for
                _      <- logger.debug(s"Dispatching ${calls.size} call(s): ${calls.map(_.name).mkString(",")}")
                result <- delegate.dispatchAll(calls)
                _      <- logger.trace(s"DispatchAll result: $result")
              yield result
            }
          traced.attempt.flatMap {
            case Right(Valid(results)) => meters.dispatchAllSuccess.add(1).as(results.validNec)
            case Right(Invalid(errs))  => meters.dispatchAllError.add(1).as(errs.invalid)
            case Left(error)           => meters.dispatchAllError.add(1) >> error.raiseError
          }

        def getFunctionDeclarations: F[List[FunctionDeclaration]] =
          Tracer[F].span("tool-dispatcher", "get-function-declarations").logged { logger =>
            for
              decls <- delegate.getFunctionDeclarations
              span  <- Tracer[F].currentSpanOrNoop
              _     <- span.addAttributes(getFunctionDeclarationsSpanAttributes(decls)*)
              _     <- logger.trace(s"Function declarations: ${decls.map(_.name).mkString(",")}")
            yield decls
          }
    }

  def noop[F[_]: Applicative]: ToolDispatcher[F] = new ToolDispatcher[F]:
    def prepare(call: FunctionCall): ValidatedNec[AgentError, F[ToolDispatcher.Result]] =
      AgentError.UnknownFunction(call).invalidNec

    def dispatch(call: FunctionCall): F[ToolDispatcher.Result] =
      throw new UnsupportedOperationException(s"Noop dispatcher does not support dispatching calls: ${call.name}")

    def dispatchAll(calls: List[FunctionCall]): F[ValidatedNec[AgentError, List[ToolDispatcher.Result]]] =
      calls.traverse(prepare).traverse(_.sequence)

    def getFunctionDeclarations: F[List[FunctionDeclaration]] = List.empty.pure[F]

  def combine[F[_]: MonadThrow](dispatchers: ToolDispatcher[F]*): F[ToolDispatcher[F]] =
    dispatchers.toList.traverse(d => d.getFunctionDeclarations.tupleLeft(d)).map(combineOwned[F])

  private def combineOwned[F[_]: MonadThrow](owned: List[(ToolDispatcher[F], List[FunctionDeclaration])]): ToolDispatcher[F] =
    new:
      private val (declarations, index) =
        owned.foldLeft(List.empty[FunctionDeclaration] -> Map.empty[String, ToolDispatcher[F]]):
          case ((decls, idx), (dispatcher, ds)) =>
            val fresh = ds.filterNot(d => idx.contains(d.name))
            (decls ++ fresh, idx ++ fresh.map(_.name -> dispatcher))

      def prepare(call: FunctionCall): ValidatedNec[AgentError, F[Result]] =
        index.get(call.name).fold(AgentError.UnknownFunction(call).invalidNec)(_.prepare(call))

      def dispatch(call: FunctionCall): F[Result] = prepare(call).valueOr(_.head.raiseError)

      def dispatchAll(calls: List[FunctionCall]): F[ValidatedNec[AgentError, List[Result]]] =
        calls.traverse(prepare).traverse(_.sequence)

      def getFunctionDeclarations: F[List[FunctionDeclaration]] = declarations.pure[F]

  inline def to[F[_], T[_[_]]]: T[F] => ToolDispatcher[F] = ${ macroImplTo[F, T] }

  inline def generate[F[_]](inline component: Any, inline optionalOtherComponents: Any*): ToolDispatcher[F] =
    ${ macroImpl[F]('component, 'optionalOtherComponents) }

  private def macroImplTo[F[_]: Type, T[F[_]]: Type](using quotes: Quotes): Expr[T[F] => ToolDispatcher[F]] =
    MacroSupport.buildFromTrait[F, T]

  private def macroImpl[F[_]: Type](componentExpr: Expr[Any], optionalExpr: Expr[Seq[Any]])(using quotes: Quotes): Expr[ToolDispatcher[F]] =
    MacroSupport.buildFromComponents[F](componentExpr, optionalExpr)

  private object MacroSupport:
    def buildFromTrait[F[_]: Type, T[F[_]]: Type](using Quotes): Expr[T[F] => ToolDispatcher[F]] =
      import quotes.reflect.*
      val traitSym = TypeRepr.of[T].typeSymbol
      if !traitSym.flags.is(Flags.Trait) then report.errorAndAbort(s"${traitSym.fullName} is not a trait.", Position.ofMacroExpansion)

      val methodType = MethodType(List("instance"))(_ => List(TypeRepr.of[T[F]]), _ => TypeRepr.of[ToolDispatcher[F]])

      val lambda = Lambda(
        Symbol.spliceOwner,
        methodType,
        (owner, params) =>
          val instanceTerm = params.headOption match
            case Some(term: Term) => term
            case Some(other)      => report.errorAndAbort(s"Expected a term parameter, got: ${other.show}", other.pos)
            case None             => report.errorAndAbort("Expected a single parameter for dispatcher lambda.", Position.ofMacroExpansion)
          buildDispatcherExpr[F](List((instanceTerm, traitSym))).asTerm.changeOwner(owner)
      )

      lambda.asExprOf[T[F] => ToolDispatcher[F]]

    def buildFromComponents[F[_]: Type](componentExpr: Expr[Any], optionalExpr: Expr[Seq[Any]])(using Quotes): Expr[ToolDispatcher[F]] =
      import quotes.reflect.*
      optionalExpr match
        case Varargs(args) =>
          val components    = componentExpr.asTerm :: args.toList.map(_.asTerm)
          val componentInfo = components.map(term => (term, resolveComponentTrait[F](term.tpe, term.pos)))
          buildDispatcherExpr[F](componentInfo)
        case _             =>
          buildFromComponentAndCollection[F](componentExpr, optionalExpr)

    /** Handles the spread form `generate(component, collection*)` where the elements are only known at runtime
      */
    def buildFromComponentAndCollection[F[_]: Type](componentExpr: Expr[Any], optionalExpr: Expr[Seq[Any]])(using
        Quotes
    ): Expr[ToolDispatcher[F]] =
      import quotes.reflect.*
      val mandatoryTerm  = componentExpr.asTerm
      val mandatoryTrait = resolveComponentTrait[F](mandatoryTerm.tpe, mandatoryTerm.pos)

      def unwrap(term: Term): Term = term match
        case Inlined(_, _, inner) => unwrap(inner)
        case Typed(inner, _)      => unwrap(inner)
        case other                => other

      val collectionTerm = unwrap(optionalExpr.asTerm)
      val collectionTpe  = collectionTerm.tpe.widen
      val elementType    = collectionTpe.baseType(TypeRepr.of[Seq[Any]].typeSymbol) match
        case AppliedType(_, List(arg)) => arg
        case _                         =>
          report.errorAndAbort(
            s"ToolDispatcher.generate can only spread a Seq of components; got ${collectionTpe.show}.",
            collectionTerm.pos
          )
      val elementTrait   = resolveComponentTrait[F](elementType, collectionTerm.pos)

      val monadThrow     =
        Expr.summon[MonadThrow[F]].getOrElse(report.errorAndAbort("No cats.MonadThrow given found for F", Position.ofMacroExpansion))
      val baseDispatcher = buildDispatcherExpr[F](List((mandatoryTerm, mandatoryTrait)))
      val baseDecls      = getMethodDeclarations(mandatoryTrait)
      val elemDecls      = getMethodDeclarations(elementTrait)

      elementType.asType match
        case '[e] =>
          val collectionExpr = collectionTerm.asExprOf[Seq[e]]
          val methodType     = MethodType(List("instance"))(_ => List(TypeRepr.of[e]), _ => TypeRepr.of[ToolDispatcher[F]])
          val elementLambda  = Lambda(
            Symbol.spliceOwner,
            methodType,
            (owner, params) =>
              val instanceTerm = params.headOption match
                case Some(term: Term) => term
                case Some(other)      => report.errorAndAbort(s"Expected a term parameter, got: ${other.show}", other.pos)
                case None             => report.errorAndAbort("Expected a single parameter for dispatcher lambda.", Position.ofMacroExpansion)
              buildDispatcherExpr[F](List((instanceTerm, elementTrait))).asTerm.changeOwner(owner)
          ).asExprOf[e => ToolDispatcher[F]]
          '{
            given MonadThrow[F] = $monadThrow
            val base            = $baseDispatcher
            val makeElement     = $elementLambda
            val elemDeclList    = $elemDecls
            val owned           =
              (base, $baseDecls) :: $collectionExpr.toList.map(instance => (makeElement(instance), elemDeclList))
            ToolDispatcher.combineOwned[F](owned)
          }

    def getCaseClassFields(using Quotes)(tpe: quotes.reflect.TypeRepr): List[(String, quotes.reflect.TypeRepr, Boolean, Option[String])] =
      import quotes.reflect.*
      tpe.typeSymbol.caseFields.map { field =>
        val fieldName  = field.name
        val fieldTpe   = tpe.memberType(field)
        val isOptional = fieldTpe <:< TypeRepr.of[Option[Any]]
        val docstring  = field.docstring
        (fieldName, fieldTpe, !isOptional, docstring)
      }.toList

    def tpeToSchema(using Quotes)(tpe: quotes.reflect.TypeRepr, posOpt: Option[quotes.reflect.Position]): Expr[Schema] =
      import quotes.reflect.*
      val errorPos = posOpt.getOrElse(Position.ofMacroExpansion)
      tpe.widen.simplified match
        case t if t =:= TypeRepr.of[String]                                                => '{ Schema.string() }
        case t if t =:= TypeRepr.of[OffsetDateTime]                                        => '{ Schema.string(format = Some("date-time")) }
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

    def processCaseClassSchema(using Quotes)(tpe: quotes.reflect.TypeRepr, posOpt: Option[quotes.reflect.Position]): Expr[Schema] =
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

    def processEnumSchema(using Quotes)(tpe: quotes.reflect.TypeRepr): Expr[Schema] =
      import quotes.reflect.*
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

    def processMethodForDeclaration(using Quotes)(traitSym: quotes.reflect.Symbol)(
        method: quotes.reflect.Symbol
    ): Expr[FunctionDeclaration] =
      import quotes.reflect.*
      val convertedMethodName = methodName(method.name)
      val qualifiedName       = s"${componentName(traitSym.name)}_$convertedMethodName"
      val docstring           = method.docstring

      def paramType(param: Symbol): TypeRepr =
        param.tree match
          case valDef: ValDef => valDef.tpt.tpe
          case other          => report.errorAndAbort(s"Expected ValDef for parameter '${param.name}', got: ${other.show}", other.pos)

      val params = method.paramSymss.headOption.getOrElse(List.empty).filterNot(_.isTypeParam)

      val parametersSchemaExpr =
        if params.isEmpty then '{ None }
        else
          val propertiesExprs = params.map { param =>
            val paramName       = parameterName(param.name)
            val paramTpe        = paramType(param)
            val paramSchemaExpr = tpeToSchema(paramTpe, param.pos)
            val doc             = param.docstring
            val schemaWithDesc  = doc match
              case Some(d) => '{ $paramSchemaExpr.copy(description = Some(${ Expr(d) })) }
              case None    => paramSchemaExpr
            '{ ${ Expr(paramName) } -> $schemaWithDesc }
          }

          val requiredExprs = params
            .filterNot(param => paramType(param) <:< TypeRepr.of[Option[Any]])
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

    def getMethodDeclarations(using Quotes)(traitSym: quotes.reflect.Symbol): Expr[List[FunctionDeclaration]] =
      import quotes.reflect.*
      val methods = traitSym.declarations.filter(m =>
        m.isDefDef && !m.flags.is(Flags.Private) && !m.flags.is(Flags.Protected) && !m.flags.is(
          Flags.Synthetic
        ) && !m.flags.is(Flags.Artifact) && !m.flags
          .is(
            Flags.CaseAccessor
          ) && !m.flags.is(Flags.StableRealizable)
      )
      Expr.ofList(methods.map(processMethodForDeclaration(traitSym)))

    def processMethodForDispatch[F[_]: Type](using Quotes)(
        repr: quotes.reflect.TypeRepr,
        from: quotes.reflect.Term
    )(method: quotes.reflect.Symbol): (String, Expr[FunctionCall => ValidatedNec[AgentError, F[ToolDispatcher.Result]]]) =
      import quotes.reflect.*
      val qualifiedName: String              = s"${componentName(repr.typeSymbol.name)}_${methodName(method.name)}"
      def paramType(param: Symbol): TypeRepr =
        param.tree match
          case valDef: ValDef => valDef.tpt.tpe
          case other          => report.errorAndAbort(s"Expected ValDef for parameter '${param.name}', got: ${other.show}", other.pos)
      qualifiedName -> '{ (call: FunctionCall) =>
        ${
          val params = method.paramSymss.headOption.getOrElse(List.empty).filterNot(_.isTypeParam)

          val argExprs = params.map { param =>
            paramType(param).asType match
              case '[t] =>
                val decoder   =
                  Expr
                    .summon[Decoder[Value, t]]
                    .getOrElse(
                      report.errorAndAbort(
                        s"No Decoder[Value, ${paramType(param).show}] found for parameter '${param.name}' in '${method.fullName}'"
                      )
                    )
                val paramName = Expr(parameterName(param.name))
                '{
                  call.args.fields.get($paramName) match
                    case Some(value) => $decoder.decode(value, $paramName)
                    case None        =>
                      if ${ Expr(paramType(param) <:< TypeRepr.of[Option[Any]]) } then Valid(None)
                      else Invalid(NonEmptyChain(Decoder.Error.MissingField($paramName)))
                }
              case _    =>
                report.errorAndAbort(
                  s"Unsupported parameter type in match: ${paramType(param).show}",
                  Symbol.spliceOwner.pos.get
                )
          }

          val validatedArgsExpr = '{ ${ Expr.ofList(argExprs) }.sequence }

          def callExpr =
            '{ (args: List[Any], call: FunctionCall) =>
              ${
                val terms      =
                  params.zipWithIndex.map { case (param, idx) =>
                    paramType(param).asType match
                      case '[t] => '{ args(${ Expr(idx) }).asInstanceOf[t] }.asExprOf[t].asTerm
                  }
                val applied    = Apply(Select(from, method), terms)
                val resultType = applied.tpe.widen.simplified
                val effect     = TypeRepr.of[F]
                val argTpeOpt  = resultType match
                  case AppliedType(tycon, List(arg)) if tycon =:= effect || tycon.dealias.simplified =:= effect.dealias.simplified =>
                    Some(arg)

                  case _ => None
                argTpeOpt match
                  case Some(argTpe) =>
                    argTpe.asType match
                      case '[t] =>
                        val functor =
                          Expr
                            .summon[Functor[F]]
                            .getOrElse(report.errorAndAbort("No cats.Functor given found for F", Position.ofMacroExpansion))
                        val encoder = '{ scala.compiletime.summonInline[ResultEncoder[t]] }
                        '{
                          $functor.map(${ applied.asExprOf[F[t]] })(value => $encoder.encode(call, value))
                        }
                      case _    =>
                        report.errorAndAbort(
                          s"Unsupported return type argument '${argTpe.show}' for method '${method.fullName}'",
                          Symbol.spliceOwner.pos.get
                        )
                  case None         =>
                    report.errorAndAbort(
                      s"Unsupported return type '${resultType.show}' for method '${method.fullName}': expected F[...]",
                      Symbol.spliceOwner.pos.get
                    )
              }
            }

          '{
            $validatedArgsExpr
              .leftMap(errors => NonEmptyChain.one(AgentError.ArgumentDecodingFailed(call, errors)))
              .map(args => $callExpr(args, call))
          }
        }
      }

    def processMethodsForDispatch[F[_]: Type](using Quotes)(
        symbol: quotes.reflect.Symbol,
        term: quotes.reflect.Term
    ) =
      import quotes.reflect.*
      val methods = symbol.declarations.filter(m =>
        m.isDefDef && !m.flags.is(Flags.Private) && !m.flags.is(Flags.Protected) && !m.flags.is(
          Flags.Synthetic
        ) && !m.flags.is(Flags.Artifact) && !m.flags
          .is(
            Flags.CaseAccessor
          ) && !m.flags.is(Flags.StableRealizable)
      )
      if methods.isEmpty then report.warning(s"Component ${symbol.fullName} has no public methods to route.", term.pos)
      methods.map(processMethodForDispatch[F](symbol.typeRef.dealias, term))

    def hasEffectType[F[_]: Type](using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
      import quotes.reflect.*
      tpe.dealias.simplified match
        case AppliedType(_, args) =>
          args match
            case List(arg) => arg =:= TypeRepr.of[F]
            case _         => false
        case _                    => false

    def resolveComponentTrait[F[_]: Type](using Quotes)(tpe: quotes.reflect.TypeRepr, pos: quotes.reflect.Position): quotes.reflect.Symbol =
      import quotes.reflect.*
      val baseType   = tpe.widen.dealias
      val candidates = baseType.baseClasses.flatMap { sym =>
        if sym.flags.is(Flags.Trait) then
          val applied = baseType.baseType(sym).dealias
          if hasEffectType[F](applied) then Some(sym) else None
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

    def buildDispatcherExpr[F[_]: Type](using Quotes)(
        componentInfo: List[(quotes.reflect.Term, quotes.reflect.Symbol)]
    ): Expr[ToolDispatcher[F]] =
      import quotes.reflect.*
      val functionDeclarationsExpr =
        componentInfo
          .map { case (_, traitSym) => getMethodDeclarations(traitSym) }
          .reduceLeftOption((left, right) => '{ $left ++ $right })
          .getOrElse('{ List.empty[FunctionDeclaration] })

      val callables  = componentInfo.flatMap { case (term, traitSym) => processMethodsForDispatch[F](traitSym, term) }
      val monadThrow =
        Expr.summon[MonadThrow[F]].getOrElse(report.errorAndAbort("No cats.MonadThrow given found for F", Position.ofMacroExpansion))
      val app        =
        Expr.summon[cats.Applicative[F]].getOrElse(report.errorAndAbort("No cats.Applicative given found for F", Position.ofMacroExpansion))

      '{
        new ToolDispatcher[F]:
          given Applicative[F] = $app

          private val preparers: Map[String, FunctionCall => ValidatedNec[AgentError, F[ToolDispatcher.Result]]] = Map(
            ${ Expr.ofList(callables.map { case (k, v) => '{ ${ Expr(k) } -> ${ v } } }) }*
          )

          def prepare(call: FunctionCall): ValidatedNec[AgentError, F[ToolDispatcher.Result]] =
            preparers.get(call.name).fold(AgentError.UnknownFunction(call).invalidNec)(_(call))

          def dispatch(call: FunctionCall): F[ToolDispatcher.Result] =
            prepare(call).fold(errs => $monadThrow.raiseError(errs.head), identity)

          def dispatchAll(calls: List[FunctionCall]): F[ValidatedNec[AgentError, List[ToolDispatcher.Result]]] =
            calls.traverse(prepare).fold(_.invalid.pure, _.sequence.map(Valid.apply))

          def getFunctionDeclarations: F[List[FunctionDeclaration]] = $app.pure($functionDeclarationsExpr)
      }

  def mapK[F[_], G[_]](dispatcher: ToolDispatcher[F])(f: F ~> G): ToolDispatcher[G] = new ToolDispatcher[G]:
    def prepare(call: FunctionCall): ValidatedNec[AgentError, G[ToolDispatcher.Result]] =
      dispatcher.prepare(call).map(f(_))

    def dispatch(call: FunctionCall): G[ToolDispatcher.Result] = f(dispatcher.dispatch(call))

    def dispatchAll(calls: List[FunctionCall]): G[ValidatedNec[AgentError, List[ToolDispatcher.Result]]] =
      f(dispatcher.dispatchAll(calls))

    def getFunctionDeclarations: G[List[FunctionDeclaration]] = f(dispatcher.getFunctionDeclarations)

  private def getFunctionDeclarationsSpanAttributes(decls: List[FunctionDeclaration]): List[Attribute[?]] =
    Attribute("function_declaration_count", decls.size.toLong) ::
      decls.zipWithIndex.map { case (d, i) =>
        Attribute(s"function_declaration.$i", d.name)
      }

  private def dispatchSpanAttributes(call: FunctionCall): List[Attribute[?]] =
    val nameAttr = Attribute("function_name", call.name)
    val idAttrs  = call.callId.toList.map(id => Attribute("function_call_id", id))
    nameAttr :: (idAttrs ++ structFieldsAsAttributes(call.args, "function"))

  private def structFieldsAsAttributes(struct: Struct, prefix: String): List[Attribute[?]] =
    struct.fields.toList.flatMap { case (key, value) =>
      valueAsAttributes(value, s"$prefix.$key")
    }

  private def valueAsAttributes(value: Value, path: String): List[Attribute[?]] =
    value match
      case Value.NullValue           => List(Attribute(path, "null"))
      case Value.NumberValue(n)      => List(Attribute(path, n.toString))
      case Value.StringValue(s)      => List(Attribute(path, s))
      case Value.BoolValue(b)        => List(Attribute(path, b.toString))
      case Value.StructValue(inner)  => structFieldsAsAttributes(inner, path)
      case Value.ListValue(elements) =>
        elements.zipWithIndex.toList.flatMap { case (elem, idx) =>
          valueAsAttributes(elem, s"$path.$idx")
        }
