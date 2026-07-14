package io.github.serhiip.constellations.gcprag

import cats.Id
import cats.data.{Chain, EitherT, NonEmptyList as NEL, WriterT}
import cats.syntax.all.*
import munit.FunSuite

import io.github.serhiip.constellations.TextSimilarity

final class RagEngineTest extends FunSuite:

  private final case class RetrieveCall(corpusName: String, query: String, config: RetrievalConfig)

  private type W[A] = WriterT[Id, Chain[RetrieveCall], A]
  private type F[A] = EitherT[W, Throwable, A]

  private val corpus = "projects/p/locations/us-east4/ragCorpora/123"

  private given ContextDecoder[F, String] = ContextDecoder(_.text.pure[F])

  test("findClosest retrieves with topK from k and decodes contexts") {
    val contexts                       = List(
      RetrievedContext("chunk-a", Some("gs://b/a.txt"), Some("a.txt"), Some(0.1)),
      RetrievedContext("chunk-b", Some("gs://b/b.txt"), Some("b.txt"), Some(0.2))
    )
    val sim: TextSimilarity[F, String] = RagEngine.similarity(retrieving(contexts), corpus, RetrievalConfig())
    val (calls, result)                = run(sim.findClosest("what is rag", k = 2))

    assertEquals(result, Right(NEL.of("chunk-a", "chunk-b")))
    assertEquals(calls.toList, List(RetrieveCall(corpus, "what is rag", RetrievalConfig(topK = Some(2)))))
  }

  test("findClosest keeps default retrieval topK when k is not positive") {
    val retrieval       = RetrievalConfig(topK = Some(5), vectorDistanceThreshold = Some(0.4))
    val sim             = RagEngine.similarity(retrieving(List(RetrievedContext("only", None, None, None))), corpus, retrieval)
    val (calls, result) = run(sim.findClosest("q", k = 0))

    assertEquals(result, Right(NEL.one("only")))
    assertEquals(calls.toList.map(_.config), List(retrieval))
  }

  test("findClosest fails when no contexts are returned") {
    val sim             = RagEngine.similarity(retrieving(Nil), corpus, RetrievalConfig())
    val (calls, result) = run(sim.findClosest("missing", k = 1))

    assertEquals(calls.toList, List(RetrieveCall(corpus, "missing", RetrievalConfig(topK = Some(1)))))
    assertEquals(result, Left(RagEngine.Error.NoContextsFound(corpus)))
  }

  test("findClosest propagates decoder failures") {
    given ContextDecoder[F, String] = ContextDecoder(_ => RuntimeException("boom").raiseError)
    val sim                         = RagEngine.similarity(retrieving(List(RetrievedContext("x", None, None, None))), corpus, RetrievalConfig())
    val (calls, result)             = run(sim.findClosest("q", k = 1))

    assertEquals(calls.size, 1L)
    assertEquals(result.leftMap(_.getMessage), Left("boom"))
  }

  private def retrieving(contexts: List[RetrievedContext]): RagClient[F] = new:
    def retrieveContexts(corpusName: String, query: String, config: RetrievalConfig): F[List[RetrievedContext]] =
      EitherT.liftF(WriterT.tell[Id, Chain[RetrieveCall]](Chain.one(RetrieveCall(corpusName, query, config)))).as(contexts)

    def createCorpus(config: CorpusConfig): F[StartedLro[F, Corpus]]                             = unused
    def getCorpus(name: String): F[Corpus]                                                       = unused
    def listCorpora: F[List[Corpus]]                                                             = unused
    def updateCorpus(request: UpdateCorpusRequest): F[StartedLro[F, Corpus]]                     = unused
    def deleteCorpus(name: String): F[StartedLro[F, Unit]]                                       = unused
    def importFiles(corpusName: String, source: GcsImportSource): F[StartedLro[F, ImportResult]] = unused
    def listFiles(corpusName: String): F[List[RagFileInfo]]                                      = unused
    def getFile(name: String): F[RagFileInfo]                                                    = unused
    def deleteFile(name: String): F[StartedLro[F, Unit]]                                         = unused
    def getLro(handle: LroHandle): F[LroStatus]                                                  = unused

  private def unused[A]: F[A] = UnsupportedOperationException("RagEngine.similarity only uses retrieveContexts").raiseError

  private def run[A](fa: F[A]): (Chain[RetrieveCall], Either[Throwable, A]) = fa.value.run
