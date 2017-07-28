package org.patricknoir.betting.protocol.command

case class BetPlaceCmd(id: String, customerId: String, event: String, stake: Double)