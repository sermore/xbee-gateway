package net.reliqs.emonlight.xbeeGateway

import akka.testkit.TestKit
import akka.testkit.TestProbe
import akka.testkit.ImplicitSender
import akka.actor.Props
import akka.actor.Actor
import org.scalatest.BeforeAndAfterAll
import akka.stream.ActorMaterializer
import akka.testkit.DefaultTimeout
import akka.actor.ActorRef
import org.scalatest.Matchers
import net.reliqs.emonlight.xbeeGateway.send.DispatcherProvider
import akka.agent.Agent
import net.reliqs.emonlight.xbeeGateway.send.Dispatcher
import org.scalatest.WordSpecLike
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import java.sql.Wrapper
import akka.actor.ActorLogging
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

class AgentTest extends TestKit(ActorSystem("AgentTest", ConfigFactory.parseString(AgentTest.config)))
    with DefaultTimeout with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll {
  import AgentTest._
  import scala.concurrent.ExecutionContext.Implicits.global

  override def afterAll {
    shutdown()
  }

  val cfg: Config = Factory.read("src/test/resources/test.conf")
  assert(cfg.validate.isEmpty())

  //  implicit val system = ActorSystem("my-system")
  implicit val materializer = ActorMaterializer()

  class MyActor(cfg: Agent[Config]) extends Actor with ActorLogging {
    def receive = {
      case "change" =>
        for (i <- 1 to 10) {
//          Thread.sleep(100)
          cfg.send(c => {
            val n = c.nodes.head
            n.lines.head.addProbe(Probe("PX", ProbeType.Sample))
            log.debug(s"change u=${n.lines.head.probes.length}")
            c
          })
        }
      case "u" =>
        val u = cfg().nodes.head.lines.head.probes.length
        log.debug(s"u=$u")
        sender() ! u
    }
  }

  val a1 = system.actorOf(Props(new MyActor(Config.cfg)))
  val a2 = system.actorOf(Props(new MyActor(Config.cfg)))
  val agent = Config.cfg

  "agent" should {
    "handle changes" in {
      assert(agent().nodes(0).lines(0).probes.length === 1)
      agent.send(c => { c.nodes(0).lines(0).addProbe(Probe("PX", ProbeType.Sample)); c })
      val c = Await.result(agent.future, 1 second)
      val l = c.nodes(0).lines(0) 
      assert(l.probes.length === 2)
      val p = cfg.nodes.head.lines.head.probes.last
      agent.send(c => { c.nodes(0).lines(0).removeProbe(p); c })
      val c1 = Await.result(agent.future, 1 second)      
      assert(c1.nodes.head.lines.head.probes.length === 1)
      assert(l eq c1.nodes.head.lines.head)
    }
    "handle concurrent changes" in {
      within(2 second) {
        agent.send(c => { c.nodes.head.lines.head.probes = List.empty; c })
        a1 ! "change"
        Thread.sleep(500)
        val c = Await.result(agent.future, 2 second)
        assert(c.nodes.head.lines.head.probes.length == 10)
        a1 ! "u"
        expectMsg(10)
        expectNoMsg
      }
      within(6 seconds) {
        a2 ! "change"
        a1 ! "change"
        a2 ! "change"
        Thread.sleep(1000)
        a1 ! "u"
        expectMsg(40)
      }
    }
    "reuse same Probe instances in QData objects" in {
      //      val c: Config = Factory.read("src/test/resources/simple.conf")
      val c = Config.cfg()
      val q1 = QData(c.probes(0), Data(1, 100))
      Config.cfg.send(cx => { cx.nodes(0).lines(0).addProbe(Probe("PX", ProbeType.Sample)); cx })
      Await.result(Config.cfg.future, 1 second)
      val q2 = QData(Config.cfg().probes(0), Data(2, 100))
      assert(q1.probe.eq(q2.probe))
      //      assert(q1.probe.uptimeOffsetInMillis == 2)
      //      assert(q2.probe.uptimeOffsetInMillis == 2)
    }
  }
}

object AgentTest {
  // Define your test specific configuration here
  val config = """
    akka {
      loglevel = "DEBUG"
    }
    """

  //case class AgentCfg(cfg: Config) {
  //  import scala.concurrent.ExecutionContext.Implicits.global
  //
  //  val agent = Agent(cfg)
  //  
  //  def apply() = agent.apply()
  //  
  //  def nodes() = agent().nodes
  //}
}