package net.reliqs.emonlight.xbeeGateway.xbee

import java.time.Instant
import java.util.concurrent.Delayed
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.Duration

import com.digi.xbee.api.RemoteXBeeDevice
import com.digi.xbee.api.io.IOSample
import com.digi.xbee.api.models.XBeeMessage

import net.reliqs.emonlight.xbeeGateway.QData
import net.reliqs.emonlight.xbeeGateway.Node

class Event extends Delayed {

  def getDelay(tUnit: TimeUnit): Long = 0

  def compareTo(d: Delayed): Int = getDelay(TimeUnit.NANOSECONDS).compareTo(d.getDelay(TimeUnit.NANOSECONDS))
}

trait Delaying extends Event {
  val time: Duration = Duration.Zero
  val timeout: Long = Instant.now.toEpochMilli() + time.toMillis

  override def getDelay(tUnit: TimeUnit): Long = {
    tUnit.convert(timeout - Instant.now().toEpochMilli(), TimeUnit.MILLISECONDS)
  }
}

class ProcessorEvent extends Event

class NodeEvent(val node: NodeEventHandling) extends Event

case class StartScheduledDiscovering(override val time: Duration = Duration.Zero) extends ProcessorEvent with Delaying

case class DeviceDiscovered(d: RemoteXBeeDevice) extends ProcessorEvent

case class DiscoveryError(msg: String) extends ProcessorEvent

case class DiscoveryFinished(msg: String) extends ProcessorEvent

case class AwakeSleepingNode(address: String) extends ProcessorEvent

case class DataReceived(msg: XBeeMessage, time: Instant) extends ProcessorEvent {
  override def toString = s"DR(${Processor.toStr(msg)}, ${time})"
}

case class NodeDataReceived(override val node: NodeEventHandling, msg: XBeeMessage, time: Instant) extends NodeEvent(node) {
  override def toString = s"NDR($node, ${Processor.toStr(msg)}, $time)"
}

case class IoSampleReceived(device: RemoteXBeeDevice, sample: IOSample, time: Instant) extends ProcessorEvent

case class NodeIoSampleReceived(override val node: NodeEventHandling, sample: IOSample, time: Instant) extends NodeEvent(node) {
  override def toString = s"NIO($node, ${sample}, $time)"
}

case class SendDataAsync(device: RemoteXBeeDevice, b: Array[Byte]) extends ProcessorEvent

case class VerifySynchAfter(override val node: NodeEventHandling, override val time: Duration) extends NodeEvent(node) with Delaying

case class VerifySynchCancel(override val node: NodeEventHandling) extends NodeEvent(node)

case class NodeDisconnectedAfter(override val node: NodeEventHandling, 
    override val time: Duration, retry: Int = 1) extends NodeEvent(node) with Delaying {
  override def equals(other: Any) = other match {
    case that: NodeDisconnectedAfter => node.equals(that.node)
    case _                           => false
  }

  override def hashCode = node.hashCode()
}

case class RemoveActiveNode(address: String) extends ProcessorEvent

case class NodeInit(override val node: NodeEventHandling, override val time: Duration = Duration.Zero, retry: Int = 1) extends NodeEvent(node) with Delaying

object Event {
  type EventHandler = PartialFunction[Event, Unit]
  type MessageHandler = PartialFunction[NodeEvent, Seq[QData]]
}
