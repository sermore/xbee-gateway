package net.reliqs.emonlight.xbeeGateway.xbee

import java.nio.ByteBuffer
import java.time.Instant
import java.time.temporal.ChronoUnit

import scala.collection.mutable.Queue
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import com.digi.xbee.api.AbstractXBeeDevice
import com.digi.xbee.api.RemoteXBeeDevice
import com.digi.xbee.api.exceptions.XBeeException
import com.digi.xbee.api.io.IOLine
import com.digi.xbee.api.io.IOSample
import com.digi.xbee.api.models.XBeeMessage
import com.digi.xbee.api.utils.ByteUtils
import com.typesafe.scalalogging.LazyLogging
import com.typesafe.scalalogging.Logger

import net.reliqs.emonlight.xbeeGateway.Data
import net.reliqs.emonlight.xbeeGateway.LineAccess
import net.reliqs.emonlight.xbeeGateway.LineAccess.LineAccess
import net.reliqs.emonlight.xbeeGateway.Node
import net.reliqs.emonlight.xbeeGateway.OpMode
import net.reliqs.emonlight.xbeeGateway.Probe
import net.reliqs.emonlight.xbeeGateway.ProbeType
import net.reliqs.emonlight.xbeeGateway.QData
import net.reliqs.emonlight.xbeeGateway.xbee.Event.MessageHandler
import scala.util.Random

object XbeeNode {

  object NodeState extends Enumeration {
    val Created, Initialized, Working, Disconnected, InSynch, OutOfSynch = Value
  }

  def sleepConfig(device: AbstractXBeeDevice, sampleTime: Int, logger: Logger) {
    val so: Byte = if (sampleTime > 28000) 6 else 0
    val sn: Short = ((sampleTime / 28000) + 1).toShort
    val sp: Short = (sampleTime / sn / 10).toShort
    val st: Short = 1000
    logger.info(s"set sleep parameters SN=$sn, SO=$so, SP=$sp, ST=$st for device $device")
    device.setParameter("SN", ByteUtils.shortToByteArray(sn))
    device.setParameter("SO", Array[Byte](so))
    device.setParameter("SP", ByteUtils.shortToByteArray(sp))
    device.setParameter("ST", ByteUtils.shortToByteArray(st))
  }

  def toShort(b: Array[Byte]): Short = ByteUtils.byteArrayToShort(b)

  val InitMaxRetry = 5
  val InitDelay = 3000
  val InitDelayRandomRange = 2000
}

class XbeeNode(val address: String, val device: RemoteXBeeDevice, val node: Node, val dsp: Dispatcher)
    extends XbeeNodeExt with NodeEventHandling with LazyLogging {
  import Processor._
  import XbeeNode._

  // FIXME remove
  val SynchShortTimeOutSec = 10
  val SynchLongTimeOutHours = 12
  val NodeDiscoveredMax = 3

  var state: NodeState.Value = NodeState.Created
  val queue: Queue[QData] = Queue.empty
  var uptimeOffsetInMillis: Long = 0
  var lastTime = collection.mutable.Map[Probe, Long]().withDefaultValue(0L)
  var nextSynch = Instant.EPOCH
  var synchRetries: Int = 0
  var ignoreNextVerifySynch = false

  dsp.queueEvent(NodeInit(this))

  override def handle: MessageHandler = {
    case NodeDataReceived(_, msg, time) =>
      logger.debug(s"$this: handle ${toStr(msg)} received at $time")
      handleNodeDisconnection()
      processDataReceivedMessage(msg, time)
    case NodeIoSampleReceived(_, sample, time) =>
      logger.debug(s"$this: handle ${sample} received at $time")
      handleNodeDisconnection()
      processIOSampleReceived(sample, time)
    case NodeInit(_, time, retry) => processInit(time, retry)
    case VerifySynchAfter(_, _) =>
      if (!ignoreNextVerifySynch) sendCmdSynch() else ignoreNextVerifySynch = false
      Seq.empty
    case VerifySynchCancel(_) =>
      ignoreNextVerifySynch = true
      Seq.empty
    case NodeDisconnectedAfter(_, _, retry) => processNodeDisconnectedEvent(retry)
  }

  def handleNodeDisconnection() {
    while (dsp.queue.remove(NodeDisconnectedAfter(this, Duration.Zero))) {}
    //    assert(ok)
    val delay = ((node.sampleTime * NodeDiscoveredMax) millis)
    logger.debug(s"$this: next NodeDisconnected verification in $delay")
    dsp.queueEvent(NodeDisconnectedAfter(this, delay))
  }

  def probeMap(line: IOLine, pt: ProbeType.ProbeType): Option[Probe] =
    node.lines.find(_.line == line).flatMap(_.probes.find(_.probeType == pt))

  def init() {
    logger.info(s"$this: starting initialization")
    xbeeSetup()
    dsp.queueEvent(NodeDisconnectedAfter(this, node.sampleTime() * NodeDiscoveredMax millis))
    if (node.opMode != OpMode.EndDevice)
      dsp.queueEvent(VerifySynchAfter(this, 0 seconds))
    state = NodeState.Initialized
    logger.info(s"$this: successfully initialized")
  }

  def xbeeSetup() {
    device.enableApplyConfigurationChanges(true)
    if (node.opMode == OpMode.EndDevice || toShort(device.getParameter("SM")) != 1) {
      device.setParameter("CB", Array[Byte](1))
      logger.info(s"$this: commissioning pushbutton command sent successfully")
    }
    val sampleTime = node.sampleTime
    device.enableApplyConfigurationChanges(false)
    if (node.opMode == OpMode.EndDevice) {
      logger.debug(s"$this: configured as an EndDevice")
      val sm: Byte = 4
      // TODO debug
      XbeeNode.sleepConfig(device, sampleTime, logger)
      device.setParameter("SM", Array[Byte](sm))
    }
    if (node.opMode == OpMode.Router) {
      logger.debug(s"$this: configured as a router")
      device.setParameter("SM", Array[Byte](1))
    }
    node.lines.foreach(l => {
      device.setIOConfiguration(l.line, l.mode)
    })
    if (node.lines.exists(_.access == LineAccess.Sampled)) {
      device.setIOSamplingRate(sampleTime)
    }
    device.applyChanges()
    device.writeChanges()

    val pulseSampleTime = if (node.lines.exists(_.probes.exists(p => p.probeType == ProbeType.Pulse))) sampleTime else 0
    val dht22SampleTime = if (node.lines.exists(_.probes.exists(p => p.probeType == ProbeType.DHT22_T))) sampleTime else 0
    sendCmdPulseSampleTime(pulseSampleTime)
    sendCmdDht22SampleTime(dht22SampleTime)

    if (node.opMode == OpMode.EndDevice)
      device.executeParameter("SI")
  }

  def processDataReceivedMessage(msg: XBeeMessage, time: Instant): Seq[QData] = {
    //    log.debug(s"process msg $msg")
    val b = msg.getData()
    val q = b(0) match {
      case 'D' => readDHT22(msg, time)
      //      case 'T' => readXbeeCurrentDateTime(msg)
      case 'P' => readPulse(msg)
      case 'U' => readSynch(msg)
      case _   => logger.error(s"$this: message discarded " + toStr(msg)); Seq.empty
    }
    //      logger.debug(s"read $q")
    if (node.opMode == OpMode.EndDevice || uptimeOffsetInMillis > 0)
      q
    else {
      queue ++= q
      logger.debug(s"$this: q=${queue}")
      Seq.empty
    }
  }

  def processIOSampleReceived(sample: IOSample, time: Instant): Seq[QData] = {
    import scala.collection.JavaConversions._
    if (sample.hasPowerSupplyValue()) {
      notifyLowVoltage(sample.getPowerSupplyValue)
    }
    // FIXME check if probes collected are the ones expected
    (sample.getAnalogValues.flatMap(kv => (probeMap(kv._1, ProbeType.Sample)
      map (p => QData(p, Data(time.toEpochMilli(), kv._2.toDouble)))))) ++ (sample.getDigitalValues flatMap (kv => (probeMap(kv._1, ProbeType.Sample)
      map (p => QData(p, Data(time.toEpochMilli(), kv._2.getID.toDouble)))))) toSeq
  }

  def readSynch(msg: XBeeMessage): Seq[QData] = {
    readXbeeUptime(msg) match {
      case Some(uptime) =>
        val now = Instant.now
        val startTime = now.minus(uptime, ChronoUnit.MILLIS)
        val offset = startTime.toEpochMilli()
        nextSynch = now.plus(SynchLongTimeOutHours, ChronoUnit.HOURS)
        uptimeOffsetInMillis = offset
        //          synchScheduler.cancel()
        dsp.queueEvent(VerifySynchCancel(this))
        state = NodeState.InSynch
        synchRetries = 0
        // update offset of queued data
        val out = queue.map(q => QData(q.probe, Data(q.data.t + offset, q.data.v)))
        queue.clear()
        logger.debug(s"$this: apply uptime offset: $uptime to start date: $startTime o: $offset, q=${out.length} next: ${nextSynch}");
        out
      case None =>
        Seq.empty
    }
  }

  def sendCmdSynch() = {
    val b = createSynchMessage()
    dsp.queueEvent(SendDataAsync(device, b))
    //      mapper ! XbeeMapperActor.SendDataAsync(node, device, b)
  }

  def verifyXbeeSynch {
    val now = Instant.now
    if (now.isAfter(nextSynch)) {
      if (synchRetries < 5) {
        synchRetries += 1
        nextSynch = now.plus(SynchShortTimeOutSec, ChronoUnit.SECONDS)
        //      node.nextSynch = now.plus(SynchShortTimeOutSec, ChronoUnit.SECONDS)
        //          if (synchScheduler.isCancelled)
        //            synchScheduler = system.scheduler.scheduleOnce(SynchShortTimeOutSec + 1 seconds, self, VerifySynch)(system.dispatcher, self)
        dsp.queueEvent(VerifySynchAfter(this, SynchShortTimeOutSec seconds))
        sendCmdSynch()
      } else {
        logger.info(s"$this: max synch retries exceeded: ${synchRetries} state changed to Disconnected")
        state = NodeState.Disconnected
        //          mapper ! state
      }
    }
  }

  def notifyLowVoltage(v: Int) = {
    logger.warn(s"$this: voltage is low: $v")
  }

  def probeFromXpin(xpin: Int, t: ProbeType.ProbeType): Option[Probe] = {
    lineFromXpin(xpin) match {
      case Some(l) =>
        probeMap(l, t)
      case None => logger.error(s"$this: IO Line not found for xpin $xpin"); None
    }
  }

  def responseTimeout: Int = node.sampleTime * 3

  def processInit(time: Duration, retry: Int): Seq[QData] = {
    try {
      init()
    } catch {
      case e: XBeeException =>
        val delay = time + (InitDelay + Random.nextInt(InitDelayRandomRange) millis)
        val r = retry + 1
        if (r > InitMaxRetry) {
          logger.warn(s"$this: error in initialization, maximum number of initialization tentatives exceeded ($r), giving up", e)
          dsp.queueEvent(RemoveActiveNode(address))
        } else {
          logger.info(s"$this: error in initialization, tentative n. $r will be tried after $delay", e)
          dsp.queueEvent(NodeInit(this, delay, r))
        }
    }
    Seq.empty
  }

  def processNodeDisconnectedEvent(retry: Int) = {
    if (retry >= 3) {
      logger.warn(s"$this: identified as disconnected for $retry times, max retries exceeded, giving up.")
      dsp.queueEvent(RemoveActiveNode(address))
    } else {
      state = NodeState.Disconnected
      val delay = (node.sampleTime * NodeDiscoveredMax) millis
      val r = retry + 1
      logger.warn(s"$this: identified as disconnected for $retry times, next verification after $delay.")
      dsp.queueEvent(NodeDisconnectedAfter(this, delay, r))
      if (retry > 2)
        dsp.queueEvent(AwakeSleepingNode(address))
    }
    Seq.empty
  }

  override def toString = s"N(${node.name})"
}
