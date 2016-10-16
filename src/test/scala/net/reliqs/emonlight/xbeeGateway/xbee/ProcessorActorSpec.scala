package net.reliqs.emonlight.xbeeGateway.xbee

import scala.concurrent.duration._
import scala.language.postfixOps

import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging

import akka.actor.ActorSystem
import akka.agent.Agent
import akka.testkit.DefaultTimeout
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.testkit.TestProbe
import net.reliqs.emonlight.xbeeGateway.Config
import net.reliqs.emonlight.xbeeGateway.Factory
import net.reliqs.emonlight.xbeeGateway.QData
import net.reliqs.emonlight.xbeeGateway.Data



class ProcessorActorSpec
    extends TestKit(ActorSystem("ProcessorActorSpec", ConfigFactory.parseString(ProcessorActorSpec.config)))
    with DefaultTimeout with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll {
  import ProcessorActorSpec._
  import scala.concurrent.ExecutionContext.Implicits.global
  
  override def afterAll {
    shutdown()
  }

  val cfg: Config = Factory.read("src/test/resources/lab2.conf")
  val agent = Agent(cfg)
  val dsp = new MyDsp()
  

  "ProcessorActor" should {
    "start correctly" in {
      val p = system.actorOf(ProcessorActor.props(agent, dsp, testActor))
      val dBus = TestProbe()
      cfg.dBus.subscribe(dBus.ref, cfg.probes(0))
      dsp.queueEvent(MyEvent(MyNode(QData(cfg.probes(0), Data(100, 10.1))), 1 second))
      dsp.queueEvent(MyEvent(MyNode(QData(cfg.probes(0), Data(110, 10.2))), 2 second))
      dsp.queueEvent(MyEvent(MyNode(QData(cfg.probes(0), Data(120, 10.3))), 3 second))
      dsp.queueEvent(MyEvent(MyNode(QData(cfg.probes(0), Data(150, 10.4))), 10 second))
      within(2 seconds) {
        expectMsg(ProcessorActor.InitComplete)
      }
      within(30 seconds) {
        p ! ProcessorActor.Process
        dBus.expectMsgPF(30 seconds)({ case q: QData => q }) 
        dBus.expectMsgPF(30 seconds)({ case q: QData => q }) 
        dBus.expectMsgPF(30 seconds)({ case q: QData => q }) 
        dBus.expectMsgPF(30 seconds)({ case q: QData => q }) 
      }
    }
  }

}

object ProcessorActorSpec {
  val config = """
    akka {
      loglevel = "DEBUG"
    }
    """

  class MyDsp extends Dispatcher with EventHandling with LazyLogging {
    def eventHandler: Event.EventHandler = {
      ???
    }
  }
  
  case class MyNode(q: QData) extends NodeEventHandling {
    override def handle: Event.MessageHandler = {
      case _ => Seq(q)
    }
  }
  
  case class MyEvent(override val node: NodeEventHandling, override val time: Duration = Duration.Zero) extends NodeEvent(node) with Delaying {
    
  }
}