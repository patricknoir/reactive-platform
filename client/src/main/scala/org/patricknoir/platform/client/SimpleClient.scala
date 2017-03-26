package org.patricknoir.platform.client

import akka.actor.ActorSystem
import akka.util.Timeout
import org.patricknoir.kafka.reactive.client.config.KafkaRClientSettings
import org.patricknoir.kafka.reactive.client.{KafkaReactiveClient, ReactiveClient}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import io.circe.generic.auto._

/**
  * Created by patrick on 26/03/2017.
  */
object SimpleClient extends App {

  import org.patricknoir.platform.Util._

  implicit val system = ActorSystem("ReactiveClient")
  implicit val timeout = Timeout(5 seconds)

  import system.dispatcher

  val client = new KafkaReactiveClient(KafkaRClientSettings.default)

  val fResp: Future[CounterValueResp] = client.request[CounterValueReq, CounterValueResp]("kafka:counterBC_1.0.0_requests/counterValueReq", CounterValueReq("Counter1"))

  val response = Await.result(fResp, Duration.Inf)

  prettyPrint(response)

  Await.ready(system.terminate(), Duration.Inf)
  println("system terminated")

  private def prettyPrint(resp: CounterValueResp) = {
    import io.circe.syntax._
    resp.asJson.pretty(io.circe.Printer.spaces2)
  }
}
