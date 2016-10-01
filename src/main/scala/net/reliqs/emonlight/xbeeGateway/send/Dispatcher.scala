package net.reliqs.emonlight.xbeeGateway.send

import java.io.File

import scala.collection.mutable.Map
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import com.fasterxml.jackson.core.`type`.TypeReference

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Terminated
import akka.actor.actorRef2Scala
import akka.agent.Agent
import net.reliqs.emonlight.xbeeGateway.Config
import net.reliqs.emonlight.xbeeGateway.Factory
import net.reliqs.emonlight.xbeeGateway.Master
import net.reliqs.emonlight.xbeeGateway.NodeData
import net.reliqs.emonlight.xbeeGateway.QData
import net.reliqs.emonlight.xbeeGateway.ServerData
import net.reliqs.emonlight.xbeeGateway.send.Dispatcher.DispatcherState

trait DispatcherProvider {
  def provide(parent: ActorRef, cfg: Agent[Config], name: String): Actor
}

object DState extends Enumeration {
  type DState = Value
  val Stopped, Disabled, Ready, Sending = Value
}
class StateType extends TypeReference[DState.type]

object Dispatcher {

  case object Send
  case object Ack
  case object Fail
  case object GetState
  case object Enable
  case object Disable

  case class DispatcherState(state: DState.DState, buf: Vector[QData], inFlight: Vector[QData], serverData: ServerData)

  var savedState = Map[String, DispatcherState]()
}

class Dispatcher(cfg: Agent[Config], name: String, restoreState: Boolean) extends Actor with ActorLogging { this: DispatcherProvider =>
  import Dispatcher._

  val server = cfg().servers.find(_.name == name).get
  val dBus = cfg().dBus
  val childDispatcher = createChildDispatcher
  context.watch(childDispatcher)
  var buf: Vector[QData] = Vector.empty
  var inFlight: Vector[QData] = Vector.empty
  var scheduler = context.system.scheduler.schedule(server.sendRate milli, server.sendRate milli, self, Send)(context.system.dispatcher, self)
  var state: DState.DState = DState.Disabled
  var pendingState: DState.DState = DState.Ready
  var serverData: ServerData = null
  var killRequester: ActorRef = ActorRef.noSender
  val savePath = s"${cfg().savePath}/d-${name}.json"
  for (sm <- server.maps) dBus.subscribe(self, sm.probe)

  def receive: Actor.Receive = {
    case q: QData =>
//      log.debug(s"received $q")
      buf :+= q
    case Send =>
      deliverBuf()
    case Ack =>
      log.info(s"receive Ack from ${sender()} for ${inFlight.length} data")
      inFlight = Vector.empty
      serverData = null
      changeState(DState.Ready)
    case Fail =>
      log.warning(s"receive Fail from ${sender()} for ${inFlight.length} data")
      //FIXME handle retries
      changeState(DState.Ready)
    case GetState =>
      log.debug("getState")
      sender() ! state
    case Enable =>
      log.debug("enable")
      changeState(DState.Ready)
    case Disable =>
      log.debug("disable")
      changeState(DState.Disabled, false)
    case Master.Kill =>
      log.info("kill")
      killRequester = sender()
      changeState(DState.Stopped, false)
    case Terminated(c) =>
      log.debug("terminated child")
      dBus.unsubscribe(self)
      saveState
      killRequester ! Master.KillCompleted
    case ex: Exception => throw ex //FIXME For testing purposes only
  }

  /** Called on actor's start. State is loaded from a file, if present. Initial state is Disabled. **/
  override def preStart() {
    if(restoreState) loadState
    state = DState.Disabled
  }

  /**
   * Saving actor state in a map, in order to be consumed by new instance's postRestart.
   * * Does not touch child actor. *
   */
  override def preRestart(reason: Throwable, message: Option[Any]) {
    if (state == DState.Sending) state = DState.Ready
    val s = DispatcherState(state, buf, inFlight, serverData)
    log.debug(s"preRestart msg:${message} state:${s}")
    savedState(name) = s
    state = DState.Stopped
    inFlight = Vector.empty
    super.preRestart(reason, message)
  }

  /** Restore state saved in preRestart. Does not touch child actor. **/
  override def postRestart(reason: Throwable) {
    savedState.remove(name) match {
      case Some(s) =>
        log.debug(s"postRestart restore")
        restoreState(s)
      case None =>
        log.warning(s"postRestart without a saved state")
    }
  }

  override def postStop() {
    log.debug(s"stop in state $state, inFlight=$inFlight")
    assert(state == DState.Stopped)
  }

  def changeState(targetState: DState.DState, force: Boolean = true) {
    if (force) {
      state = if (pendingState != DState.Ready) pendingState else targetState
      pendingState = DState.Ready
    } else if (state == DState.Sending && (pendingState == DState.Ready || pendingState == targetState))
      pendingState = targetState
    else state = targetState
    //    } else throw new Exception(s"change state not correct from $state to $targetState")
    if (state == DState.Stopped) {
      log.info("stop child")
      context.stop(childDispatcher)
    }
  }

  private def deliverBuf() = {
    log.debug(s"deliver s=$state buf=${buf.length} inFlight=${inFlight.length}")
    assert((inFlight.isEmpty && serverData == null)
      || (inFlight.nonEmpty && serverData != null))
    assert(if (state == DState.Sending) inFlight.nonEmpty else true)
    //    assert((state == DState.Ready && inFlight.isEmpty && serverData == null)
    //      || (state == DState.Ready && inFlight.nonEmpty && serverData != null)
    //      || (state == DState.Sending && inFlight.nonEmpty)
    //      || (state == DState.Disabled && inFlight.isEmpty)
    //      || (state == DState.Stopped && inFlight.isEmpty))
    if (state == DState.Ready && buf.nonEmpty && inFlight.isEmpty) {
      inFlight = buf
      buf = Vector.empty
      serverData = createServerData
    }
    if (state == DState.Ready && serverData != null) {
      state = DState.Sending
      childDispatcher ! serverData
    }
  }

  private def createServerData: ServerData = {
    log.info(s"produce serverData from ${inFlight.length}")
    val s = ServerData(server.maps.map(sm => NodeData(sm.apiKey, sm.nodeId, inFlight.filter { q => q.probe == sm.probe }
      .map(_.convertData).to[List])).filter { n => n.d.nonEmpty })
    assert(s.nodes.nonEmpty)
    s
  }

  private def createChildDispatcher: ActorRef = {
    context.actorOf(Props(provide(self, cfg, name)), name = s"h-${server.name}")
  }

  private def saveState = {
    val saved = DispatcherState(state, buf, inFlight, serverData)
    Factory.save(saved, savePath)
  }

  private def loadState = {
    val f = new File(savePath)
    if (f.canRead()) {
      val saved = Factory.mapper.readValue(f, classOf[DispatcherState])
      restoreState(saved)
    } else log.debug(s"no state saved for $self")
  }

  private def restoreState(saved: DispatcherState) = {
    log.debug(s"restore state from $saved")
    buf = saved.buf
    inFlight = saved.inFlight
    serverData = saved.serverData
  }
}