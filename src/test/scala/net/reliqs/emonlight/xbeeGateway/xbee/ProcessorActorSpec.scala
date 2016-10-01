package net.reliqs.emonlight.xbeeGateway.xbee

import scala.concurrent.duration._
import scala.language.postfixOps

import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

import com.typesafe.config.ConfigFactory

import akka.actor.ActorSystem
import akka.agent.Agent
import akka.testkit.DefaultTimeout
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import net.reliqs.emonlight.xbeeGateway.Config
import net.reliqs.emonlight.xbeeGateway.Factory
import akka.actor.Props
import akka.testkit.TestProbe
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

  "ProcessorActor" should {
    "start correctly" in {
      val p = system.actorOf(ProcessorActor.props(agent, testActor))
      val dBus = TestProbe()
      cfg.dBus.subscribe(dBus.ref, cfg.probes(0))
      within(2 seconds) {
        expectMsg(ProcessorActor.InitComplete)
      }
      within(30 seconds) {
        p ! ProcessorActor.Process
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

}