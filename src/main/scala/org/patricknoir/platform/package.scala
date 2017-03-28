package org.patricknoir

import cats.data.State
import org.patricknoir.platform.protocol.{Command, Event, Request, Response}

import scala.concurrent.Future

/**
  * Created by patrick on 16/03/2017.
  */
package object platform {

  case class Service[-In, +Out](id: String, f: PartialFunction[In, Future[Out]])

  trait StatefulService[M, In, Out] {
    type Input = In
    type Output = Out

    val id: String
    val func: PartialFunction[In, Future[State[M, Out]]]
  }

  case class AsyncStatefulService[M, In, Out](override val id: String, override val func: PartialFunction[In, Future[State[M, Out]]]) extends StatefulService[M, In, Out]

  case class SyncStatefulService[M, In, Out](override val id: String, val syncFunc: PartialFunction[In, State[M, Out]]) extends StatefulService[M, In, Out] {
    val func: PartialFunction[In, Future[State[M, Out]]] = syncFunc.andThen(Future.successful)
  }

  object StatefulService {
    def async[M, In, Out](id: String, service: PartialFunction[In, Future[State[M, Out]]]) = AsyncStatefulService(id, service)
    def sync[M, In, Out](id: String, service: PartialFunction[In, State[M, Out]]) = SyncStatefulService(id, service)
  }



  type Cmd[S] = StatefulService[S, Command, Seq[Event]]
  type Evt[S] = StatefulService[S, Event, Seq[Event]]
  type Ask[S] = StatefulService[S, Request, Response]

}
