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

  val cfg = Factory.read("src/test/resources/lab3.conf")
  val my = new MyProc(cfg)

  "A processor" should {
    "init correctly" in {
      //      assert(my.dsp.mapperHandler === my.eventHandler)
      //      assert(my.mapper.cfg === my.cfg)
      assert(my.activeNodes.size === 0)
    }
    "discover correctly" in {
//      my.queueEvent(StartScheduledDiscovering(10 seconds))
      assert(my.run(20).length >= 0)
      assert(my.activeNodes.size > 0)
    }
    "synch correctly" in {
      my.verifySynch()
      assert(my.run(30).length > 0)
    }
    "work in the long run" in {
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
      for (t <- 1 to time) q ++= process()
      q
    }
  }

}
