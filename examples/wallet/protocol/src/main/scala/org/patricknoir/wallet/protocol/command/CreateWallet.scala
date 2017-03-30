package org.patricknoir.wallet.protocol.command

case class CreateWalletCmd(
  id: String,
  amount: BigDecimal
)
