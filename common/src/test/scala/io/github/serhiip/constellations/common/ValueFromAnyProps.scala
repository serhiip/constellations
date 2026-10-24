package io.github.serhiip.constellations.common

import java.lang.{Boolean as JBoolean, Long as JLong, Number as JNumber}
import java.util.{
  ArrayList,
  Arrays,
  Collections,
  Comparator,
  HashMap as JHashMap,
  HashSet as JHashSet,
  Hashtable,
  LinkedHashMap as JLinkedHashMap,
  LinkedHashSet,
  LinkedList,
  Set as JSet,
  TreeMap as JTreeMap,
  TreeSet as JTreeSet,
  Vector as JVector
}
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
    Gen.const((null, Value.NullValue)),
    Gen.alphaNumStr.map(s => (s, Value.StringValue(s))),
    Gen.oneOf(true, false).flatMap { b =>
      Gen.oneOf((b, Value.BoolValue(b)), (JBoolean.valueOf(b), Value.BoolValue(b)))
    },
    genFiniteDouble.map(d => (d, Value.NumberValue(d))),
    genFiniteDouble.map(d => (d.toFloat, Value.NumberValue(d.toFloat.toDouble))),
    Gen.chooseNum(Int.MinValue, Int.MaxValue).flatMap { i =>
      Gen.oneOf((i, Value.NumberValue(i.toDouble)), (Integer.valueOf(i), Value.NumberValue(i.toDouble)))
    },
    Gen.chooseNum(Long.MinValue, Long.MaxValue).flatMap { l =>
      Gen.oneOf((l, Value.NumberValue(l.toDouble)), (JLong.valueOf(l), Value.NumberValue(l.toDouble)))
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
    items
      .foldLeft(List.empty[(Any, Value)]) { (acc, item) =>
        if acc.exists { case (_, value) => valuesEqual(value, item._2) } then acc else item :: acc
      }
      .reverse

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
      case SeqKind.JavaArrayList            => new ArrayList(items.asJava)
      case SeqKind.JavaLinkedList           => new LinkedList(items.asJava)
      case SeqKind.JavaVector               => new JVector(items.asJava)
      case SeqKind.JavaCopyOnWriteArrayList => new CopyOnWriteArrayList(items.asJava)
      case SeqKind.JavaArraysAsList         => Arrays.asList(items*)
      case SeqKind.JavaUnmodifiableList     => Collections.unmodifiableList(new ArrayList(items.asJava))
      case SeqKind.JavaHashSet              => new JHashSet(items.asJava)
      case SeqKind.JavaLinkedHashSet        => new LinkedHashSet(items.asJava)
      case SeqKind.JavaTreeSet              => javaTreeSetOrHashSet(items)

  private def treeSetOrHashSet(items: List[Any]): Any =
    if uniformComparable(items) then ImmTreeSet.from(items)(using treeOrdering)
    else ImmHashSet.from(items)

  private def javaTreeSetOrHashSet(items: List[Any]): JSet[Any] =
    val set: JSet[Any] =
      if uniformComparable(items) then new JTreeSet[Any](treeComparator)
      else new JHashSet[Any]
    set.addAll(items.asJava)
    set

  // Set equality probes a TreeSet with whatever element it is comparing, including null.
  private val treeOrdering: Ordering[Any] = new Ordering[Any]:
    def compare(x: Any, y: Any): Int =
      (x, y) match
        case (null, null) => 0
        case (null, _)    => -1
        case (_, null)    => 1
        case _            =>
          val byClass = x.getClass.getName.compareTo(y.getClass.getName)
          if byClass != 0 then byClass
          else
            x match
              case c: Comparable[?] => c.asInstanceOf[Comparable[Any]].compareTo(y)
              case _                => Integer.compare(System.identityHashCode(x), System.identityHashCode(y))

  private val treeComparator: Comparator[Any] = (x, y) => treeOrdering.compare(x, y)

  private def uniformComparable(items: List[Any]): Boolean =
    items match
      case head :: tail =>
        isTreeElement(head) && tail.forall(item => isTreeElement(item) && item.getClass == head.getClass)
      case Nil          => true

  private def isTreeElement(value: Any): Boolean =
    value match
      case _: String | _: JNumber | _: JBoolean => true
      case _                                    => false

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
      case MapKind.JavaHashMap           => new JHashMap(map.asJava)
      case MapKind.JavaLinkedHashMap     => new JLinkedHashMap(map.asJava)
      case MapKind.JavaTreeMap           => new JTreeMap(map.asJava)
      case MapKind.JavaHashtable         =>
        if hasNulls then new JHashMap(map.asJava) else new Hashtable(map.asJava)
      case MapKind.JavaConcurrentHashMap =>
        if hasNulls then new JHashMap(map.asJava) else new ConcurrentHashMap(map.asJava)
      case MapKind.JavaUnmodifiableMap   => Collections.unmodifiableMap(new JHashMap(map.asJava))

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
