package net.reliqs.emonlight.xbeeGateway.xbee

import com.digi.xbee.api.models.XBeeMessage
import java.nio.ByteBuffer
import akka.event.Logging
import akka.event.LoggingAdapter
import java.time.LocalDateTime
import java.time.ZoneId
import com.digi.xbee.api.io.IOLine
import net.reliqs.emonlight.xbeeGateway.MainApp
import akka.actor.ActorLogging
import com.digi.xbee.api.utils.HexUtils
import scala.collection.mutable.ListBuffer
import net.reliqs.emonlight.xbeeGateway.ProbeType
import com.typesafe.scalalogging.LazyLogging
import java.time.Instant
import net.reliqs.emonlight.xbeeGateway.QData
import net.reliqs.emonlight.xbeeGateway.OpMode
import net.reliqs.emonlight.xbeeGateway.Data
import scala.collection.mutable.ArrayBuffer
import net.reliqs.emonlight.xbeeGateway.Probe
import com.digi.xbee.api.utils.ByteUtils

trait XbeeNodeExt { this: XbeeNode with LazyLogging =>

  //  val log: LoggingAdapter = Logging(MainApp.system, "helper")

  val XPinMap: Map[Int, IOLine] = Map(
    0 -> IOLine.DIO0_AD0,
    1 -> IOLine.DIO9,
    2 -> IOLine.DIO11_PWM1,
    3 -> IOLine.DIO1_AD1,
    8 -> IOLine.DIO14,
    9 -> IOLine.DIO13,
    10 -> IOLine.DIO2_AD2,
    11 -> IOLine.DIO4_AD4,
    12 -> IOLine.DIO12,
    13 -> IOLine.DIO3_AD3)

  def parseDHT22Msg(b: Array[Byte]): Option[(Short, Double, Double, Long)] = {
    var res: Option[(Short, Double, Double, Long)] = None
    val bb = ByteBuffer.wrap(b)
    if (b.length == 16 && bb.get() == 'D') {
      val xpin = bb.getShort()
      val uptime = convertUptime(bb.getInt(), bb.getInt());
      val b0 = bb.get()
      val b1 = bb.get()
      val b2 = bb.get()
      val b3 = bb.get()
      val b4 = bb.get()
      if (((b0 + b1 + b2 + b3) & 0xFF).asInstanceOf[Byte] != b4) {
        logger.warn("error reading dht22 data")
      } else {
        val humidity = 0.0 + ((b0 << 8) + b1) / 10.0
        val temperature = 0.0 + (((b2 & 0x7F) << 8) + b3) / 10.0
        res = Some((xpin, temperature, humidity, uptime))
      }
    }
    res
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

  
  def convertUptime(s: Int, ms: Int): Int = {
    s * 1000 + ms % 1000
  }

  def sendCmdPulseSampleTime(sampleTime: Int) = {
    val b = ByteBuffer.allocate(6)
    b.put('S'.toByte)
    b.put('P'.toByte)
    b.put(ByteUtils.intToByteArray(sampleTime / 4))
    b.array()
    dsp.queueEvent(SendDataAsync(device, b.array()))
  }

  def sendCmdDht22SampleTime(sampleTime: Int) = {
    val b = ByteBuffer.allocate(6)
    b.put('S'.toByte)
    b.put('D'.toByte)
    b.put(ByteUtils.intToByteArray(sampleTime / 4))
    b.array()
    dsp.queueEvent(SendDataAsync(device, b.array()))
  }

  def readXbeeUptime(msg: XBeeMessage): Option[Long] = {
    val b = ByteBuffer.wrap(msg.getData())
    val header = b.get()
    if (header != 'U') None else Some(convertUptime(b.getInt(), b.getInt()));
  }

  def readXbeeCurrentDateTime(msg: XBeeMessage): Boolean = {
    false
  }

  def createSynchMessage(): Array[Byte] = {
    val b = ByteBuffer.allocate(9)
    b.put('S'.asInstanceOf[Byte])
    b.put('Y'.asInstanceOf[Byte])
    b.put(fillWithCurrentDateTime())
    b.array()
  }

  def fillWithCurrentDateTime(): ByteBuffer = {
    val b = ByteBuffer.allocate(7)
    val now = LocalDateTime.now(ZoneId.of("UTC"));
    b.put(now.getSecond().asInstanceOf[Byte]);
    b.put(now.getMinute().asInstanceOf[Byte]);
    b.put(now.getHour().asInstanceOf[Byte]);
    b.put(now.getDayOfMonth().asInstanceOf[Byte]);
    b.put(now.getMonthValue().asInstanceOf[Byte]);
    b.putShort(now.getYear().asInstanceOf[Short]);
  }

  def lineFromXpin(xpin: Int): Option[IOLine] = {
    XPinMap.get(xpin & 0x1F)
  }

}