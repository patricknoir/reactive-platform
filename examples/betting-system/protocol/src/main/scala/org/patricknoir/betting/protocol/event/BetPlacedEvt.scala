package org.patricknoir.betting.protocol.event

case class BetPlacedEvt(id: String, customerId: String, event: String, stake: Double, placed: Boolean)
