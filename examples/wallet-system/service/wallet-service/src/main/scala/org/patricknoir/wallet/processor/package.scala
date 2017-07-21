package org.patricknoir.wallet

import org.patricknoir.platform._
import org.patricknoir.platform.dsl._
import org.patricknoir.wallet.domain.Wallet
import org.patricknoir.wallet.protocol.command.{CreditCmd, DebitCmd, WalletCreateCmd}
import org.patricknoir.wallet.protocol.event.{CreditedEvt, DebitedEvt, WalletCreatedEvt}
import io.circe.generic.auto._
import org.patricknoir.wallet.protocol.request.GetBalanceReq
import org.patricknoir.wallet.protocol.response.GetBalanceRes

/**
  * Processors modifiers and queries
  */
package object processor {

  val createWalletCmd: CtxCmdInfo[Option[Wallet]] = command("walletCreateCmd") { (_: ComponentContext, optWallet: Option[Wallet], cmd: WalletCreateCmd) =>
    (Option(Wallet(cmd.id, cmd.amount, cmd.active)), Seq(WalletCreatedEvt(cmd.id, cmd.amount, cmd.active)))
  }

  val debitWalletCmd: CtxCmdInfo[Option[Wallet]] = command("debitCmd") { (_: ComponentContext, optWallet: Option[Wallet], cmd: DebitCmd) =>
    optWallet.fold(
      throw new RuntimeException(s"Wallet ${cmd.id} does not exist")
    )
    { wallet =>
      if (!wallet.active) throw new RuntimeException(s"Wallet ${wallet.id} is not active")
      val newWallet = wallet.debit(cmd.amount)
      if (newWallet.amount < 0) throw new RuntimeException(s"Insufficient balance for debit amount: ${cmd.amount}")
      (Option(newWallet), Seq(DebitedEvt(cmd.id, cmd.amount)))
    }
  }

  val creditWalletCmd: CtxCmdInfo[Option[Wallet]] = command("creditCmd") { (_: ComponentContext, optWallet: Option[Wallet], cmd: CreditCmd) =>
    optWallet.fold(
      throw new RuntimeException(s"Wallet ${cmd.id} is not active")
    )
    { wallet =>
      if(!wallet.active) throw new RuntimeException(s"Wallet ${wallet.id} is not active")
      val newWallet = wallet.credit(cmd.amount)
      (Option(newWallet), Seq(CreditedEvt(cmd.id, cmd.amount)))
    }
  }

  val getBalanceReq: CtxAskInfo[Option[Wallet]] = request("getBalanceReq") { (_: ComponentContext, optWallet: Option[Wallet], req: GetBalanceReq) =>
    GetBalanceRes(req.walletId, optWallet.map(_.amount))
  }

  // === Processor Descriptor === //

  val shardingDescriptor = KeyShardedProcessDescriptor(
    commandKeyExtractor = {
      case cmd @ WalletCreateCmd(id, _, _) => (id, cmd)
      case cmd @ DebitCmd(id, _) => (id, cmd)
      case cmd @ CreditCmd(id, _) => (id, cmd)
    },
    eventKeyExtractor = PartialFunction.empty,
    queryKeyExtractor = {
      case req @ GetBalanceReq(id) => (id, req)
    },
    dependencies = Set.empty,
    hashFunction = _.hashCode,
    shardSpaceSize = 100
  )

  // === Processor Definiiton === //

  val walletProcessor = Processor[Option[Wallet]](
    id = "walletProcessor",
    version = Version(1, 0, 0),
    descriptor = shardingDescriptor,
    model = None,
    commandModifiers = Set(
      createWalletCmd,
      debitWalletCmd,
      creditWalletCmd
    ),
    queries = Set(getBalanceReq)
  )


}
