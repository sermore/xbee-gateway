package net.reliqs.emonlight.xbeeGateway.xbee

import java.time.Instant

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Random

import com.digi.xbee.api.RemoteXBeeDevice
import com.digi.xbee.api.io.IOSample
import com.digi.xbee.api.models.XBeeMessage
import com.typesafe.scalalogging.LazyLogging

import net.reliqs.emonlight.xbeeGateway.Config
import net.reliqs.emonlight.xbeeGateway.OpMode
import net.reliqs.emonlight.xbeeGateway.xbee.Event.EventHandler

object Processor {

  object State extends Enumeration {
    val Init, Ready, Discovering, NodeInitializing = Value
  }

  def toStr(msg: XBeeMessage): String = s"Msg(${msg.getDevice}, ${msg.getData()(0).toChar}, ${msg.getData.length})"
}

trait Processor { this: NodeManager with Dispatcher with EventHandling with LazyLogging =>
  import Processor._

  val cfg: Config
  val DiscoveryScheduleTime = 100000

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
    case DeviceDiscovered(d, delay)             => deviceDiscovered(d)
    case DiscoveryError(msg)                    => logger.debug(s"discovery error $msg")
    case DiscoveryFinished(msg, devices)        => discoveryFinished(msg, devices)
    case DataReceived(msg, time)                => dataReceived(msg, time)
    case IoSampleReceived(device, sample, time) => ioSampleReceivedHandler(device, sample, time)
    case SendDataAsync(device, b)               => xbeeDispatcher.sendDataAsync(device, b)
    case RemoveActiveNode(address, _)           => removeActiveNode(address)
    case SignalStartNodeInit(_) =>
      assert(state == State.Ready); state = State.NodeInitializing
    case SignalEndNodeInit(n) =>
      if (state != State.NodeInitializing) {
        logger.error(s"state $state incorrect while handling SignalEndNodeInit($n)")
      } else {
        state = State.Ready
      }
    case AwakeSleepingNode(addr, delay) => processAwakeSleepingNode(addr)
  }

  def verifySynch() {
    activeNodes.values filter (_.node.opMode != OpMode.EndDevice) foreach (n =>
      queueEvent(VerifySynchAfter(n, n.SynchShortTimeOutSec seconds)))
  }

  /**
   * The digi API call will block until timeout is reached.
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
    if (state == State.Discovering || state == State.NodeInitializing) {
      logger.debug(s"postpone deviceDiscover $d waiting for completion of $state")
      queueEvent(DeviceDiscovered(d, (15 + Random.nextInt(10)) seconds))
    } else {
      val addr = d.get64BitAddress.toString
      findActiveNode(addr) match {
        case None =>
          cfg.findNode(d.getNodeID, addr) match {
            case None => logger.warn(s"device discovered $d ignored as it is not listed in configuration")
            case Some(n) =>
              logger.info(s"active node creation for $d")
              createActiveNode(addr, d, n, this, this)
          }
        case Some(xn) =>
          logger.debug(s"device discovered $d already mapped to $xn")
      }
    }
  }

  def discoveryFinished(msg: String, devices: Seq[RemoteXBeeDevice]) {
    state = State.Ready
    //    assert(!xbeeDispatcher.localDevice.getNetwork.isDiscoveryRunning())
    // check if nodes are still missing:
    var delay = (0 seconds)
    devices filter (d => findActiveNode(d.get64BitAddress.toString, d.getNodeID).isEmpty) foreach (d => {
      delay += (15 seconds)
      queueEvent(DeviceDiscovered(d, delay))
    })
    val mn = notActiveNodes
    if (mn.nonEmpty) {
      val delay = (((mn.minBy(_.sampleTime)).sampleTime * 10 + DiscoveryScheduleTime + Random.nextInt(DiscoveryScheduleTime / 3)) millis)
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
      queueEvent(StartScheduledDiscovering(delay))
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
        //        if (state != State.Discovering) {
        findNode(addr, device.getNodeID) match {
          case Some(n) =>
            if (state == State.Discovering) {
              xbeeDispatcher.addToDiscovery(device)
            } else {
              val e = DeviceDiscovered(device, 500 millis)
              if (!existsEvent(e)) {
                logger.debug(s"DeviceDiscovered event for device ${device} queued after receiving a message outside discovering")
                //              removeEvent(e)
                queueEvent(e)
              }
            }
            // FIXME how queue msg to be handled?
            true
          case None =>
            // node not found, or the address is not present in the node or the device hasn't yet a name, in the latter case a discovery is queued
            // FIXME double check for discovery frequency not being too high
            if (state != State.Discovering && device.getNodeID.isEmpty) {
              queue.remove(StartScheduledDiscovering())
              queueEvent(StartScheduledDiscovering())
            }
            false
        }
      //        } else false
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

  def processAwakeSleepingNode(addr: String) = {
    if (state == State.Ready) {
      xbeeDispatcher.awakeSleepingNode(addr) match {
        case Some(r) => queueEvent(DeviceDiscovered(r, (1 + Random.nextInt(4)) seconds))
        case None    => logger.debug(s"awakening failed for $addr")
      }
    } else {
      val delay = 10 + Random.nextInt(5)
      logger.debug(s"postponed AwakeSleepingNode $addr due to state not ready, delayed of $delay seconds")
      queueEvent(AwakeSleepingNode(addr, delay seconds))
    }
  }

}

class FullProcessor(override val cfg: Config) extends NodeFactoring
  with NodeManager with Dispatcher with Processor with EventHandling with LazyLogging
  