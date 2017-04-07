package org.patricknoir.wallet.protocol.command

import org.patricknoir.platform.protocol.Command

case class DebitCmd(
  id: String,
  amount: BigDecimal
) extends Command
