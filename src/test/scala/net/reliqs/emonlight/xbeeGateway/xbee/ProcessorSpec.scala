package net.reliqs.emonlight.xbeeGateway.xbee

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.language.postfixOps

import org.scalatest.WordSpec

import com.typesafe.scalalogging.LazyLogging

import net.reliqs.emonlight.xbeeGateway.Config
import net.reliqs.emonlight.xbeeGateway.Factory
import net.reliqs.emonlight.xbeeGateway.QData
import java.util.concurrent.TimeUnit

class ProcessorSpec extends WordSpec {
  import ProcessorSpec._

  val cfg = Factory.read("src/test/resources/lab2.conf")
  val my = new MyProc(cfg)
  
  class MyNodeH extends NodeEventHandling {
    def handle: Event.MessageHandler = {
      ???
    }
  }

  "A processor" should {
    "handle events" in {
      assert(StartScheduledDiscovering(40 millis) == StartScheduledDiscovering())
      assert(StartScheduledDiscovering(40 millis) != DiscoveryError(""))
      assert(SignalStartNodeInit() == SignalStartNodeInit()) 
      val n1 = new MyNodeH()
      val n2 = new MyNodeH()
      my.queueEvent(NodeInit(n1, 1000 millis))
      my.queueEvent(StartScheduledDiscovering())
      my.queueEvent(StartScheduledDiscovering(100 millis))
      assert(my.removeEvent(StartScheduledDiscovering()) == 2)
      my.queueEvent(NodeInit(n2))
      assert(my.removeEvent(NodeInit(n1, 100 millis)) == 1)
      assert(my.removeEvent(NodeInit(n1, 800 millis)) == 0)
      assert(my.removeEvent(NodeInit(n2, 100 millis)) == 1)
    }
    "do time summing" in {
      val x:Duration = 30000 millis
      val y = x + (50000 millis)
      assert(y == (80000 millis))
    }
    "init correctly" ignore {
      //      assert(my.dsp.mapperHandler === my.eventHandler)
      //      assert(my.mapper.cfg === my.cfg)
      assert(my.activeNodes.size === 0)
    }
    "discover correctly" ignore {
//      my.queueEvent(StartScheduledDiscovering(10 seconds))
      assert(my.run(20).length >= 0)
      assert(my.activeNodes.size > 0)
    }
    "synch correctly" ignore {
      my.verifySynch()
      assert(my.run(30).length > 0)
    }
    "work in the long run" ignore {
      assert(my.run(110).length > 0)

    }
  }
}

object ProcessorSpec {

//  trait MyFactory extends XbeeNodeFactory {
//    case class XN(override val address: String, override val device: RemoteXBeeDevice, override val node: Node, override val dsp: Dispatcher)
//      extends XbeeNode(address, device, node, dsp)
//    override def create(address: String, device: RemoteXBeeDevice, node: Node, dsp: Dispatcher): XbeeNode = {
//      new XN(address, device, node, dsp)
//    }
//  }
//
//  trait Simple extends MyFactory with NodeManager with Dispatcher with LazyLogging {
//    override val dsp: Dispatcher = this
//    override val factory: XbeeNodeFactory = this
//    val mapper = this
//  }
//
//  class MyX(val cfg: Config) extends Simple with Processor {
//    override val mapperHandler = eventHandler

  class MyProc(override val cfg: Config) extends FullProcessor(cfg) {
    
    def run(time: Int): Seq[QData] = {
      val q = ArrayBuffer[QData]()
      for (t <- 1 to time) q ++= process(1 second)
      q
    }
  }

}
