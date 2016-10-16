package net.reliqs.emonlight.xbeeGateway.xbee

import scala.language.postfixOps

import com.digi.xbee.api.RemoteXBeeDevice
import com.digi.xbee.api.XBeeDevice
import com.digi.xbee.api.io.IOSample
import com.digi.xbee.api.listeners.IDataReceiveListener
import com.digi.xbee.api.listeners.IDiscoveryListener
import com.digi.xbee.api.listeners.IIOSampleReceiveListener
import com.digi.xbee.api.models.XBeeMessage
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import com.digi.xbee.api.utils.HexUtils
import akka.actor.ActorLogging
import net.reliqs.emonlight.xbeeGateway.Config
import com.digi.xbee.api.exceptions.XBeeException
import akka.actor.Status
import akka.agent.Agent
import net.reliqs.emonlight.xbeeGateway.DispatchBus
import net.reliqs.emonlight.xbeeGateway.Master
import akka.actor.Terminated
import net.reliqs.emonlight.xbeeGateway.Node
import scala.concurrent.Await
import akka.pattern.ask
import akka.util.Timeout
import akka.actor.PoisonPill
import scala.concurrent.Future
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import scala.util.Success
import scala.util.Failure
import java.util.concurrent.TimeoutException

object ProcessorActor {

  case object InitComplete
  case object Process
  case object Discovery

  def props(cfg: Agent[Config], dsp: Dispatcher, parent: ActorRef): Props = Props(new ProcessorActor(cfg, dsp, parent))

}

/**
 * Connected to local Xbee node through a serial port. It receive and transmit all messages to other remote xbee nodes.
 *  On startup perform a discovery operation in order to find active nodes.
 *  The discovery operation is repeated on regular basis in case Router nodes listed in configuration have not been identified.
 */
class ProcessorActor(cfg: Agent[Config], dsp: Dispatcher, parent: ActorRef) extends Actor with ActorLogging {
  import ProcessorActor._

  val master = context.parent
  val dBus = cfg().dBus
  parent ! InitComplete

  def process() {
    import context.dispatcher
    val f = Future(dsp.process())
    try {
      val result = Await.result(f, 1 seconds)
      result foreach (dBus.publish(_))
    } catch {
      case e: TimeoutException =>
    }
    self ! Process
  }

  def receive = {
    case Discovery => dsp.queueEvent(StartScheduledDiscovering())
    case Process   => process()
  }

}