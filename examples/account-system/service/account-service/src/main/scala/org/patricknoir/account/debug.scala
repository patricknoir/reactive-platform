package org.patricknoir.account

import cats.data.State
import org.patricknoir.platform.ComponentContext

import org.patricknoir.platform.protocol.{Command, Event, Request, Response}

import org.patricknoir.wallet.protocol.command.WalletCreateCmd

trait Debug[Session, AccountInfo] {

  trait StatefulService[M, In, Out] {
    type Input = In
    type Output = Out

    val id: String
    val func: PartialFunction[In, State[M, Out]]

    def map[Out2](f: Out => Out2): StatefulService[M, In, Out2]
    def flatMap[Out2](f: Out => StatefulService[M, Out, Out2]): StatefulService[M, In, Out2]
  }

  type Cmd[S, C <: Command, E <: Event] = StatefulService[S, C, E]
  type Evt[S, E1 <: Event, E2 <: Event] = StatefulService[S, E1, E2]
  type Ask[S, RQ <: Request, RS <: Response] = StatefulService[S, RQ, RS]


  case class SessionCreateCmd() extends Command
  case class SessionCreatedEvt() extends Event
  case class GetAccountInfoReq() extends Request
  case class GetAccountInfoRes() extends Response

  val cmd1: Cmd[Session, SessionCreateCmd, SessionCreatedEvt] = ???

  val req1f: GetAccountInfoReq => Ask[AccountInfo, GetAccountInfoReq, GetAccountInfoRes] = ???

  cmd1.map( _ => GetAccountInfoReq()).flatMap(req => {
    val ss: StatefulService[Session, GetAccountInfoReq, GetAccountInfoRes] = ???
    ss
  })

  for {
    evt <- cmd1
    req: GetAccountInfoReq = ??? //use evt to build req ... makeRequest(evt)
    accInfo <- req1f(req)
  } yield accInfo

}
