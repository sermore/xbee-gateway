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

trait XbeeNodeExt { this: LazyLogging =>

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
        val humidity = 0.0 + (((b0 & 0xff) << 8) + (b1 & 0xff)) / 10.0
        val temperature = 0.0 + (((b2 & 0x7F) << 8) + (b3 & 0xff)) / 10.0
//        logger.debug(s"$temperature, $humidity, $uptime")
        res = Some((xpin, temperature, humidity, uptime))
      }
    }
    res
  }
  
  def convertUptime(s: Int, ms: Int): Int = {
    s * 1000 + ms % 1000
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