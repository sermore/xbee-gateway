package net.reliqs.emonlight.xbeeGateway.xbee

import java.time.Instant
import java.util.concurrent.DelayQueue
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import com.digi.xbee.api.RemoteXBeeDevice
import com.digi.xbee.api.io.IOSample
import com.digi.xbee.api.models.XBeeMessage
import com.typesafe.scalalogging.LazyLogging

import net.reliqs.emonlight.xbeeGateway.Config
import net.reliqs.emonlight.xbeeGateway.Node
import net.reliqs.emonlight.xbeeGateway.OpMode
import net.reliqs.emonlight.xbeeGateway.QData
import net.reliqs.emonlight.xbeeGateway.xbee.Event._
import com.digi.xbee.api.exceptions.XBeeException
import scala.collection.mutable.PriorityQueue
import java.util.concurrent.BlockingQueue

object Processor {

  def toStr(msg: XBeeMessage): String = s"Msg(${msg.getDevice}, ${msg.getData()(0).toChar}, ${msg.getData.length})"
}

trait XbeeNodeFactory {
  def create(address: String, d: RemoteXBeeDevice, n: Node, dsp: Dispatcher): XbeeNode
}

trait NodeFactoring {

  class SimpleFactory extends XbeeNodeFactory {
    override def create(address: String, device: RemoteXBeeDevice, node: Node, dsp: Dispatcher) = {
      new XbeeNode(address, device, node, dsp)
    }
  }

  val factory = new SimpleFactory()
}

trait EventHandling {
  def eventHandler: EventHandler
}

//trait Dispatching { this : EventHandling =>
//  class MyDispatcher(val eventHandler: EventHandler) extends Dispatcher with EventHandling with LazyLogging
//  
//  val dsp: Dispatcher = new MyDispatcher(eventHandler)
//}

trait NodeEventHandling {
  def handle: MessageHandler
}

trait Dispatcher { this: EventHandling with LazyLogging =>

  val queue = new DelayQueue[Event]()

  def queueEvent(event: Event) = queue.offer(event)

  def handle(e: Event): Seq[QData] = {
    //    logger.debug(s"event $e")
    if (e.isInstanceOf[ProcessorEvent]) {
      eventHandler.orElse(PartialFunction.empty)(e)
      Seq.empty
    } else {
      val ne = e.asInstanceOf[NodeEvent]
      val q = ne.node.handle(ne)
      logger.debug(s"data Produced $q")
      q
    }
  }

  def removeEventsForNode(n: NodeEventHandling) {
    import scala.collection.JavaConversions._
    var cnt = 0
    while (queue.find(e => (e.isInstanceOf[NodeEvent] && e.asInstanceOf[NodeEvent].node == n)) match {
      case Some(e) =>
        cnt += 1; queue.remove(e)
      case None => false
    }) {}
    logger.debug(s"removed $cnt events related to $n")
  }

  def process(): Seq[QData] = {
    //    logger.debug("poll")
    Option(queue.poll(1, TimeUnit.SECONDS)) match {
      case Some(e) => handle(e)
      case None    => Seq.empty
    }
  }

}

trait NodeManager { this: NodeFactoring with LazyLogging =>

  // FIXME make private
  val activeNodes = collection.mutable.Map[String, XbeeNode]()

  def findActiveNode(addr: String, name: String = ""): Option[XbeeNode] =
    (if (addr != null && addr.nonEmpty) activeNodes.get(addr)
    else activeNodes.values find (n => name != null && name.nonEmpty && n.node.name == name))

  def createActiveNode(addr: String, d: RemoteXBeeDevice, n: Node, dsp: Dispatcher) = {
    logger.info(s"xbeeNode creation for device $d and node $n address $addr")
    try {
      val xn = factory.create(addr, d, n, dsp)
      activeNodes.put(addr, xn)
    } catch {
      case e: XBeeException => logger.warn(s"xbeeNode creation aborted due to error", e); None
    }
  }

  def removeActiveNode(address: String): Option[XbeeNode] = activeNodes.remove(address)

}

trait Processor { this: NodeManager with Dispatcher with EventHandling with LazyLogging =>
  import Processor._

  object State extends Enumeration {
    val Init, Ready, Discovering = Value
  }

  val cfg: Config
  val DiscoveryScheduleTime = 10

  var state: State.Value = State.Init
  val xbeeDispatcher = new XbeeDispatcher(cfg, this)

  //  init()

  /**
   * First a discovery is launched to find active devices.
   * If end device nodes are missing, try to awake sleeping nodes.
   * If router nodes are missing schedule a discovery operation until found.
   */
  def init() {
    assert(queue != null)
    queueEvent(StartScheduledDiscovering())
    state = State.Ready
  }

  override def eventHandler: EventHandler = {
    case StartScheduledDiscovering(_)           => startDiscovery()
    case DeviceDiscovered(d)                    => deviceDiscovered(d)
    case DiscoveryError(msg)                    => logger.debug(s"discovery error $msg")
    case DiscoveryFinished(msg)                 => discoveryFinished(msg)
    case DataReceived(msg, time)                => dataReceived(msg, time)
    case IoSampleReceived(device, sample, time) => ioSampleReceivedHandler(device, sample, time)
    case SendDataAsync(device, b)               => xbeeDispatcher.sendDataAsync(device, b)
    case RemoveActiveNode(address)              => removeActiveNode(address)
    case AwakeSleepingNode(addr) =>
      xbeeDispatcher.awakeSleepingNode(addr) match {
        case Some(r) => queueEvent(DeviceDiscovered(r))
        case None    => logger.debug(s"awakening failed for $addr")
      }
  }

  def verifySynch() {
    activeNodes.values filter (_.node.opMode != OpMode.EndDevice) foreach (n =>
      queueEvent(VerifySynchAfter(n, n.SynchShortTimeOutSec seconds)))
  }

  /**
   * Blocking until timeout is reached.
   * @param time
   * @param retry
   * @return
   */
  def startDiscovery() = {
    if (state != State.Discovering) {
      state = State.Discovering
      // find max sampleTime
      xbeeDispatcher.startDiscovery()
    }
  }

  /**
   * No overlapped deviceDiscovered calls should happen because it should be called only from single-threaded dispatcher.
   * @param d
   */
  def deviceDiscovered(d: RemoteXBeeDevice) {
    val addr = d.get64BitAddress.toString
    findActiveNode(addr) match {
      case None =>
        cfg.findNode(d.getNodeID, addr) match {
          case None => logger.warn(s"device discovered $d ignored as it is not listed in configuration")
          case Some(n) =>
            logger.info(s"device discovered $d")
            createActiveNode(addr, d, n, this)
        }
      case Some(xn) =>
        logger.debug(s"device discovered $d already mapped to $xn")
    }
  }

  def discoveryFinished(msg: String) {
    state = State.Ready
    //    assert(!xbeeDispatcher.localDevice.getNetwork.isDiscoveryRunning())
    // check if nodes are still missing: 
    val mn = notActiveNodes
    if (mn.nonEmpty) {
      val delay = (((mn.minBy(_.sampleTime)).sampleTime * DiscoveryScheduleTime) millis)
      logger.info(s"discovery finished, it will be performed again after $delay because of missing nodes: $mn")
      // if we know the address for the missing end devices, try to awake them
      //      mn.filter(n => n.address != null && n.address.nonEmpty).foreach(n => queueEvent(AwakeSleepingNode(n.address)))
      //      // for routers, schedule a discovery to detect them in the future
      //      if (mn.nonEmpty && mn.exists(_.opMode == OpMode.Router)) {
      //      logger.info(s"router nodes missing, discovery scheduled in $cfg.discoverySchedule seconds")
      //        // if router nodes are still missing, enable a scheduled discovery 
      //        queueEvent(StartScheduledDiscovering(Duration(cfg.discoverySchedule, TimeUnit.SECONDS)))
      //      }
      //      queueEvent(StartDiscovering)
      // FIXME how to discover?
      //      queueEvent(StartScheduledDiscovering(delay))
    } else
      logger.info(s"discovery finished, all nodes have been identified.")
  }

  def dataReceived(msg: XBeeMessage, time: Instant): Unit = {
    //    log.debug(s"data length ${msg.getData.length} from ${msg.getDevice}")
    if (!handleMessage(msg.getDevice, (xn: XbeeNode) => NodeDataReceived(xn, msg, time)))
      logger.warn(s"node for ${msg.getDevice} not found, message discarded: ${toStr(msg)}")
  }

  def ioSampleReceivedHandler(device: RemoteXBeeDevice, sample: IOSample, time: Instant) = {
    logger.debug(s"received ioSample $sample from $device")
    if (!handleMessage(device, (xn: XbeeNode) => NodeIoSampleReceived(xn, sample, time)))
      logger.warn(s"node for ${device} not found, message discarded: ${sample}")
  }

  def handleMessage(device: RemoteXBeeDevice, eventGen: (XbeeNode) => NodeEvent): Boolean = {
    // TODO handle node disconnection
    val addr = device.get64BitAddress.toString
    findActiveNode(addr, device.getNodeID) match {
      case Some(xn) =>
        queueEvent(eventGen(xn))
        true
      case None =>
        // search for a node with same address, consider it as just discovered if we are not inside a discovering state
        if (state != State.Discovering) {
          findNode(addr, device.getNodeID) match {
            case Some(n) =>
              logger.debug(s"DeviceDiscovered event for device ${device} queued after receiving a message")
              queueEvent(DeviceDiscovered(device))
              // FIXME how queue msg to be handled?
              true
            case None =>
              // node not found, or the address is not present in the node or the device hasn't yet a name, in the latter case a discovery is queued
              // FIXME double check for discovery frequency not being too high
              if (state != State.Discovering && device.getNodeID.isEmpty) queueEvent(StartScheduledDiscovering())
              false
          }
        } else false
    }
  }

  def findNode(addr: String, name: String = "") = cfg.nodes find (n => n.address == addr || n.name == name)
  def notActiveNodes = cfg.nodes filter (n => findActiveNode(n.address, n.name).isEmpty)

  def removeActiveNodeExt(address: String) = {
    logger.info(s"remove active node ${activeNodes(address)}")
    removeActiveNode(address) match {
      //remove remaining queued events to this node
      case Some(n) => removeEventsForNode(n)
      case None    => logger.warn(s"failed to remove node with address $address")
    }
  }

}

class FullProcessor(override val cfg: Config) extends NodeFactoring
  with NodeManager with Dispatcher with Processor with EventHandling with LazyLogging
  