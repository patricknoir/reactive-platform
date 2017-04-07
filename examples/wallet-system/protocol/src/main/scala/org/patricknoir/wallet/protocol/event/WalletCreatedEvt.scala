package org.patricknoir.wallet.protocol.event

import org.patricknoir.platform.protocol.Event

case class WalletCreatedEvt(
  id: String,
  amount: BigDecimal,
  active: Boolean
) extends Event
