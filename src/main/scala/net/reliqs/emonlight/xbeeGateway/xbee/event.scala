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
  val time: Duration
  val timeout: Long = if (time.isFinite()) Instant.now.toEpochMilli() + time.toMillis else 0

  override def getDelay(tUnit: TimeUnit): Long = {
    tUnit.convert(timeout - Instant.now().toEpochMilli(), TimeUnit.MILLISECONDS)
  }
}

trait EventEquality extends Event {
  override def equals(other: Any) = other match {
    case that: Event => that.getClass == this.getClass
    case _           => false
  }

  override def hashCode = getClass.hashCode()

}

class ProcessorEvent extends Event

class NodeEvent(val node: NodeEventHandling) extends Event

trait NodeEquality extends NodeEvent {
  override def equals(other: Any) = other match {
    case that: NodeEvent => that.getClass == this.getClass && node.equals(that.node)
    case _               => false
  }

  override def hashCode = node.hashCode()
}

case class StartScheduledDiscovering(override val time: Duration = Duration.Zero)
  extends ProcessorEvent with Delaying with EventEquality

case class DeviceDiscovered(d: RemoteXBeeDevice, override val time:Duration) extends ProcessorEvent with Delaying {
  override def equals(other: Any) = other match {
    case that: DeviceDiscovered => that.d == this.d
    case _                      => false
  }

  override def hashCode = DeviceDiscovered.hashCode() + d.hashCode()
}

case class DiscoveryError(msg: String) extends ProcessorEvent

case class DiscoveryFinished(msg: String, devices: Seq[RemoteXBeeDevice]) extends ProcessorEvent

case class AwakeSleepingNode(address: String, override val time: Duration = Duration.Zero)
  extends ProcessorEvent with Delaying

case class DataReceived(msg: XBeeMessage, time: Instant) extends ProcessorEvent {
  override def toString = s"DR(${Processor.toStr(msg)}, ${time})"
}

case class NodeDataReceived(override val node: NodeEventHandling, msg: XBeeMessage, time: Instant)
    extends NodeEvent(node) {
  override def toString = s"NDR($node, ${Processor.toStr(msg)}, $time)"
}

case class IoSampleReceived(device: RemoteXBeeDevice, sample: IOSample, time: Instant) extends ProcessorEvent

case class NodeIoSampleReceived(override val node: NodeEventHandling, sample: IOSample, time: Instant)
    extends NodeEvent(node) {
  override def toString = s"NIO($node, ${sample}, $time)"
}

case class SendDataAsync(device: RemoteXBeeDevice, b: Array[Byte]) extends ProcessorEvent

case class VerifySynchAfter(override val node: NodeEventHandling, override val time: Duration = Duration.Zero)
  extends NodeEvent(node) with Delaying

case class NodeDisconnectedAfter(override val node: NodeEventHandling, override val time: Duration, retry: Int = 1)
  extends NodeEvent(node) with Delaying with NodeEquality

case class RemoveActiveNode(address: String, override val time: Duration = Duration.MinusInf) extends ProcessorEvent with Delaying

case class NodeInit(override val node: NodeEventHandling, override val time: Duration = Duration.Zero, retry: Int = 1)
  extends NodeEvent(node) with Delaying with NodeEquality

case class SignalStartNodeInit(override val time: Duration = Duration.MinusInf) extends ProcessorEvent with Delaying

case class SignalEndNodeInit(override val time: Duration = Duration.MinusInf) extends ProcessorEvent with Delaying


object Event {
  type EventHandler = PartialFunction[Event, Unit]
  type MessageHandler = PartialFunction[NodeEvent, Seq[QData]]
}
