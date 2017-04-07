package org.patricknoir.wallet.protocol.command

import org.patricknoir.platform.protocol.Command

case class WalletCreateCmd(
  id: String,
  amount: BigDecimal,
  active: Boolean
) extends Command
