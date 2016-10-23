package net.reliqs.emonlight.xbeeGateway.xbee

import com.typesafe.scalalogging.LazyLogging
import java.util.concurrent.DelayQueue
import net.reliqs.emonlight.xbeeGateway.QData
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration

trait EventHandling {
  def eventHandler: Event.EventHandler
}

trait NodeEventHandling {
  def handle: Event.MessageHandler
}

trait Dispatcher { this: EventHandling with LazyLogging =>
  import scala.collection.JavaConversions._

  val queue = new DelayQueue[Event]()

  def queueEvent(event: Event) = queue.offer(event)

  def handle(e: Event): Seq[QData] = {
    //    logger.debug(s"event $e")
    if (e.isInstanceOf[ProcessorEvent]) {
      eventHandler.orElse(PartialFunction.empty)(e)
      Seq.empty
    } else {
      val ne = e.asInstanceOf[NodeEvent]
      val q = ne.node.handle(ne)
      logger.debug(s"data Produced $q")
      q
    }
  }

  def existsEvent(event: Event) = queue.exists { e => e == event }

  def removeEvent(event: Event) = {
    var cnt = 0
    while (queue.remove(event)) { cnt += 1 }
    cnt
    //    var cnt = 0
    //    while (queue.find(e => e == event) match {
    //      case Some(e) =>
    //        cnt += 1; queue.remove(e)
    //      case None => false
    //    }) {}
    //    logger.debug(s"removed $cnt events $event")
  }

  def removeEventsForNode(n: NodeEventHandling) {
    var cnt = 0
    while (queue.find(e => (e.isInstanceOf[NodeEvent] && e.asInstanceOf[NodeEvent].node == n)) match {
      case Some(e) =>
        cnt += 1; queue.remove(e)
      case None => false
    }) {}
    logger.debug(s"removed $cnt events related to $n")
  }

  def process(timeout: Duration): Seq[QData] = {
    //    logger.debug("poll")
    Option(queue.poll(timeout.toSeconds, TimeUnit.SECONDS)) match {
      case Some(e) => handle(e)
      case None    => Seq.empty
    }
  }

}
