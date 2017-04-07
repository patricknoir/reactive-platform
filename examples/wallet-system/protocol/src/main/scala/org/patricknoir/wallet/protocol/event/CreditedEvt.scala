package org.patricknoir.wallet.protocol.event

import org.patricknoir.platform.protocol.Event

case class CreditedEvt(
  id: String,
  amount: BigDecimal
) extends Event
