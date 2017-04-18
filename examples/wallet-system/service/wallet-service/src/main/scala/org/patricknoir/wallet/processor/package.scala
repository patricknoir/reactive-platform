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
  * Created by patrick on 30/03/2017.
  */
package object processor {

  val createWalletCmd: CmdInfo[Option[Wallet]] = command("walletCreateCmd") { (optWallet: Option[Wallet], cmd: WalletCreateCmd) =>
    (Option(Wallet(cmd.id, cmd.amount, cmd.active)), Seq(WalletCreatedEvt(cmd.id, cmd.amount, cmd.active)))
  }

  val debitWalletCmd: CmdInfo[Option[Wallet]] = command("debitCmd") { (optWallet: Option[Wallet], cmd: DebitCmd) =>
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

  val creditWalletReducer: CmdInfo[Option[Wallet]] = command("creditCmd") { (optWallet: Option[Wallet], cmd: CreditCmd) =>
    optWallet.fold(
      throw new RuntimeException(s"Wallet ${cmd.id} is not active")
    )
    { wallet =>
      if(!wallet.active) throw new RuntimeException(s"Wallet ${wallet.id} is not active")
      val newWallet = wallet.credit(cmd.amount)
      (Option(newWallet), Seq(CreditedEvt(cmd.id, cmd.amount)))
    }
  }

  val getBalanceReq: AskInfo[Option[Wallet]] = request("getBalanceReq") { (optWallet: Option[Wallet], req: GetBalanceReq) =>
    GetBalanceRes(req.walletId, optWallet.map(_.amount))
  }

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

  val walletProcessorDef = ProcessorDef[Option[Wallet]](
    id = "walletProcessor",
    version = Version(1, 0, 0),
    descriptor = shardingDescriptor,
    model = None,
    propsFactory = _ => ProcessorProps[Option[Wallet]](
      commandModifiers = Set(
        createWalletCmd,
        debitWalletCmd,
        createWalletCmd
      ),
      queries = Set(getBalanceReq)
    )

  )


}
