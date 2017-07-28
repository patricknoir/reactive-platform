package org.patricknoir.betting

import org.patricknoir.betting.domain.Account
import org.patricknoir.betting.protocol.command.BetPlaceCmd
import org.patricknoir.platform.{ComponentContext, CtxCmdInfo, ServiceURL, Version}
import org.patricknoir.platform.dsl._
import org.patricknoir.wallet.protocol.command.DebitCmd
import org.patricknoir.wallet.protocol.event.DebitedEvt

package object processor {

  val placeBetCmd: CtxCmdInfo[Account] = command("placeBetCmd") { (ctx: ComponentContext, account: Account, bpc: BetPlaceCmd) =>
    val debitCmd = DebitCmd(account.id, bpc.stake)
    val fEvt = ctx.request[DebitCmd, DebitedEvt](ServiceURL("bcId", Version(1,0,0), "debitCmd"), debitCmd)
    ???
  }

}
