package com.lottomart

import cats.data.State
import com.lottomart.platform.protocol.{Command, Event, Request, Response}

import scala.concurrent.Future

/**
  * Created by patrick on 16/03/2017.
  */
package object platform {

  case class Service[-In, +Out](id: String, f: PartialFunction[In, Future[Out]])
  case class StatefulService[M, -In, Out](id: String, func: PartialFunction[In, Future[State[M, Out]]])

  type Cmd[S] = StatefulService[S, Command, Seq[Event]]
  type Evt[S] = StatefulService[S, Event, Seq[Event]]
  type Ask[S] = StatefulService[S, Request, Response]
}
