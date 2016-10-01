package net.reliqs.emonlight.xbeeGateway

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import scala.io.StdIn
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.Failure
import scala.util.Success
import akka.stream.scaladsl.Sink
import scala.util.Try
import akka.stream.scaladsl.Source
import akka.stream.Attributes
import akka.event.Logging
import akka.util.ByteString
import akka.actor.Actor
import akka.actor.ActorRef

class WebServerActor extends Actor with ServerJsonSupport {

  implicit val system = ActorSystem("my-system")
  implicit val materializer = ActorMaterializer()
  // needed for the future flatMap/onComplete in the end
  implicit val executionContext = system.dispatcher
  var bindingFuture: Option[Future[Http.ServerBinding]] = None
  var resp: String = "OK"
  var sleepTime: Int = 0
  var ref: ActorRef = ActorRef.noSender

  def receive = {
    case "start"     => start()
    case "stop"      => stop()
    case "OK"        => resp = "OK"
    case ("sleep", time: Int) => sleepTime = time
    case ("notify", r: ActorRef) => ref = r
    case res: String => resp = res    
  }

  def start() {
    val route =
      path("input" / "read.json") {
        post {
          entity(as[ServerData]) { s =>            
            println(s"$s");
            if (sleepTime > 0) {
              Thread.sleep(sleepTime)
              sleepTime = 0
            }
            if (ref != ActorRef.noSender) ref ! (resp, s)
            complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, ByteString(resp)))
          }
        }
      }

    bindingFuture = Some(Http().bindAndHandle(route, "localhost", 8080))
    //    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    //    StdIn.readLine() // let it run until user presses return
  }

  def stop() {
    bindingFuture.get
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done    
  }
}
