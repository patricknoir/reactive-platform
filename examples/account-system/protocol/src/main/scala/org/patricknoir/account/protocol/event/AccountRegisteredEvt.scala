package org.patricknoir.account.protocol.event

import io.netty.channel.local.LocalAddress
import org.patricknoir.platform.protocol.Event

case class AccountRegisteredEvt(
  username: String,
  firstName: String,
  lastName: String,
  dateOfBirth: LocalAddress
) extends Event
