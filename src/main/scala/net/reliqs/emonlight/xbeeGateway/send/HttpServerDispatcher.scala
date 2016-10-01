package net.reliqs.emonlight.xbeeGateway.send

import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.actor.actorRef2Scala
import akka.agent.Agent
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri.apply
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import akka.util.ByteString
import net.reliqs.emonlight.xbeeGateway.Config
import net.reliqs.emonlight.xbeeGateway.ServerData
import net.reliqs.emonlight.xbeeGateway.ServerJsonSupport
import spray.json.pimpAny
import net.reliqs.emonlight.xbeeGateway.Factory

trait HttpDispatcherProvider extends DispatcherProvider {
  override def provide(parent: ActorRef, cfg: Agent[Config], name: String): Actor = {
    new HttpServerDispatcher(parent, cfg, name)
  }
}

object HttpServerDispatcher {

  def props(parent: ActorRef, cfg: Agent[Config], name: String): Props =
    Props(new HttpServerDispatcher(parent, cfg, name))
  def parentProps(cfg: Agent[Config], name: String): Props =
    Props(new Dispatcher(cfg, name, true) with HttpDispatcherProvider)

}

class HttpServerDispatcher(parent: ActorRef, cfg: Agent[Config], name: String) extends Actor
    with ActorLogging with ServerJsonSupport {
  import context.dispatcher

  val server = cfg().servers.find(_.name == name).get
  final implicit val materializer: ActorMaterializer = ActorMaterializer()
  //FIXME share the pool between actors
  val http = Http(context.system)

  override def preStart {
    log.info("start " + server)
  }

  def receive = {
    case sd: ServerData =>
      sendData(sd)
      context.become(waitForHttpResponse)
      // http client timeout configuration: akka.http.client.connecting-timeout
      context.setReceiveTimeout(server.sendTimeout milliseconds)
    //    case v => log.warning(s"received $v")
  }

  def waitForHttpResponse: Receive = {
    case HttpResponse(StatusCodes.OK, headers, entity, _) =>
      val fs = entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map(_.utf8String)
      context.become(waitForEntityContent)
      context.setReceiveTimeout(server.sendTimeout / 2 milliseconds)
      fs.pipeTo(self)
      log.debug("wait for entity")
    //      var res = Await.result(fs, 3 seconds)
    //      val res = entity.toStrict(3 seconds).map(_.data).map(_.utf8String).value.get.get
    case HttpResponse(code, _, _, _) =>
      log.warning("Request failed, response code: " + code)
      complete(false)
    case ReceiveTimeout =>
      log.warning("Got Timeout for response")
      complete(false)
  }

  def waitForEntityContent: Receive = {
    case "OK" =>
      complete(true)
    case res: String =>
      log.warning("Got wrong response, body: " + res)
      complete(false)
    case ReceiveTimeout =>
      log.warning("Got Timeout for response body")
      complete(false)
  }

  def complete(success: Boolean) {
    parent ! (if (success) Dispatcher.Ack else Dispatcher.Fail)
    context.unbecome()
    context.setReceiveTimeout(Duration.Undefined)
  }

  def sendData(data: ServerData) = {
    http.singleRequest(HttpRequest(uri = server.url, method = HttpMethods.POST,
      entity = HttpEntity(ContentTypes.`application/json`, Factory.stdMapper.writeValueAsString(data)))).pipeTo(self)
  }
}
