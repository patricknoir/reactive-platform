package org.patricknoir.wallet.client

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.patricknoir.kafka.reactive.client.ReactiveKafkaClient
import org.patricknoir.kafka.reactive.client.config.KafkaReactiveClientConfig
import org.patricknoir.wallet.protocol.command.WalletCreateCmd
import org.patricknoir.wallet.protocol.request.GetBalanceReq
import org.patricknoir.wallet.protocol.response.GetBalanceRes
import io.circe.generic.auto._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.io.StdIn

/**
  * Created by patrick on 18/04/2017.
  */
object SimpleClient extends App {

  implicit val system = ActorSystem("platformClient")
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(10 seconds)

  val client = new ReactiveKafkaClient(KafkaReactiveClientConfig.default())

  if(StdIn.readLine("Press [enter] to create a wallet, type: skip + [enter] if you want to skip this step: ").toLowerCase.trim != "skip") {

    val createWallet = WalletCreateCmd("1", 100, true)

    println(s"Sending command: $createWallet")
    val sendConfirm: Future[Unit] = client.send("kafka:walletSystem_1.0.0_commands/walletCreateCmd", createWallet, confirmSend = true)
    Await.ready(sendConfirm, Duration.Inf)
    println(s"Command sent confirmation: $sendConfirm")

  }

  println("Press [enter] to continue")
  StdIn.readLine()

  println("Retrieving balance for walletId = 1")
  val req = GetBalanceReq("1")
  val fResp: Future[GetBalanceRes] = client.request[GetBalanceReq, GetBalanceRes]("kafka:walletSystem_1.0.0_requests/getBalanceReq", req)
  val resp = Await.result(fResp, Duration.Inf)
  println(s"Balance is: $resp")


  println("System shutting down")
  Await.ready(system.terminate(), Duration.Inf)
  println("System terminated")
}
