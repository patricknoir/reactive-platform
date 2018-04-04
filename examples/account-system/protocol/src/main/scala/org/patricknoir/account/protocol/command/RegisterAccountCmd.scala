package org.patricknoir.account.protocol.command

import java.time.LocalDate

import org.patricknoir.platform.protocol.Command


case class RegisterAccountCmd(
  username: String,
  password: String,
  firstName: String,
  lastName: String,
  dateOfBirth: LocalDate,
  address: AccountAddressCmdPart
) extends Command


case class AccountAddressCmdPart(
  street1: String,
  street2: String,
  zipCode: String,
  city: String,
  country: String
)
