package net.reliqs.emonlight.xbeeGateway.send

import scala.concurrent.duration._
import scala.language.postfixOps

import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

import com.typesafe.config.ConfigFactory

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.actor.actorRef2Scala
import akka.agent.Agent
import akka.stream.ActorMaterializer
import akka.testkit.DefaultTimeout
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.testkit.TestProbe
import net.reliqs.emonlight.xbeeGateway.Config
import net.reliqs.emonlight.xbeeGateway.Data
import net.reliqs.emonlight.xbeeGateway.Factory
import net.reliqs.emonlight.xbeeGateway.Master
import net.reliqs.emonlight.xbeeGateway.NodeData
import net.reliqs.emonlight.xbeeGateway.QData
import net.reliqs.emonlight.xbeeGateway.Server
import net.reliqs.emonlight.xbeeGateway.ServerData

class DispatcherSpec
    extends TestKit(ActorSystem("DispatcherSpec", ConfigFactory.parseString(DispatcherSpec.config)))
    with DefaultTimeout with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll {
  import DispatcherSpec._
  import scala.concurrent.ExecutionContext.Implicits.global

  override def afterAll {
    shutdown()
  }

  val cfg: Config = Factory.read("src/test/resources/test.conf")
  assert(cfg.validate().isEmpty())

  //  implicit val system = ActorSystem("my-system")
  implicit val materializer = ActorMaterializer()

  class Wrapper(target: ActorRef) extends Actor {
    def receive = {
      case x =>
        println(x)
        target forward x
    }
  }

  val agent = Agent(cfg)
  val dBus = agent().dBus
  val tmap = cfg.servers.map(s => s -> TestProbe()).toMap
  val tpl = tmap.map(el => el._2).toList
  trait TestProvider extends DispatcherProvider {
    override def provide(parent: ActorRef, cfg: Agent[Config], name: String): Actor = {
      new Wrapper(tmap(agent().servers.find(_.name == name).get).ref)
    }
  }

  def startDispatchers = cfg.servers.map(s => system.actorOf(Props(new Dispatcher(agent, s.name, false) with TestProvider)))
  val refs = startDispatchers

  "Dispatcher" should {
    "have initial state as Disabled" in {
      assert(tmap.size == 3)
      refs.foreach { _ ! Dispatcher.GetState }
      expectMsgAllOf(400 millis, DState.Disabled, DState.Disabled, DState.Disabled)
    }
    "do not send data when state is disabled" in {
      dBus.publish(QData(cfg.probes(0), Data(300L, 1.2)))
      Thread.sleep(2000)
      tpl.foreach { _.expectNoMsg(0 seconds) }
      refs.foreach { r => r ! Dispatcher.Enable }
      tpl(0).expectMsg(2 seconds, ServerData(List(NodeData("dcTRYGK7RB1kqxW2vE-Q", 2, List((0, 300000000, 1.2))))))
      refs(0) ! Dispatcher.Ack
      Thread.sleep(2000)
      tpl.foreach { _.expectNoMsg(0 seconds) }
    }
    "do not send data when a ServerData is in flight, send it after receiving Ack" in {
      dBus.publish(QData(cfg.probes(0), Data(400L, 1.2)))
      Thread.sleep(2000)
      dBus.publish(QData(cfg.probes(0), Data(500L, 1.2)))
      tpl(0).expectMsg(2 seconds, ServerData(List(NodeData("dcTRYGK7RB1kqxW2vE-Q", 2, List((0, 400000000, 1.2))))))
      tpl.foreach { _.expectNoMsg(0 seconds) }
      refs(0) ! Dispatcher.Ack
      tpl(0).expectMsg(2 seconds, ServerData(List(NodeData("dcTRYGK7RB1kqxW2vE-Q", 2, List((0, 500000000, 1.2))))))
      refs(0) ! Dispatcher.Ack
      Thread.sleep(2000)
      tpl.foreach { _.expectNoMsg(0 seconds) }
    }
    "dispatch QData msgs to dispatcher actors based on probe instances" in {
      for (i <- 0 to cfg.probes.length - 1) dBus.publish(QData(cfg.probes(i), Data(600L + 100 * i, 1.2)))
      val q1 = ServerData(List(NodeData("dcTRYGK7RB1kqxW2vE-Q", 2, List((0, 600000000, 1.2))), NodeData("sL_hcL72QmsZ-FjuRzoE", 6, List((0, 700000000, 1.2)))))
      val q2 = ServerData(List(NodeData("sL_hcL72QmsZ-FjuRzoE", 6, List((0, 700000000, 1.2))), NodeData("xxvqsr2YSxACHbCxTzB6", 7, List((0, 800000000, 1.2)))))
      val q3 = ServerData(List(NodeData("xxvqsr2YSxACHbCxTzB6", 7, List((0, 800000000, 1.2))), NodeData("a7LiZVht-FNo3i8bUf61", 5, List((0, 900000000, 1.2)))))
      tpl(0).expectMsg(1500 millis, q1)
      tpl(1).expectMsg(1500 millis, q2)
      tpl(2).expectMsg(1500 millis, q3)
      refs.foreach { _ ! Dispatcher.Ack }
      tpl.foreach { _.expectNoMsg(2 seconds) }
    }
    "retry after a failure" in {
      dBus.publish(QData(cfg.probes(0), Data(1000L, 300)))
      tpl(0).expectMsg(2 seconds, ServerData(List(NodeData("dcTRYGK7RB1kqxW2vE-Q", 2, List((1, 0, 300.0))))))
      refs(0) ! Dispatcher.Fail
      tpl(0).expectMsg(2 seconds, ServerData(List(NodeData("dcTRYGK7RB1kqxW2vE-Q", 2, List((1, 0, 300.0))))))
      refs(0) ! Dispatcher.Fail
      tpl(0).expectMsg(2 seconds, ServerData(List(NodeData("dcTRYGK7RB1kqxW2vE-Q", 2, List((1, 0, 300.0))))))
      refs(0) ! Dispatcher.Ack
      Thread.sleep(2000)
      tpl.foreach { _.expectNoMsg(0 seconds) }
    }
    "handle failures" in {
      within(3 seconds) {
        refs(0) ! Dispatcher.Enable
        dBus.publish(QData(cfg.probes(0), Data(1100L, 300)))
        Thread.sleep(2000)
        refs(0) ! Dispatcher.GetState
        expectMsg(DState.Sending)
        refs(0) ! new ArithmeticException
        refs(0) ! Dispatcher.GetState
        expectMsg(DState.Ready)
      }
    }
    "have a graceful shutdown" in {
      refs(0) ! Master.Kill
      //      tpl(0).expectMsg(Master.Kill)
      //      refs(0) ! Master.KillCompleted
      expectMsg(Master.KillCompleted)
      system.stop(refs(0))
      refs(1) ! Master.Kill
      refs(2) ! Master.Kill
    }
  }
}

object DispatcherSpec {
  // Define your test specific configuration here
  val config = """
    akka {
      loglevel = "DEBUG"
    }
    """

  class TestServerDispatcher(parent: ActorRef, server: Server) extends Actor with ActorLogging {

    var busyTime: Int = 0

    override def preStart {
      log.debug(s"start $server")
    }

    def receive = {
      case sd: ServerData =>
        log.debug(s"$server busyTime $busyTime $sd")
        if (busyTime > 0) {
          context.become(busy)
          context.setReceiveTimeout(busyTime milliseconds)
          busyTime = 0
        }
      case t: Int =>
        busyTime = t
        log.debug(s"$server busy time $t")
      case v => log.warning(s"received $v")
    }

    def busy: Receive = {
      case ReceiveTimeout =>
        log.warning(s"$server busy time completed")
        context.setReceiveTimeout(Duration.Undefined)
        parent ! Dispatcher.Ack
        context.unbecome()
    }
  }
}
