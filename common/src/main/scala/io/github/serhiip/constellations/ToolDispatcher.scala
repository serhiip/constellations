package io.github.serhiip.constellations

import scala.compiletime.summonInline
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
import io.github.serhiip.constellations.naming.Configuration
import io.github.serhiip.constellations.schema.ToSchema

trait ToolDispatcher[F[_]]:
  def dispatch(call: FunctionCall): F[ToolDispatcher.Result]
  def dispatchAll(calls: List[FunctionCall]): F[ValidatedNec[AgentError, List[ToolDispatcher.Result]]]
  def getFunctionDeclarations: F[List[FunctionDeclaration]]
  def prepare(call: FunctionCall): ValidatedNec[AgentError, F[ToolDispatcher.Result]]
  def declarationOf(component: String, method: String): Option[FunctionDeclaration] = None

object ToolDispatcher:
  type Probe[A] = Any

  enum Result:
    case Response(result: FunctionResponse)
    case HumanInTheLoop

  extension [F[_]](dispatcher: ToolDispatcher[F])
    inline def getDeclaration[T[_[_]]](inline select: T[Probe] => Any): Option[FunctionDeclaration] =
      ${ declarationImpl[T, F]('dispatcher, 'select) }

  private def declarationImpl[T[_[_]]: Type, F[_]: Type](
      dispatcher: Expr[ToolDispatcher[F]],
      select: Expr[T[Probe] => Any]
  )(using Quotes): Expr[Option[FunctionDeclaration]] =
    import quotes.reflect.*
    val traitSym = TypeRepr.of[T].typeSymbol
    if !traitSym.flags.is(Flags.Trait) then report.errorAndAbort(s"${traitSym.fullName} is not a trait.", Position.ofMacroExpansion)

    val methods = new TreeAccumulator[List[Symbol]]:
      def foldTree(acc: List[Symbol], tree: Tree)(owner: Symbol): List[Symbol] = tree match
        case Select(_, _) =>
          val sym = tree.symbol
          if sym.isDefDef then sym :: acc else foldOverTree(acc, tree)(owner)
        case _            => foldOverTree(acc, tree)(owner)

    val selected = methods.foldTree(Nil, select.asTerm)(Symbol.spliceOwner).distinct
    selected match
      case method :: Nil if method.owner == traitSym || traitSym.declarations.contains(method) =>
        '{ $dispatcher.declarationOf(${ Expr(traitSym.name) }, ${ Expr(method.name) }) }
      case method :: Nil                                                                       =>
        report.errorAndAbort(
          s"Method '${method.name}' is not a direct declaration of ${traitSym.fullName}; inherited members are not registered as tools.",
          select.asTerm.pos
        )

      case Nil  => report.errorAndAbort(s"Selector does not refer to a method of ${traitSym.fullName}.", select.asTerm.pos)
      case many =>
        report.errorAndAbort(
          s"Selector refers to multiple methods of ${traitSym.fullName}: ${many.map(_.name).mkString(", ")}.",
          select.asTerm.pos
        )

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

        override def declarationOf(component: String, method: String): Option[FunctionDeclaration] =
          delegate.declarationOf(component, method)
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

      override def declarationOf(component: String, method: String): Option[FunctionDeclaration] =
        owned.iterator.flatMap((d, _) => d.declarationOf(component, method)).nextOption()

  inline def to[F[_], T[_[_]]](using config: Configuration = Configuration.snakeCase): T[F] => ToolDispatcher[F] =
    ${ macroImplTo[F, T]('config) }

  inline def generate[F[_]](
      inline component: Any,
      inline optionalOtherComponents: Any*
  )(using config: Configuration = Configuration.snakeCase): ToolDispatcher[F] =
    ${ macroImpl[F]('component, 'optionalOtherComponents, 'config) }

  private def macroImplTo[F[_]: Type, T[F[_]]: Type](config: Expr[Configuration])(using quotes: Quotes): Expr[T[F] => ToolDispatcher[F]] =
    MacroSupport.buildFromTrait[F, T](config)

  private def macroImpl[F[_]: Type](
      componentExpr: Expr[Any],
      optionalExpr: Expr[Seq[Any]],
      config: Expr[Configuration]
  )(using quotes: Quotes): Expr[ToolDispatcher[F]] =
    MacroSupport.buildFromComponents[F](componentExpr, optionalExpr, config)

  private object MacroSupport:
    def buildFromTrait[F[_]: Type, T[F[_]]: Type](config: Expr[Configuration])(using Quotes): Expr[T[F] => ToolDispatcher[F]] =
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
          buildDispatcherExpr[F](List((instanceTerm, traitSym)), config).asTerm.changeOwner(owner)
      )

      lambda.asExprOf[T[F] => ToolDispatcher[F]]

    def buildFromComponents[F[_]: Type](
        componentExpr: Expr[Any],
        optionalExpr: Expr[Seq[Any]],
        config: Expr[Configuration]
    )(using Quotes): Expr[ToolDispatcher[F]] =
      import quotes.reflect.*
      optionalExpr match
        case Varargs(args) =>
          val components    = componentExpr.asTerm :: args.toList.map(_.asTerm)
          val componentInfo = components.map(term => (term, resolveComponentTrait[F](term.tpe, term.pos)))
          buildDispatcherExpr[F](componentInfo, config)
        case _             =>
          buildFromComponentAndCollection[F](componentExpr, optionalExpr, config)

    def buildFromComponentAndCollection[F[_]: Type](
        componentExpr: Expr[Any],
        optionalExpr: Expr[Seq[Any]],
        config: Expr[Configuration]
    )(using Quotes): Expr[ToolDispatcher[F]] =
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
      val baseDispatcher = buildDispatcherExpr[F](List((mandatoryTerm, mandatoryTrait)), config)
      val baseDecls      = getMethodDeclarations(mandatoryTrait, config)
      val elemDecls      = getMethodDeclarations(elementTrait, config)

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
              buildDispatcherExpr[F](List((instanceTerm, elementTrait)), config).asTerm.changeOwner(owner)
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

    def processMethodForDeclaration(using Quotes)(
        traitSym: quotes.reflect.Symbol,
        config: Expr[Configuration]
    )(method: quotes.reflect.Symbol): Expr[FunctionDeclaration] =
      import quotes.reflect.*
      val traitName  = Expr(traitSym.name)
      val methodName = Expr(method.name)
      val docstring  = method.docstring

      def paramType(param: Symbol): TypeRepr =
        param.tree match
          case valDef: ValDef => valDef.tpt.tpe
          case other          => report.errorAndAbort(s"Expected ValDef for parameter '${param.name}', got: ${other.show}", other.pos)

      val params = method.paramSymss.headOption.getOrElse(List.empty).filterNot(_.isTypeParam)

      val parametersSchemaExpr =
        if params.isEmpty then '{ None }
        else
          val propertiesExprs = params.map { param =>
            val scalaName       = Expr(param.name)
            val paramTpe        = paramType(param)
            val paramSchemaExpr = paramTpe.asType match
              case '[t] => '{ summonInline[ToSchema[t]].schemaWith($config) }
            val doc             = param.docstring
            val schemaWithDesc  = doc match
              case Some(d) => '{ $paramSchemaExpr.copy(description = Some(${ Expr(d) })) }
              case None    => paramSchemaExpr
            '{ $config.transformMemberNames($scalaName) -> $schemaWithDesc }
          }

          val requiredExprs = params
            .filterNot(param => paramType(param) <:< TypeRepr.of[Option[Any]])
            .map(p => '{ $config.transformMemberNames(${ Expr(p.name) }) })

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
          name = s"${$config.transformComponentNames($traitName)}_${$config.transformMethodNames($methodName)}",
          description = ${ Expr(docstring) },
          parameters = $parametersSchemaExpr
        )
      }

    def toolMethods(using Quotes)(traitSym: quotes.reflect.Symbol): List[quotes.reflect.Symbol] =
      import quotes.reflect.*
      traitSym.declarations.filter(m =>
        m.isDefDef && !m.flags.is(Flags.Private) && !m.flags.is(Flags.Protected) && !m.flags.is(
          Flags.Synthetic
        ) && !m.flags.is(Flags.Artifact) && !m.flags
          .is(
            Flags.CaseAccessor
          ) && !m.flags.is(Flags.StableRealizable)
      )

    def getMethodDeclarationEntries(using Quotes)(
        traitSym: quotes.reflect.Symbol,
        config: Expr[Configuration]
    ): Expr[List[((String, String), FunctionDeclaration)]] =
      val entries = toolMethods(traitSym).map { method =>
        val key  = Expr((traitSym.name, method.name))
        val decl = processMethodForDeclaration(traitSym, config)(method)
        '{ $key -> $decl }
      }
      Expr.ofList(entries)

    def getMethodDeclarations(using Quotes)(
        traitSym: quotes.reflect.Symbol,
        config: Expr[Configuration]
    ): Expr[List[FunctionDeclaration]] =
      val entries = getMethodDeclarationEntries(traitSym, config)
      '{ $entries.map(_._2) }

    def processMethodForDispatch[F[_]: Type](using Quotes)(
        repr: quotes.reflect.TypeRepr,
        from: quotes.reflect.Term,
        config: Expr[Configuration]
    )(method: quotes.reflect.Symbol): (Expr[String], Expr[FunctionCall => ValidatedNec[AgentError, F[ToolDispatcher.Result]]]) =
      import quotes.reflect.*
      val traitNameExpr                      = Expr(repr.typeSymbol.name)
      val methodNameExpr                     = Expr(method.name)
      val qualifiedName                      =
        '{ s"${$config.transformComponentNames($traitNameExpr)}_${$config.transformMethodNames($methodNameExpr)}" }
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
                val scalaName = Expr(param.name)
                '{
                  val paramName = $config.transformMemberNames($scalaName)
                  call.args.fields.get(paramName) match
                    case Some(value) => $decoder.decode(value, paramName, $config)
                    case None        =>
                      if ${ Expr(paramType(param) <:< TypeRepr.of[Option[Any]]) } then Valid(None)
                      else Invalid(NonEmptyChain(Decoder.Error.MissingField(paramName)))
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
                          $functor.map(${ applied.asExprOf[F[t]] })(value => $encoder.encode(call, value, $config))
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
        term: quotes.reflect.Term,
        config: Expr[Configuration]
    ) =
      import quotes.reflect.*
      val methods = toolMethods(symbol)
      if methods.isEmpty then report.warning(s"Component ${symbol.fullName} has no public methods to route.", term.pos)
      methods.map(processMethodForDispatch[F](symbol.typeRef.dealias, term, config))

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
        componentInfo: List[(quotes.reflect.Term, quotes.reflect.Symbol)],
        config: Expr[Configuration]
    ): Expr[ToolDispatcher[F]] =
      import quotes.reflect.*
      val entriesExpr =
        componentInfo
          .map { case (_, traitSym) => getMethodDeclarationEntries(traitSym, config) }
          .reduceLeftOption((left, right) => '{ $left ++ $right })
          .getOrElse('{ List.empty[((String, String), FunctionDeclaration)] })

      val callables  = componentInfo.flatMap { case (term, traitSym) => processMethodsForDispatch[F](traitSym, term, config) }
      val monadThrow =
        Expr.summon[MonadThrow[F]].getOrElse(report.errorAndAbort("No cats.MonadThrow given found for F", Position.ofMacroExpansion))
      val app        =
        Expr.summon[cats.Applicative[F]].getOrElse(report.errorAndAbort("No cats.Applicative given found for F", Position.ofMacroExpansion))

      '{
        new ToolDispatcher[F]:
          given Applicative[F] = $app

          private val entries: List[((String, String), FunctionDeclaration)] = $entriesExpr
          private val declarations: List[FunctionDeclaration]                = entries.map(_._2)
          private val byName: Map[(String, String), FunctionDeclaration]     =
            entries.foldLeft(Map.empty[(String, String), FunctionDeclaration])((acc, e) => acc.updatedWith(e._1)(_.orElse(Some(e._2))))

          private val preparers: Map[String, FunctionCall => ValidatedNec[AgentError, F[ToolDispatcher.Result]]] = Map(
            ${ Expr.ofList(callables.map { case (k, v) => '{ $k -> $v } }) }*
          )

          def prepare(call: FunctionCall): ValidatedNec[AgentError, F[ToolDispatcher.Result]] =
            preparers.get(call.name).fold(AgentError.UnknownFunction(call).invalidNec)(_(call))

          def dispatch(call: FunctionCall): F[ToolDispatcher.Result] =
            prepare(call).fold(errs => $monadThrow.raiseError(errs.head), identity)

          def dispatchAll(calls: List[FunctionCall]): F[ValidatedNec[AgentError, List[ToolDispatcher.Result]]] =
            calls.traverse(prepare).fold(_.invalid.pure, _.sequence.map(Valid.apply))

          def getFunctionDeclarations: F[List[FunctionDeclaration]] = $app.pure(declarations)

          override def declarationOf(component: String, method: String): Option[FunctionDeclaration] =
            byName.get(component -> method)
      }

  def mapK[F[_], G[_]](dispatcher: ToolDispatcher[F])(f: F ~> G): ToolDispatcher[G] = new ToolDispatcher[G]:
    def prepare(call: FunctionCall): ValidatedNec[AgentError, G[ToolDispatcher.Result]] =
      dispatcher.prepare(call).map(f(_))

    def dispatch(call: FunctionCall): G[ToolDispatcher.Result] = f(dispatcher.dispatch(call))

    def dispatchAll(calls: List[FunctionCall]): G[ValidatedNec[AgentError, List[ToolDispatcher.Result]]] =
      f(dispatcher.dispatchAll(calls))

    def getFunctionDeclarations: G[List[FunctionDeclaration]] = f(dispatcher.getFunctionDeclarations)

    override def declarationOf(component: String, method: String): Option[FunctionDeclaration] =
      dispatcher.declarationOf(component, method)

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
