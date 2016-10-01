package net.reliqs.emonlight.xbeeGateway.scalatest.my

import akka.testkit.TestKit
import akka.testkit.ImplicitSender
import akka.actor.Props
import akka.actor.ActorLogging
import akka.actor.Actor
import org.scalatest.BeforeAndAfterAll
import akka.testkit.DefaultTimeout
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

class Test extends TestKit(ActorSystem("Test", ConfigFactory.parseString(Test.config)))
    with DefaultTimeout with ImplicitSender
    with WordSpecLike with Matchers with BeforeAndAfterAll {
  import Test._
  import scala.concurrent.ExecutionContext.Implicits.global

  override def afterAll {
    shutdown()
  }

  "An actor" should {
    "actor test" in {
      val a = system.actorOf(Props[TActor])
      a ! "a"
      a ! "b"
      a ! "c"
    }

  }
}

object Test {
  // Define your test specific configuration here
  val config = """
    akka {
      loglevel = "DEBUG"
    }
    """
  class TActor extends Actor with ActorLogging {
    def receive() = {
      case "a" =>
        log.debug("a")
        context.become(afterA)
      case "b" =>
        log.debug("b")
    }
    override def unhandled(message: Any) = {
      log.debug(s"u $message")
    }

    def afterA: Receive = {
      case "c" =>
        log.debug("c")
        context.unbecome()
    }
  }

}
