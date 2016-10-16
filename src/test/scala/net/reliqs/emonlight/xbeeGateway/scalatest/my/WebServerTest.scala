package net.reliqs.emonlight.xbeeGateway.scalatest.my

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import scala.io.StdIn
import com.fasterxml.jackson.databind.ObjectMapper
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
import net.reliqs.emonlight.xbeeGateway.Factory

case class Item(name: String, value: Int)

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val itemFormat = jsonFormat2(Item)
  //implicit val orderFormat = jsonFormat1(Order) // contains List[Item]
}

object WebServerTest extends JsonSupport {
  
  def main(args: Array[String]) {
    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher
    val route =
      path("hello") {
        get {
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
        }
      } ~
        path("hi") {
          get {
            complete(HttpEntity(ContentTypes.`application/json`, Item("yy", 54).toJson.prettyPrint))
          }
        } ~
        path("h") {
          //          get {
          //            val item = Item("prova", 32)
          //            complete(item)
          //          }
          post {
            entity(as[Item]) { item =>
              println(s"$item"); complete(s"Item: ${item.name} - id: ${item.value}")
            }
          }
        }

    val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)
    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }
}

object WebClientTest extends JsonSupport {
  def main(args: Array[String]) {
    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val item = Item("ciccio", 55)
    //    val poolClientFlow = Http().cachedHostConnectionPool[Item]("localhost", 8080).withAttributes(Attributes.logLevels(onElement = Logging.DebugLevel))
    //    val responseFuture: Future[(Try[HttpResponse], Item)] =
    //      source.single(httprequest(uri = "/h", method = httpmethods.post, entity = httpentity.) -> item).map { elem => println(elem); elem }
    //        .via(poolclientflow)
    //        .runwith(sink.head)
//    val data = item.toJson.compactPrint
    val data = Factory.jsonMapper.writeValueAsString(item)
    println(s"$item -> $data")
    val responseFuture: Future[HttpResponse] =
      Http().singleRequest(HttpRequest(uri = "http://localhost:8080/h", method = HttpMethods.POST,
        entity = HttpEntity(ContentTypes.`application/json`, data)))
    //        val res = Await.result(responseFuture, 5 seconds)

    responseFuture.andThen {
      case Success(v) =>
        println(s"request succeded $v")
      case Failure(v) => println("request failed $v")
      case _          => println("ciao")
    }.andThen {
      case _ => println("fine"); system.terminate()
    }

    //    println(res)
  }
}