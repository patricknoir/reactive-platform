package org.patricknoir.betting.domain

case class Bet(id: String, event: String, stake: Double)
case class Account(id: String, bets: Seq[Bet] = Seq.empty[Bet])

