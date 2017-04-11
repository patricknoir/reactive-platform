package org.patricknoir.wallet

import org.patricknoir.platform.runtime.Platform
import org.patricknoir.platform.{BoundedContext, Version}
import org.patricknoir.wallet.processor.walletProcessor

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

object WalletService extends App {

  val walletBoundedContext = BoundedContext(
    id = "walletSystem",
    version = Version(1, 0, 0),
    componentDefs = Set(walletProcessor)
  )

  val runtime = Platform.install(walletBoundedContext)

  Await.ready(runtime, Duration.Inf)
}
