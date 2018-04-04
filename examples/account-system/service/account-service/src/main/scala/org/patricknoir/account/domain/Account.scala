package org.patricknoir.account.domain

import java.time.LocalDate

case class Account(
  username: String,
  password: String,
  firstName: String,
  dateOfBirth: LocalDate,
  address: Address,
  status: String //Active, Suspended etc... TOBE modelled
)

case class Address(
  street1: String,
  street2: String,
  zipCode: String,
  city: String,
  country: String
)

case class AccountSession(
  token: String,
  started: LocalDate,
  username: String
)
