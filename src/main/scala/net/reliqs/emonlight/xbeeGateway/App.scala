package net.reliqs.emonlight.xbeeGateway

import akka.actor.ActorSystem
import scala.io.StdIn
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.Await
import akka.actor.ActorRef

object MainApp extends App {
  if (args.length == 1) {
    val cfg: Config = Factory.read(args(0))
    println("read config from " + args(0))
    val v = cfg.validate()
    if (v.isEmpty()) {
      println("run..")
      val system = ActorSystem("gw")
      val master = system.actorOf(Master.props(cfg), "master")
      addShutdownHook(system, master)
    } else println(v)
  } else {
    println("usage: <app> <config-file>")
  }

  def addShutdownHook(system: ActorSystem, master: ActorRef) {
    import scala.concurrent.ExecutionContext.Implicits.global
    sys.addShutdownHook {
      system.log.warning(s"Shutting down..")
      master ! Master.Kill
      Await.result(system.whenTerminated, 10 seconds)
    }
  }
}
