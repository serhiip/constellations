package io.github.serhiip.constellations.common

import java.lang.{Boolean as JBoolean, Number as JNumber}
import java.util as ju
import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList}

import scala.collection.immutable.{
  ArraySeq,
  ListMap,
  Queue,
  HashMap as ImmHashMap,
  HashSet as ImmHashSet,
  TreeMap as ImmTreeMap,
  TreeSet as ImmTreeSet
}
import scala.collection.mutable.{ArrayBuffer, LinkedHashMap as MutLinkedHashMap, ListBuffer, HashMap as MutHashMap, HashSet as MutHashSet}
import scala.jdk.CollectionConverters.*
import scala.util.Try

import munit.ScalaCheckSuite
import org.scalacheck.Prop.{forAll, propBoolean}
import org.scalacheck.Gen

class ValueFromAnyProps extends ScalaCheckSuite:
  override def scalaCheckTestParameters = super.scalaCheckTestParameters
    .withMinSuccessfulTests(1000)
    .withWorkers(Runtime.getRuntime.availableProcessors())

  property("fromAny parses every Scala/Java collection materialization of a JSON-like tree") {
    forAll(genMaterialized) { case (raw, expected) =>
      val actual = Value.fromAny(raw)
      valuesEqual(actual, expected) :| s"fromAny($raw) = $actual, expected $expected"
    }
  }

  private enum SeqKind:
    case ScalaList, ScalaVector, ScalaLazyList, ScalaQueue, ScalaArraySeq, ScalaArrayBuffer, ScalaListBuffer, ScalaIndexedSeq,
      ScalaArray, ScalaSet, ScalaHashSet, ScalaTreeSet, ScalaMutHashSet, JavaArrayList, JavaLinkedList, JavaVector,
      JavaCopyOnWriteArrayList, JavaArraysAsList, JavaUnmodifiableList, JavaHashSet, JavaLinkedHashSet, JavaTreeSet

  private enum MapKind:
    case ScalaMap, ScalaHashMap, ScalaTreeMap, ScalaListMap, ScalaMutHashMap, ScalaMutLinkedHashMap, JavaHashMap,
      JavaLinkedHashMap, JavaTreeMap, JavaHashtable, JavaConcurrentHashMap, JavaUnmodifiableMap

  private val setSeqKinds: Set[SeqKind] = Set(
    SeqKind.ScalaSet,
    SeqKind.ScalaHashSet,
    SeqKind.ScalaTreeSet,
    SeqKind.ScalaMutHashSet,
    SeqKind.JavaHashSet,
    SeqKind.JavaLinkedHashSet,
    SeqKind.JavaTreeSet
  )

  private val genKey: Gen[String] = Gen.alphaNumStr.suchThat(_.nonEmpty).map(_.take(8))

  private val genFiniteDouble: Gen[Double] =
    Gen.double.map(d => if d.isNaN || d.isInfinite then 0.0 else d)

  private val genLeaf: Gen[(Any, Value)] = Gen.oneOf(
    Gen.const((null: Any, Value.NullValue)),
    Gen.alphaNumStr.map(s => (s: Any, Value.StringValue(s))),
    Gen.oneOf(true, false).flatMap { b =>
      Gen.oneOf((b: Any, Value.BoolValue(b)), (JBoolean.valueOf(b): Any, Value.BoolValue(b)))
    },
    genFiniteDouble.map(d => (d: Any, Value.NumberValue(d))),
    genFiniteDouble.map(d => (d.toFloat: Any, Value.NumberValue(d.toFloat.toDouble))),
    Gen.chooseNum(Int.MinValue, Int.MaxValue).flatMap { i =>
      Gen.oneOf((i: Any, Value.NumberValue(i.toDouble)), (Integer.valueOf(i): Any, Value.NumberValue(i.toDouble)))
    },
    Gen.chooseNum(Long.MinValue, Long.MaxValue).flatMap { l =>
      Gen.oneOf((l: Any, Value.NumberValue(l.toDouble)), (java.lang.Long.valueOf(l): Any, Value.NumberValue(l.toDouble)))
    }
  )

  private val genSeqKind: Gen[SeqKind] = Gen.oneOf(SeqKind.values.toSeq)
  private val genMapKind: Gen[MapKind] = Gen.oneOf(MapKind.values.toSeq)

  private val genMaterialized: Gen[(Any, Value)] = Gen.sized(genSized)

  private def genSized(size: Int): Gen[(Any, Value)] =
    if size <= 0 then genLeaf
    else
      Gen.frequency(
        4 -> genLeaf,
        2 -> genArray(size),
        2 -> genObject(size)
      )

  private def genArray(size: Int): Gen[(Any, Value)] =
    for
      items <- Gen.listOf(Gen.resize(size / 2, genMaterialized))
      kind  <- genSeqKind
      unique = if setSeqKinds.contains(kind) then distinctByValue(items) else items
      raw    = materializeSeq(unique.map(_._1), kind)
    yield (raw, Value.ListValue(unique.map(_._2)))

  private def distinctByValue(items: List[(Any, Value)]): List[(Any, Value)] =
    items.foldLeft(List.empty[(Any, Value)]) { (acc, item) =>
      if acc.exists { case (_, value) => valuesEqual(value, item._2) } then acc else acc :+ item
    }

  private def genObject(size: Int): Gen[(Any, Value)] =
    for
      fields <- Gen.listOf(for
                  key   <- genKey
                  value <- Gen.resize(size / 2, genMaterialized)
                yield key -> value)
      kind   <- genMapKind
      unique  = fields.distinctBy(_._1)
      raws    = unique.map { case (k, (raw, _)) => k -> raw }
      vals    = unique.map { case (k, (_, value)) => k -> value }.toMap
      raw     = materializeMap(raws, kind)
    yield (raw, Value.StructValue(Struct(vals)))

  private def materializeSeq(items: List[Any], kind: SeqKind): Any =
    kind match
      case SeqKind.ScalaList                => items
      case SeqKind.ScalaVector              => items.toVector
      case SeqKind.ScalaLazyList            => items.to(LazyList)
      case SeqKind.ScalaQueue               => Queue.from(items)
      case SeqKind.ScalaArraySeq            => ArraySeq.from(items)
      case SeqKind.ScalaArrayBuffer         => ArrayBuffer.from(items)
      case SeqKind.ScalaListBuffer          => ListBuffer.from(items)
      case SeqKind.ScalaIndexedSeq          => items.toIndexedSeq
      case SeqKind.ScalaArray               => items.toArray[Any]
      case SeqKind.ScalaSet                 => items.toSet
      case SeqKind.ScalaHashSet             => ImmHashSet.from(items)
      case SeqKind.ScalaTreeSet             => treeSetOrHashSet(items)
      case SeqKind.ScalaMutHashSet          => MutHashSet.from(items)
      case SeqKind.JavaArrayList            => new ju.ArrayList(items.asJava)
      case SeqKind.JavaLinkedList           => new ju.LinkedList(items.asJava)
      case SeqKind.JavaVector               => new ju.Vector(items.asJava)
      case SeqKind.JavaCopyOnWriteArrayList => new CopyOnWriteArrayList(items.asJava)
      case SeqKind.JavaArraysAsList         => ju.Arrays.asList(items*)
      case SeqKind.JavaUnmodifiableList     => ju.Collections.unmodifiableList(new ju.ArrayList(items.asJava))
      case SeqKind.JavaHashSet              => new ju.HashSet(items.asJava)
      case SeqKind.JavaLinkedHashSet        => new ju.LinkedHashSet(items.asJava)
      case SeqKind.JavaTreeSet              => javaTreeSetOrHashSet(items)

  private def isTreeSetSafe(value: Any): Boolean =
    value match
      case _: String | _: JNumber | _: JBoolean => true
      case _                                    => false

  private given comparableOrdering: Ordering[Any] =
    Ordering.fromLessThan { (a, b) =>
      a.asInstanceOf[Comparable[Any]].compareTo(b) < 0
    }

  private def treeSetOrHashSet(items: List[Any]): Any =
    if items.forall(isTreeSetSafe) then Try(ImmTreeSet.from(items)).getOrElse(ImmHashSet.from(items))
    else ImmHashSet.from(items)

  private def javaTreeSetOrHashSet(items: List[Any]): Any =
    if items.forall(isTreeSetSafe) then Try(new ju.TreeSet(items.asJava)).getOrElse(new ju.HashSet(items.asJava))
    else new ju.HashSet(items.asJava)

  private def materializeMap(fields: List[(String, Any)], kind: MapKind): Any =
    val map      = fields.toMap
    val hasNulls = map.values.exists(_ == null)
    kind match
      case MapKind.ScalaMap              => map
      case MapKind.ScalaHashMap          => ImmHashMap.from(map)
      case MapKind.ScalaTreeMap          => ImmTreeMap.from(map)
      case MapKind.ScalaListMap          => ListMap.from(map)
      case MapKind.ScalaMutHashMap       => MutHashMap.from(map)
      case MapKind.ScalaMutLinkedHashMap => MutLinkedHashMap.from(map)
      case MapKind.JavaHashMap           => new ju.HashMap(map.asJava)
      case MapKind.JavaLinkedHashMap     => new ju.LinkedHashMap(map.asJava)
      case MapKind.JavaTreeMap           => new ju.TreeMap(map.asJava)
      case MapKind.JavaHashtable         =>
        if hasNulls then new ju.HashMap(map.asJava) else new ju.Hashtable(map.asJava)
      case MapKind.JavaConcurrentHashMap =>
        if hasNulls then new ju.HashMap(map.asJava) else new ConcurrentHashMap(map.asJava)
      case MapKind.JavaUnmodifiableMap   => ju.Collections.unmodifiableMap(new ju.HashMap(map.asJava))

  private def valuesEqual(actual: Value, expected: Value): Boolean =
    (actual, expected) match
      case (Value.NullValue, Value.NullValue)           => true
      case (Value.BoolValue(a), Value.BoolValue(b))     => a == b
      case (Value.StringValue(a), Value.StringValue(b)) => a == b
      case (Value.NumberValue(a), Value.NumberValue(b)) => a == b
      case (Value.StructValue(a), Value.StructValue(b)) =>
        a.fields.keySet == b.fields.keySet && a.fields.forall { case (k, v) => valuesEqual(v, b.fields(k)) }
      case (Value.ListValue(a), Value.ListValue(b))     =>
        a.size == b.size && (
          a.zip(b).forall { case (x, y) => valuesEqual(x, y) } ||
            listValuesEqualUnordered(a, b)
        )
      case _                                            => false

  private def listValuesEqualUnordered(actual: List[Value], expected: List[Value]): Boolean =
    actual.forall(a => expected.exists(e => valuesEqual(a, e))) && expected.forall(e => actual.exists(a => valuesEqual(a, e)))
