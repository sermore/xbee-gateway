package net.reliqs.emonlight.xbeeGateway.xbee

import java.time.Instant

import com.digi.xbee.api.RemoteXBeeDevice
import com.digi.xbee.api.XBeeDevice
import com.digi.xbee.api.io.IOSample
import com.digi.xbee.api.listeners.IDataReceiveListener
import com.digi.xbee.api.listeners.IDiscoveryListener
import com.digi.xbee.api.listeners.IIOSampleReceiveListener
import com.digi.xbee.api.models.XBeeMessage
import com.digi.xbee.api.utils.HexUtils
import com.typesafe.scalalogging.LazyLogging

import net.reliqs.emonlight.xbeeGateway.Config
import com.digi.xbee.api.exceptions.InvalidOperatingModeException
import com.digi.xbee.api.models.DiscoveryOptions
import java.util.Collection
import java.util.Collections
import java.util.EnumSet
import net.reliqs.emonlight.xbeeGateway.Node
import com.digi.xbee.api.models.XBee64BitAddress
import com.digi.xbee.api.exceptions.XBeeException
import net.reliqs.emonlight.xbeeGateway.OpMode

class XbeeDispatcher(val cfg: Config, val dsp: Dispatcher)
    extends IDiscoveryListener with IIOSampleReceiveListener with IDataReceiveListener with LazyLogging {

  assert(cfg != null)
  assert(dsp != null)
  val localDevice = init()

  def init(): XBeeDevice = {
    logger.debug("init")
    val d = new XBeeDevice(cfg.serialPort, cfg.baudRate)
    d.setReceiveTimeout(cfg.receiveTimeout)
    d.open()
    // FIXME check for coordinator
    // extended sleep handling
    d.enableApplyConfigurationChanges(true)
    XbeeNode.sleepConfig(d, cfg.timeout, logger)
    d.writeChanges()
    d.addIOSampleListener(this)
    d.addDataListener(this)
    logger.debug(s"init completed! $d")
    d
  }

  def startDiscovery(): Boolean = {
    //    import scala.collection.JavaConversions._
    //    logger.debug("start discovery")
    val network = localDevice.getNetwork()
    if (network.isDiscoveryRunning()) {
      logger.warn("request to start discovery when it is already running")
      false
    } else {
      val ln = (cfg.nodes.filter(n => n.opMode == OpMode.EndDevice && n.sampleTime <= 28000))
      val shortTimeout = (if (ln.isEmpty) 0 else ln.maxBy(_.sampleTime()).sampleTime())
      val t = Math.min(25000, Math.max(cfg.discoveryTimeout, (shortTimeout * 2).toLong))
      logger.info(s"start discovery timeout=$t")
      network.addDiscoveryListener(this)
      network.setDiscoveryTimeout(t)
      //      network.setDiscoveryOptions(EnumSet.noneOf(classOf[DiscoveryOptions]))
      network.startDiscoveryProcess()
      logger.info(s"discovery started")
      true
    }
  }

  def deviceDiscovered(d: RemoteXBeeDevice): Unit = {
    logger.debug(s"discovered $d")
    //    dsp.queueEvent(DeviceDiscovered(d))
  }

  def discoveryError(msg: String): Unit = {
    //    logger.debug(s"discover error $msg")
    dsp.queueEvent(DiscoveryError(msg))
  }

  def discoveryFinished(msg: String): Unit = {
    import scala.collection.JavaConversions._
    logger.debug(s"discovery finished $msg")
    dsp.queueEvent(DiscoveryFinished(msg, localDevice.getNetwork.getDevices))
  }

  def addToDiscovery(device: RemoteXBeeDevice) {
    val net = localDevice.getNetwork
    if (net.getDevice(device.get64BitAddress) == null) {
      logger.debug(s"device ${device} added to list of discovered devices")
      net.addRemoteDevice(device)
    }
  }

  def sendDataAsync(device: RemoteXBeeDevice, b: Array[Byte]) = {
    logger.debug("send " + HexUtils.byteArrayToHexString(b) + " to device " + device.toString);
    localDevice.sendDataAsync(device, b);
  }

  def dataReceived(msg: XBeeMessage): Unit = {
    dsp.queueEvent(DataReceived(msg, Instant.now))
  }

  def ioSampleReceived(d: RemoteXBeeDevice, s: IOSample): Unit = {
    dsp.queueEvent(IoSampleReceived(d, s, Instant.now))
  }

  def awakeSleepingNode(address: String): Option[RemoteXBeeDevice] = {
    logger.info(s"try to awake node with address $address")
    assert(address.nonEmpty)
    val r = new RemoteXBeeDevice(localDevice, new XBee64BitAddress(address))
    // Send a Commissioning PushButton command to force the xbee to stay awake for 30 seconds
    try {
      r.reset()
      Thread.sleep(1000)
      r.setParameter("CB", Array[Byte](1))
      logger.info(s"Commissioning Pushbutton sent successfully")
      Some(r)
    } catch {
      case e: XBeeException => logger.info(s"error while trying to awake $address: ${e.getMessage}", e)
    }
    None
  }

}
