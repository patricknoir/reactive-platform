package org.patricknoir.wallet.domain

/**
  * Created by patrick on 30/03/2017.
  */
case class Wallet(
  id: String,
  amount: BigDecimal,
  active: Boolean
) {
  def debit(value: BigDecimal) = this.copy(amount = this.amount - value)
  def credit(value: BigDecimal) = debit(-value)
}
