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
import com.digi.xbee.api.utils.HexUtils
import scala.collection.mutable.ArrayBuffer

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
  val InitDelay = 30000
  val InitDelayRandomRange = 10000
}

class XbeeNode(val address: String, val device: RemoteXBeeDevice, val node: Node, val proc: Processor, val dsp: Dispatcher)
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

  //  dsp.queueEvent(NodeInit(this))
  processInit(Duration.Zero, 1)

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
    case VerifySynchAfter(_, _) => sendCmdSynch(); Seq.empty
    case NodeDisconnectedAfter(_, _, retry) => processNodeDisconnectedEvent(retry)
  }

  def handleNodeDisconnection() {
    dsp.removeEvent(NodeDisconnectedAfter(this, Duration.Zero))
    //    assert(ok)
    val delay = ((node.sampleTime * NodeDiscoveredMax) millis)
    logger.debug(s"$this: next NodeDisconnected verification in $delay")
    dsp.queueEvent(NodeDisconnectedAfter(this, delay))
  }

  def probeMap(line: IOLine, pt: ProbeType.ProbeType): Option[Probe] =
    node.lines.find(_.line == line).flatMap(_.probes.find(_.probeType == pt))

  def processInit(time: Duration, retry: Int): Seq[QData] = {
    if (proc.cfg.applyConfig && proc.state != Processor.State.Ready) {
      dsp.queueEvent(NodeInit(this, (InitDelay + Random.nextInt(InitDelayRandomRange)) millis))
    } else {
      dsp.queueEvent(SignalStartNodeInit())
      try {
        init(retry)
      } catch {
        case e: XBeeException =>
          val delay = ((InitDelay + Random.nextInt(InitDelayRandomRange)) millis)
          val r = retry + 1
          if (r > InitMaxRetry) {
            logger.warn(s"$this: error in initialization, maximum number of initialization tentatives exceeded ($r), giving up", e)
            dsp.queueEvent(RemoveActiveNode(address))
          } else {
            logger.info(s"$this: error in initialization, tentative n. $r will be tried after $delay", e)
            dsp.removeEvent(NodeInit(this, delay, r))
            dsp.queueEvent(NodeInit(this, delay, r))
          }
      } finally {
        dsp.queueEvent(SignalEndNodeInit())
      }
    }
    Seq.empty
  }

  def init(retry: Int) {
    logger.info(s"$this: starting initialization")
    if (proc.cfg.applyConfig)
      xbeeSetup(retry)
    dsp.removeEvent(NodeDisconnectedAfter(this, Duration.Zero))
    dsp.queueEvent(NodeDisconnectedAfter(this, node.sampleTime() * NodeDiscoveredMax millis))
    if (node.opMode != OpMode.EndDevice)
      dsp.queueEvent(VerifySynchAfter(this))
    state = NodeState.Initialized
    logger.info(s"$this: successfully initialized")
  }

  /**
   * Xbee setup.
   * TODO send a RESET in case the configuration fails too many times.
   * TODO read first configuration and apply new conf only if it is different
   */
  def xbeeSetup(retry: Int) {
    device.enableApplyConfigurationChanges(true)
    if (retry > 1) {
      device.reset()
      Thread.sleep(1000)
    }
    //    if (node.opMode == OpMode.EndDevice || toShort(device.getParameter("SM")) != 0) {
    device.setParameter("CB", Array[Byte](1))
    logger.info(s"$this: commissioning pushbutton command sent successfully")
    Thread.sleep(1000)
    //    }
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
      device.setParameter("SM", Array[Byte](0))
    }
    node.lines.foreach(l => {
      device.setIOConfiguration(l.line, l.mode)
    })
    device.setIOSamplingRate((if (node.lines.exists(_.access == LineAccess.Sampled)) sampleTime else 0))
    device.applyChanges()
    device.writeChanges()

    if (node.opMode == OpMode.EndDevice)
      device.executeParameter("SI")
//    else {
//      device.reset()
////      device.executeParameter("FR")
//    }
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
        dsp.removeEvent(VerifySynchAfter(this))
        // after each Xbee reset we have to reconfigure sample times for pulses and dht22
        sendCmdSampleTime()
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

  def sendCmdSynch() {
    val b = createSynchMessage()
    dsp.queueEvent(SendDataAsync(device, b))
    //      mapper ! XbeeMapperActor.SendDataAsync(node, device, b)
  }

  def sendCmdSampleTime() {
    node.lines.find(_.probes.exists(p => p.probeType == ProbeType.Pulse)) match {
      case Some(l) =>
        sendCmdPulseSampleTime(l.sampleTime)
      case None =>
    }
    node.lines.find(_.probes.exists(p => p.probeType == ProbeType.DHT22_T)) match {
      case Some(l) =>
        sendCmdDht22SampleTime(l.sampleTime)
      case None =>
    }
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

  def processNodeDisconnectedEvent(retry: Int) = {
    if (retry >= 3) {
      logger.warn(s"$this: identified as disconnected for $retry times, max retries exceeded, giving up.")
      dsp.queueEvent(RemoveActiveNode(address))
      dsp.queueEvent(AwakeSleepingNode(address))
    } else {
      state = NodeState.Disconnected
      val delay = (node.sampleTime * NodeDiscoveredMax) millis
      val r = retry + 1
      logger.warn(s"$this: identified as disconnected for $retry times, next verification after $delay.")
      dsp.queueEvent(NodeDisconnectedAfter(this, delay, r))
    }
    Seq.empty
  }

  def readDHT22(msg: XBeeMessage, time: Instant): Seq[QData] = {
    parseDHT22Msg(msg.getData) match {
      case None =>
        logger.warn(s"$this read DHT22 failure")
        List.empty
      case Some(tp) =>
        val t = if (node.opMode == OpMode.EndDevice) time.toEpochMilli() else uptimeOffsetInMillis + tp._4
        //        log.debug(s"L=${tp._1}, T=${tp._2}, H=${tp._3}, d=${Instant.ofEpochMilli(t)}")
        (probeFromXpin(tp._1, ProbeType.DHT22_T), probeFromXpin(tp._1, ProbeType.DHT22_H)) match {
          case (Some(p_t), Some(p_h)) =>
            QData(p_t, Data(t, tp._2)) :: QData(p_h, Data(t, tp._3)) :: Nil
          case _ =>
            logger.error(s"$this Probe or IO Line not found for xpin ${tp._1}")
            List.empty
        }
    }
  }

  def parsePulseMsg(b: Array[Byte]): Seq[(Short, Long)] = {
    val bb = ByteBuffer.wrap(b)
    if ('P' != bb.get()) {
      logger.warn("$this pulse message not correct: " + HexUtils.byteArrayToHexString(b))
      List.empty
    } else {
      var l = new ArrayBuffer[(Short, Long)]()
      for (i <- 1 to b.length / 10) {
        val xpin = bb.getShort()
        val uptime = convertUptime(bb.getInt(), bb.getInt())
        val probe = probeFromXpin(xpin, ProbeType.Pulse)
        // TODO when power calculation will be implemented on xbee side, power value will be added
        l += ((xpin, uptime))
      }
      //      log.debug(s"pulse $l")
      l.toSeq
    }
  }

  def readPulse(msg: XBeeMessage): Seq[QData] = {
    parsePulseMsg(msg.getData) flatMap (m => (probeFromXpin(m._1, ProbeType.Pulse) map
      (p => QData(p, Data(uptimeOffsetInMillis + m._2, calcPower(m._2, p))))))
  }

  def calcPower(t: Long, p: Probe): Double = {
    val dt = t - lastTime(p)
    lastTime(p) = t
    if (dt > 0) (3600000000.0 / dt) / p.pulsesPerKilowattHour else 0
  }

  def sendCmdPulseSampleTime(sampleTime: Int) = {
    logger.debug(s"$this: send Pulse Sample Time $sampleTime")
    val b = ByteBuffer.allocate(6)
    b.put('S'.toByte)
    b.put('P'.toByte)
    b.put(ByteUtils.intToByteArray(sampleTime))
    b.array()
    dsp.queueEvent(SendDataAsync(device, b.array()))
  }

  def sendCmdDht22SampleTime(sampleTime: Int) = {
    logger.debug(s"$this: send DHT22 Sample Time $sampleTime")
    val b = ByteBuffer.allocate(6)
    b.put('S'.toByte)
    b.put('D'.toByte)
    b.put(ByteUtils.intToByteArray(sampleTime))
    b.array()
    dsp.queueEvent(SendDataAsync(device, b.array()))
  }

  override def toString = s"N(${node.name})"
}
