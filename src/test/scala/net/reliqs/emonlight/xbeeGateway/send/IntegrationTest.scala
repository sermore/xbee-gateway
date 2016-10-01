package net.reliqs.emonlight.xbeeGateway.send

import scala.concurrent.duration._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import com.typesafe.config.ConfigFactory
import akka.NotUsed
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.testkit.DefaultTimeout
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.stream.ActorMaterializer
import net.reliqs.emonlight.xbeeGateway.WebServerActor
import akka.actor.Props
import net.reliqs.emonlight.xbeeGateway.ServerData
import net.reliqs.emonlight.xbeeGateway.QData
import net.reliqs.emonlight.xbeeGateway.NodeData
import net.reliqs.emonlight.xbeeGateway.Factory
import net.reliqs.emonlight.xbeeGateway.Data
import akka.agent.Agent
import net.reliqs.emonlight.xbeeGateway.Config
import scala.language.postfixOps

class IntegrationTest
    extends TestKit(ActorSystem("IntegrationTest", ConfigFactory.parseString(IntegrationTest.config)))
    with DefaultTimeout with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll {
  import IntegrationTest._
  import scala.concurrent.ExecutionContext.Implicits.global

  val webServer = system.actorOf(Props(new WebServerActor))

  override def beforeAll {
    webServer ! "start"
  }
  override def afterAll {
    webServer ! "stop"
    shutdown()
  }

  val cfg: Config = Factory.read("src/test/resources/test.conf")
  assert(cfg.validate.isEmpty())

  //  implicit val system = ActorSystem("my-system")
  implicit val materializer = ActorMaterializer()

  val agent = Agent(cfg)
  val dBus = agent().dBus
  val ds = cfg.servers.map(s => system.actorOf(HttpServerDispatcher.parentProps(agent, s.name), s.name))
  webServer ! ("notify", testActor)
  ds.foreach { r => r ! Dispatcher.Enable }
//  cfg.nodes.foreach(_.uptimeOffsetInMillis = 1)

  "A Dispatcher system" should {
    "send data to server s1 when receiving QData(p1) from dBus" in {
      dBus.publish(QData(cfg.probes(0), Data(100L, 100)))
      expectMsg(2 seconds, ("OK", ServerData(List(NodeData("dcTRYGK7RB1kqxW2vE-Q", 2, List((0, 101000000, 100.0)))))))
    }
  }

}

object IntegrationTest {
  // Define your test specific configuration here
  val config = """
    akka {
      loglevel = "DEBUG"
    }
    """
}
