package org.patricknoir.wallet.protocol.event

import org.patricknoir.platform.protocol.Event

case class DebitedEvt(
  id: String,
  amount: BigDecimal
) extends Event
