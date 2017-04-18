package org.patricknoir.wallet.client

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.Timeout
import org.patricknoir.kafka.reactive.client.ReactiveKafkaClient
import org.patricknoir.kafka.reactive.client.config.KafkaReactiveClientConfig
import org.patricknoir.wallet.protocol.command.WalletCreateCmd
import io.circe.generic.auto._

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Created by patrick on 18/04/2017.
  */
object SimpleClient extends App {

  implicit val system = ActorSystem("platformClient")
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(100 seconds)

  val client = new ReactiveKafkaClient(KafkaReactiveClientConfig.default())

  val createWallet = WalletCreateCmd("1", 100, true)

  val sendConfirm = client.send("kafka:walletSystem_1.0.0_commands/walletCreateCmd", createWallet, true)

  Await.ready(sendConfirm, Duration.Inf)

}
