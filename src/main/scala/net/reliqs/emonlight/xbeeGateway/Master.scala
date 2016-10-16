package net.reliqs.emonlight.xbeeGateway

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.Terminated
import akka.agent.Agent
import akka.http.scaladsl.Http
import net.reliqs.emonlight.xbeeGateway.send.Dispatcher
import net.reliqs.emonlight.xbeeGateway.send.HttpDispatcherProvider
import net.reliqs.emonlight.xbeeGateway.send.HttpServerDispatcher
import net.reliqs.emonlight.xbeeGateway.xbee.FullProcessor
import net.reliqs.emonlight.xbeeGateway.xbee.ProcessorActor

object Master {
  case object Kill
  case object KillCompleted

  def props(cfg: Config): Props = Props(new Master(cfg))
}

class Master(val cfg: Config) extends Actor with ActorLogging {
  import Master._
  import scala.concurrent.ExecutionContext.Implicits.global

  val agent = Agent(cfg)
  val xbeeProc = new FullProcessor(cfg)
  xbeeProc.init()
  val processor = context.actorOf(ProcessorActor.props(agent, xbeeProc, self), "processor")
  context.watch(processor)
  //  var children: Set[ActorRef] = Set.empty

  def receive = {
    case ProcessorActor.InitComplete =>
      val ds = cfg.servers.map(s => context.actorOf(HttpServerDispatcher.parentProps(agent, s.name), "d-" + s.name))
      ds.foreach { c => context.watch(c); c ! Dispatcher.Enable }
      processor ! ProcessorActor.Process
    case Kill =>
      log.debug("kill")
      //     children = context.children.toSet
      context.children.foreach(_ ! Kill)
    case KillCompleted =>
      log.debug("kill completed")
      context.stop(sender())
    case Terminated(a) =>
      log.debug(s"terminated $a")
      //      children -= a
      if (context.children.isEmpty) {
        context.stop(self)
      }
  }

  override def postStop() {
    // FIXME handle http shutdown only when it has been started
    Await.result(Http(context.system).shutdownAllConnectionPools(), 5 seconds)
    context.system.terminate
    log.debug("system terminated")
  }

}
