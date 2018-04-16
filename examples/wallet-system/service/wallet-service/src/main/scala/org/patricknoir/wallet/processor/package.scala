package org.patricknoir.wallet

import org.patricknoir.platform.dsl._
import org.patricknoir.wallet.domain.Wallet
import org.patricknoir.wallet.protocol.command.{CreditCmd, DebitCmd, WalletCreateCmd}
import org.patricknoir.wallet.protocol.event.{CreditedEvt, DebitedEvt, WalletCreatedEvt}
import io.circe.generic.auto._
import org.patricknoir.platform._
import org.patricknoir.wallet.protocol.request.GetBalanceReq
import org.patricknoir.wallet.protocol.response.GetBalanceRes

/**
  * Processors modifiers and queries
  */
package object processor {

  val createWalletCmd: CtxCmdInfo[Option[Wallet]] = command { (_: Option[Wallet], cmd: WalletCreateCmd) =>
    (Option(Wallet(cmd.id, cmd.amount, cmd.active)), WalletCreatedEvt(cmd.id, cmd.amount, cmd.active))
  }("walletCreateCmd")

  val debitWalletCmd: CtxCmdInfo[Option[Wallet]] = command{ (optWallet: Option[Wallet], cmd: DebitCmd) =>
    optWallet.fold(
      throw new RuntimeException(s"Wallet ${cmd.id} does not exist")
    )
    { wallet =>
      if (!wallet.active) throw new RuntimeException(s"Wallet ${wallet.id} is not active")
      val newWallet = wallet.debit(cmd.amount)
      if (newWallet.amount < 0) throw new RuntimeException(s"Insufficient balance for debit amount: ${cmd.amount}")
      (Option(newWallet), DebitedEvt(cmd.id, cmd.amount))
    }
  }("debitCmd")

  val creditWalletCmd: CtxCmdInfo[Option[Wallet]] = command { (optWallet: Option[Wallet], cmd: CreditCmd) =>
    optWallet.fold(
      throw new RuntimeException(s"Wallet ${cmd.id} is not active")
    )
    { wallet =>
      if(!wallet.active) throw new RuntimeException(s"Wallet ${wallet.id} is not active")
      val newWallet = wallet.credit(cmd.amount)
      (Option(newWallet), CreditedEvt(cmd.id, cmd.amount))
    }
  }("creditCmd")

  val getBalanceReq: CtxAskInfo[Option[Wallet]] = request { (optWallet: Option[Wallet], req: GetBalanceReq) =>
    GetBalanceRes(req.walletId, optWallet.map(_.amount))
  }("getBalanceReq")

  val recs: Set[Recovery[Option[Wallet]]] = Set(
    recovery { (_: Option[Wallet], evt: WalletCreatedEvt) =>
      Option(Wallet(evt.id, evt.amount, evt.active))
    }("walletCreatedEvtRec"),
    recovery { (optWallet: Option[Wallet], evt: DebitedEvt) =>
      optWallet.map(_.debit(evt.amount))
    }("debitEvtRec"),
    recovery { (optWallet: Option[Wallet], evt: CreditedEvt) =>
      optWallet.map(_.credit(evt.amount))
    }("creditEvtRec")
  )

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

  // === Processor Definition === //

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
    queries = Set(getBalanceReq),
    recover = recs
  )


}
