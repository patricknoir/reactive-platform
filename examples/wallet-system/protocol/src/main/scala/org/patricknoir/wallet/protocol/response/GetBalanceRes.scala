package org.patricknoir.wallet.protocol.response

import org.patricknoir.platform.protocol.Response

/**
  * Created by patrick on 31/03/2017.
  */
case class GetBalanceRes(walletId: String, balance: Option[BigDecimal]) extends Response
