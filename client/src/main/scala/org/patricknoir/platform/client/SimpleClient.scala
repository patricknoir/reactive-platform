package org.patricknoir.platform.client

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.patricknoir.kafka.reactive.client.config.KafkaReactiveClientConfig
import org.patricknoir.kafka.reactive.client.ReactiveKafkaClient

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import io.circe.generic.auto._

import scala.io.StdIn
import scala.util.Try

/**
  * Created by patrick on 26/03/2017.
  */
object SimpleClient extends App {

  import org.patricknoir.platform.Util._

  implicit val system = ActorSystem("platformClient")
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(100 seconds)

  import system.dispatcher

  val client = new ReactiveKafkaClient(KafkaReactiveClientConfig.default())

  var input = ""

  while(input != "exit") {
    val cmd = IncrementCounterIfCmd("Counter1", 1, 0)
    val cResp = client.request[IncrementCounterIfCmd, CounterIncrementedEvt]("kafka:counterBC_1.0.0_commands/incrementIfCmd", cmd)
    println("Response is: " + Try(Await.result(cResp, Duration.Inf)))

    val req = CounterValueReq("Counter1")
    println(s"Sending request: $req")
    val fResp: Future[CounterValueResp] = client.request[CounterValueReq, CounterValueResp]("kafka:counterBC_1.0.0_requests/counterValueReq", req)

    val response = Try(Await.result(fResp, Duration.Inf))
    println("Response is: " + response)
    println("Press Enter to send another request or \"exit\" to terminate")
    input = StdIn.readLine()
  }

  Await.ready(system.terminate(), Duration.Inf)
  println("system terminated")

  private def prettyPrint(resp: CounterValueResp) = {
    import io.circe.syntax._
    resp.asJson.pretty(io.circe.Printer.spaces2)
  }

}
