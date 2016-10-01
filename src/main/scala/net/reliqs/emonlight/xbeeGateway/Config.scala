package net.reliqs.emonlight.xbeeGateway

import java.io.File
import java.time.Instant

import scala.language.postfixOps
import scala.collection.mutable.Map

import com.digi.xbee.api.io.IOLine
import com.digi.xbee.api.io.IOMode
import com.fasterxml.jackson.annotation.JsonIdentityInfo
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.ObjectIdGenerators
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.`type`.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.JsonScalaEnumeration

import akka.actor.ActorRef
import akka.event.EventBus
import akka.event.LookupClassification
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.DefaultJsonProtocol
import akka.agent.Agent
import com.fasterxml.jackson.annotation.JsonBackReference
import com.fasterxml.jackson.annotation.JsonManagedReference
import scala.collection.mutable.ArrayBuffer
import com.digi.xbee.api.models.XBee64BitAddress

object Protocol extends Enumeration {
  type Protocol = Value
  val Emonlight, EmonCms = Value
}
class ProtocolType extends TypeReference[Protocol.type]

object LineAccess extends Enumeration {
  type LineAccess = Value
  val Sampled, Monitored, LocalOnly = Value
}
class LineAccessType extends TypeReference[LineAccess.type]

object OpMode extends Enumeration {
  type OpMode = Value
  val Coordinator, Router, EndDevice = Value
}
class OpModeType extends TypeReference[OpMode.type]

object ProbeType extends Enumeration {
  type ProbeType = Value
  val Pulse, Sample, DHT22_T, DHT22_H = Value
}
class ProbeTypeType extends TypeReference[ProbeType.type]

case class Data(t: Long, v: Double)

case class QData(probe: Probe, data: Data) {
  def convertData(): (Long, Long, Double) = {
    ((data.t / 1000L).toLong, ((data.t % 1000L) * 1000000L).toLong, data.v)
  }
}

case class NodeData(k: String, id: Int, d: List[(Long, Long, Double)])
case class ServerData(nodes: List[NodeData])

trait ServerJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val nodeFormat = jsonFormat3(NodeData)
  implicit val serverFormat = jsonFormat1(ServerData)
}

//TODO remove id and force name uniqueness
@JsonIdentityInfo(generator = classOf[ObjectIdGenerators.IntSequenceGenerator], property = "id")
case class Probe(
    name: String,
    @JsonScalaEnumeration(classOf[ProbeTypeType]) probeType: ProbeType.ProbeType,
    pulsesPerKilowattHour: Int = 0) {

  def validate(): String = {
    //    if (serverMaps == Nil) log.warning
    val str =
      (if (name.isEmpty) " - name can't be empty\n" else "") +
        (if (probeType == ProbeType.Pulse && pulsesPerKilowattHour <= 0) " - pulsePerKilowattHour should be > 0 when probe type is pulse\n" else "")
    //        (if (cfg().node(this)) " - node can't be null\n" else "")
    if (str.nonEmpty) s"Pulse ${name} should:\n" + str else ""
  }

  def existsIn(s: Server) = Config.cfg().serverMaps(this).exists { sm => sm.server == s }

  override def toString = s"P[$name]"
}

object LineOrderingBySampleTime extends Ordering[Line] {
  def compare(x: Line, y: Line): Int = x.sampleTime compare y.sampleTime
}

class Line(
    val line: IOLine,
    @JsonScalaEnumeration(classOf[LineAccessType]) val access: LineAccess.LineAccess = LineAccess.LocalOnly,
    val sampleTime: Int,
    val mode: IOMode,
    p: List[Probe]) {

  private var _probes: ArrayBuffer[Probe] = if (p == null) ArrayBuffer() else p.to
  def probes: List[Probe] = _probes.toList
  def probes_=(l: List[Probe]) { _probes = l.to[ArrayBuffer] }

  def addProbe(p: Probe) = _probes += p
  def removeProbe(p: Probe) = _probes -= p

  def validate(): String = {
    val dht22 = probes.length > 0 && (probes(0).probeType == ProbeType.DHT22_H || probes(0).probeType == ProbeType.DHT22_T)
    val str =
      (if (mode == null) " - mode can't be null\n" else "") +
        (if (line == null) " - line can't be null\n" else "") +
        (if (access == null) " - access can't be null\n" else "") +
        (if (access == LineAccess.Monitored && mode != IOMode.DIGITAL_IN) " - when access is monitored mode should be digital in\n" else "") +
        (if (sampleTime <= 0) " - sampleTime should be > 0\n" else "") +
        (if (probes.isEmpty) " - no probes defined for line\n" else "") +
        (if (probes(0).probeType == ProbeType.Pulse && probes.length != 1) " - a single probe can be present when probe type is pulse\n" else "") +
        (if (probes(0).probeType == ProbeType.Pulse && mode != IOMode.DIGITAL_IN) " - mode should be Digital In when probe type is pulse\n" else "") +
        (if (probes(0).probeType == ProbeType.Pulse && access != LineAccess.LocalOnly) " - access should be local only when probe type is pulse\n" else "") +
        (if (dht22 && access != LineAccess.LocalOnly) " - access should be Local Only when probe type is DHT22\n" else "") +
        (if (dht22 && probes.length != 2) " - 2 probes must be present when probe type is DHT22\n" else "")
    // FIXME handle the case of first probe not DHT22
    (if (dht22 && probes.length == 2 &&
      (probes(0).probeType == ProbeType.DHT22_T && probes(1).probeType != ProbeType.DHT22_H ||
        probes(0).probeType == ProbeType.DHT22_H && probes(1).probeType != ProbeType.DHT22_T)) " - probes types should be DHT22_T and DHT22_H\n" else "") +
      (if (probes.map(_.name).toSet.size == probes.length) " - probes should have unique names\n" else "") +
      probes.map(_.validate).fold("")((acc, v) => acc + v)
    if (str.nonEmpty) s"Line $this should:\n" + str else ""
  }
}

//object Node {
//  val Empty: Node = new Node("", OpMode.Router, List.empty)
//}

case class Node(
    name: String,
    address: String = "",
    @JsonScalaEnumeration(classOf[OpModeType]) opMode: OpMode.OpMode = OpMode.Router,
    lines: List[Line]) {

  def sampleTime(): Int = lines.minBy(_.sampleTime).sampleTime

  def validate(): String = {
    val str = (if (name.isEmpty) " - name can't be empty\n" else "") +
      //      (if (opMode == OpMode.EndDevice && sleepTime == 0) " - endDevice must have sleepTime > 0\n" else "") +
      (if (lines.isEmpty) " - no lines are defined\n" else "") +
      (if (lines.map(_.line).toSet.size != lines.length) " - line attribute should be unique\n" else "") +
      lines.map(_.validate()).fold("")(_ + _)
    if (str.nonEmpty) s"Node ${name} should:\n" + str else ""
  }

  override def toString = s"N[$name]"
}

class ServerMap(
    val probe: Probe,
    val nodeId: Int,
    val apiKey: String) {

  @JsonIgnore def server: Server = Config.cfg().servers find (s => (s.maps contains (this))) get

  def validate(): String = {
    val str =
      (if (nodeId == 0) " - nodeId should be > 0\n" else "") +
        (if (apiKey.isEmpty) " - apiKey should not be empty\n" else "") +
        //        (if (server == null) " - server should not be null\n" else "") +
        (if (probe == null) " - probe should not be null\n" else "")
    if (str.nonEmpty) s"ServerMap $this should:\n" + str else ""
  }
}

case class Server(
    name: String,
    url: String,
    @JsonScalaEnumeration(classOf[ProtocolType]) protocol: Protocol.Protocol = Protocol.Emonlight,
    sendRate: Int = 10000,
    sendTimeout: Int = 5000,
    maps: List[ServerMap]) {

  //  @JsonIgnore var nextSend: Instant = Instant.EPOCH

  def validate(): String = {
    val str =
      (if (name.isEmpty) " - name should not be empty\n" else "") +
        (if (url.isEmpty) " - url should not be empty\n" else "") +
        (if (sendRate <= 0) " - sendRate should be > 0\n" else "") +
        (if (sendTimeout <= 0) " - sendTimeout should be > 0\n" else "") +
        (if (maps.isEmpty) " - maps should not be empty\n" else "") +
        (if (maps.map(_.nodeId).toSet.size == maps.length) " - nodeId inside ServerMap should be unique\n" else "") +
        maps.map(_.validate()).fold("")(_ + _)
    if (str.nonEmpty) s"Server ${name} should:\n" + str else ""
  }

  override def toString = s"S[$name]"

}

object Config {
  import scala.concurrent.ExecutionContext.Implicits.global

  var cfg: Agent[Config] = null
  def setCfg(c: Config) = cfg = Agent(c)
}

case class Config(
    serialPort: String,
    baudRate: Int = 115200,
    discoveryTimeout: Int = 10000,
    discoverySchedule: Int = 0,
    receiveTimeout: Int = 5000,
    //    sendRate: Int = 10000,
    savePath: String = ".",
    //    resetCmd: String = "", 
    nodes: List[Node],
    servers: List[Server]) {

  @JsonIgnore val dBus = new DispatchBus

  //  def init: Config = {
  //    servers foreach (s => s.maps foreach (sm => sm.server = s))
  //    nodes foreach (n => n.lines foreach (l => l.probes foreach (p => {
  //      p.node = n
  //      p.serverMaps = (servers flatMap (s => s.maps filter (_.probe == p)))
  //    })))
  //    //    if (validate().nonEmpty) throw new RuntimeException("validation failed")
  //    this
  //  }

  //  def createProbeMap: Map[(Node, IOLine, ProbeType.ProbeType), Probe] = {
  //    nodes flatMap (node => node.lines flatMap (line => (line.probes map (p => (node, line.line, p.probeType) -> p)))) toMap
  //  }

  def probes = nodes flatMap (node => node.lines flatMap (line => line.probes))

  // From probe
  def serverMaps(p: Probe): List[ServerMap] = servers flatMap (s => (s.maps find (sm => sm.probe == p)))
  def node(p: Probe): Node = nodes find (n => (n.lines exists (l => (l.probes contains (p))))) get

  def findNode(name: String, addr: String) = nodes.find(n => n.name == name || (addr.nonEmpty && n.address == addr))
  def timeout = (nodes maxBy (_.sampleTime())).sampleTime()
  
  def validate(): String = {
    val str = (if (serialPort.isEmpty) " - serial port should not be empy\n" else "") +
      (if (baudRate <= 0) " - baudRate should be > 0\n" else "") +
      (if (discoveryTimeout <= 0) " - discoveryTimeout should be > 0\n" else "") +
      (if (receiveTimeout <= 0) " - receiveTimeout should be > 0\n" else "") +
      //      (if (sendRate <= 0) " - sendRate should be > 0\n" else "") +
      (if (servers.map(_.name).toSet.size != servers.length) " - server name should be unique\n" else "") +
      nodes.map(_.validate()).fold("")(_ + _)
    servers.map(_.validate()).fold("")(_ + _)
    if (str.nonEmpty) s"Config should:\n" + str else ""
  }

}

class DispatchBus extends EventBus with LookupClassification {
  type Event = QData
  type Classifier = Probe
  type Subscriber = ActorRef
  // is used for extracting the classifier from the incoming events
  override protected def classify(event: Event): Classifier = event.probe
  // will be invoked for each event for all subscribers which registered themselves
  // for the event’s classifier
  override protected def publish(event: Event, subscriber: Subscriber): Unit = {
    subscriber ! event
  }
  // must define a full order over the subscribers, expressed as expected from
  // `java.lang.Comparable.compare`
  override protected def compareSubscribers(a: Subscriber, b: Subscriber): Int =
    a.compareTo(b)
  // determines the initial size of the index data structure
  // used internally (i.e. the expected number of different classifiers)
  override protected def mapSize: Int = 16
}

object Factory {
  lazy val stdMapper = {
    val m = new ObjectMapper()
    m.registerModule(DefaultScalaModule)
  }

  lazy val mapper = buildMapper

  private def buildMapper: ObjectMapper = {
    val m = new ObjectMapper()
    m.registerModule(DefaultScalaModule)
    m.enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES);
    m.disable(JsonGenerator.Feature.QUOTE_FIELD_NAMES);
    m.enable(SerializationFeature.INDENT_OUTPUT);
  }

  def read(filePath: String): Config = {
    val src = new File(filePath)
    val c = mapper.readValue(src, classOf[Config])
    Config.setCfg(c)
    c
  }

  //  def read[T](filePath: String, clazz: Class[T]): T = {
  //    val src = new File(filePath)
  //    mapper.readValue[T](src, clazz)
  //  }

  def save[T](res: T, filePath: String) {
    val f = new File(filePath)
    mapper.writeValue(f, res)
  }

}
