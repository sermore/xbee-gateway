package net.reliqs.emonlight.xbeeGateway

import scala.concurrent.duration._
import scala.language.postfixOps

import org.scalatest.WordSpec
import com.digi.xbee.api.io.IOMode
import com.digi.xbee.api.io.IOLine
import net.reliqs.emonlight.xbeeGateway.xbee.StartScheduledDiscovering
import net.reliqs.emonlight.xbeeGateway.xbee.DiscoveryError

class ConfigSpec extends WordSpec {

  "A Config" should {    
    "handle json serialization/deserialization for line and node classes" in {
      val m = Factory.confMapper

      val lp1 = new Probe("P1", ProbeType.DHT22_T, 0) :: new Probe("P2", ProbeType.DHT22_H, 0) :: Nil
      val lp2 = new Probe("P3", ProbeType.Pulse, 1200) :: Nil
      val ll =
        new Line(IOLine.DIO0_AD0, LineAccess.LocalOnly, 1000, IOMode.DIGITAL_IN, lp1) ::
          new Line(IOLine.DIO10_PWM0, LineAccess.LocalOnly, 1500, IOMode.DIGITAL_IN, lp2) ::
          Nil
      val n: Node = new Node("n1", "addr1", OpMode.EndDevice, ll)
      //      assert(n.validate())
      //            println(m.writeValueAsString(n))

      assert(m.writeValueAsString(n) == """{
  name : "n1",
  address : "addr1",
  opMode : "EndDevice",
  lines : [ {
    line : "DIO0_AD0",
    access : "LocalOnly",
    sampleTime : 1000,
    mode : "DIGITAL_IN",
    probes : [ {
      id : 1,
      name : "P1",
      probeType : "DHT22_T",
      pulsesPerKilowattHour : 0
    }, {
      id : 2,
      name : "P2",
      probeType : "DHT22_H",
      pulsesPerKilowattHour : 0
    } ]
  }, {
    line : "DIO10_PWM0",
    access : "LocalOnly",
    sampleTime : 1500,
    mode : "DIGITAL_IN",
    probes : [ {
      id : 3,
      name : "P3",
      probeType : "Pulse",
      pulsesPerKilowattHour : 1200
    } ]
  } ]
}""")
    }
    "handle json serialization/deserialization for Config class" in {
      val m = Factory.confMapper

      val lp1 = new Probe("P1", ProbeType.DHT22_T) :: new Probe("P2", ProbeType.DHT22_H) :: Nil
      val lp2 = new Probe("P3", ProbeType.Pulse, 1200) :: Nil
      val ll =
        new Line(IOLine.DIO0_AD0, LineAccess.LocalOnly, 1000, IOMode.DIGITAL_IN, lp1) ::
          new Line(IOLine.DIO10_PWM0, LineAccess.LocalOnly, 1500, IOMode.DIGITAL_IN, lp2) ::
          Nil
      val n: Node = new Node("n1", "addr1", OpMode.Router, ll)
      //      assert(n.validate())
      val ln: List[Node] = n :: Nil
      val sm1 = new ServerMap(n.lines(0).probes(0), 2, "api-key-1") ::
        new ServerMap(n.lines(0).probes(1), 3, "api-key-2") ::
        Nil
      val s1: Server = new Server(name = "s1", url = "url1", protocol = Protocol.Emonlight, 
          sendRate = 11000, sendTimeout = 4000, maps = sm1)
      val sm2 = new ServerMap(n.lines(0).probes(0), 2, "api-key-1") ::
        new ServerMap(n.lines(0).probes(1), 3, "api-key-2") ::
        Nil
      val s2: Server = new Server(name = "s2", url = "url2", protocol = Protocol.Emonlight, 
          sendRate = 11000, sendTimeout = 4000, maps = sm1)
      //      assert(s.validate())
      val ls: List[Server] = s1 :: Nil

      val c: Config = Config(serialPort = "/dev/ttyUSB0", baudRate = 115200, discoveryTimeout = 1000, 
          receiveTimeout = 2000, nodes = ln, servers = ls)
      Config.setCfg(c)
//      c.init
      //      cfg.probes foreach(p => println(s"n=${p.node},s=${p.serverMaps}"))
//            assert(cfg.probes forall (p => p.node != null && p.serverMaps.nonEmpty))
      assert(c.probes forall(p => c.node(p) != null))
      assert(c.servers.length == 1)
      assert(c.servers(0).maps.length == 2)
      assert(c.servers forall (s => s.maps forall (sm => sm.server == s)))      
//      assert(c.servers forall (s => s.maps forall (sm => sm.probe.serverMaps.contains(sm))))
      assert(c.validate().isEmpty())
      //                  println(m.writeValueAsString(cfg))
      assert(m.writeValueAsString(c) == """{
  serialPort : "/dev/ttyUSB0",
  baudRate : 115200,
  discoveryTimeout : 1000,
  discoverySchedule : 0,
  receiveTimeout : 2000,
  savePath : ".",
  applyConfig : true,
  nodes : [ {
    name : "n1",
    address : "addr1",
    opMode : "Router",
    lines : [ {
      line : "DIO0_AD0",
      access : "LocalOnly",
      sampleTime : 1000,
      mode : "DIGITAL_IN",
      probes : [ {
        id : 1,
        name : "P1",
        probeType : "DHT22_T",
        pulsesPerKilowattHour : 0
      }, {
        id : 2,
        name : "P2",
        probeType : "DHT22_H",
        pulsesPerKilowattHour : 0
      } ]
    }, {
      line : "DIO10_PWM0",
      access : "LocalOnly",
      sampleTime : 1500,
      mode : "DIGITAL_IN",
      probes : [ {
        id : 3,
        name : "P3",
        probeType : "Pulse",
        pulsesPerKilowattHour : 1200
      } ]
    } ]
  } ],
  servers : [ {
    name : "s1",
    url : "url1",
    protocol : "Emonlight",
    sendRate : 11000,
    sendTimeout : 4000,
    maps : [ {
      probe : 1,
      nodeId : 2,
      apiKey : "api-key-1"
    }, {
      probe : 2,
      nodeId : 3,
      apiKey : "api-key-2"
    } ]
  } ]
}""")
    }
    "be able to read config from file" in {
      val c: Config = Factory.read("src/test/resources/simple.conf")
      assert(c.validate.isEmpty())
      assert(c.baudRate == 115200)
      assert(c.nodes.length == 2)
      assert(c.nodes(0).lines(0).mode == IOMode.DIGITAL_IN)
      assert(c.servers.length == 2)
      assert(c.servers(1).sendRate == 10000)
      assert(c.servers(1).sendTimeout == 8000)
      assert(c.servers forall (s => s.maps forall (sm => {println(s"sm=$sm, s=$s"); sm.server eq s })))
    }
    "produce same content after a read/write process" in {
      val s1 = """{
  serialPort : "/dev/ttyUSB0",
  baudRate : 115200,
  discoveryTimeout : 1000,
  discoverySchedule : 0,
  receiveTimeout : 2000,
  savePath : ".",
  applyConfig : false,
  nodes : [ {
    name : "n1",
    address : "addr1",
    opMode : "Router",
    lines : [ {
      line : "DIO0_AD0",
      access : "LocalOnly",
      sampleTime : 30000,
      mode : "DIGITAL_IN",
      probes : [ {
        id : 1,
        name : "P1",
        probeType : "DHT22_T",
        pulsesPerKilowattHour : 0
      }, {
        id : 2,
        name : "P2",
        probeType : "DHT22_H",
        pulsesPerKilowattHour : 0
      } ]
    }, {
      line : "DIO10_PWM0",
      access : "LocalOnly",
      sampleTime : 10000,
      mode : "DIGITAL_IN",
      probes : [ {
        id : 3,
        name : "P3",
        probeType : "Pulse",
        pulsesPerKilowattHour : 1200
      } ]
    } ]
  } ],
  servers : [ {
    name : "s1",
    url : "url1",
    protocol : "Emonlight",
    sendRate : 11000,
    sendTimeout : 4000,
    maps : [ {
      probe : 1,
      nodeId : 2,
      apiKey : "api-key-1"
    }, {
      probe : 2,
      nodeId : 3,
      apiKey : "api-key-2"
    } ]
  } ]
}"""
      val c = Factory.confMapper.readValue(s1, classOf[Config])
      val s2 = Factory.confMapper.writeValueAsString(c)
      assert(s1 == s2)
    }
  }
}
