package org.patricknoir

import cats.data.State
import org.patricknoir.kafka.reactive.common.{ReactiveDeserializer, ReactiveSerializer}
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
    val func: PartialFunction[In, State[M, Out]]
  }

  trait ContextStatefulService[M, In, Out] {
    type Input = In
    type Output = Out

    val id: String
    val func: PartialFunction[(ComponentContext, In), State[M, Out]]
  }

  case class AsyncStatefulService[M, In, Out](override val id: String, override val func: PartialFunction[In, State[M, Out]]) extends StatefulService[M, In, Out]
  case class SyncStatefulService[M, In, Out](override val id: String, override val func: PartialFunction[In, State[M, Out]]) extends StatefulService[M, In, Out]

  object StatefulService {
    def async[M, In, Out](id: String, service: PartialFunction[In, State[M, Out]]) = AsyncStatefulService(id, service)
    def sync[M, In, Out](id: String, service: PartialFunction[In, State[M, Out]]) = SyncStatefulService(id, service)
  }

  case class SyncContextStatefulService[M, In, Out](override val id: String, override val func: PartialFunction[(ComponentContext, In), State[M, Out]]) extends ContextStatefulService[M, In, Out]
  case class AsyncContextStatefulService[M, In, Out](override val id: String, override val func: PartialFunction[(ComponentContext, In), State[M, Out]]) extends ContextStatefulService[M, In, Out]

  object ContextStatefulService {
    def async[M, In, Out](id: String, service: PartialFunction[(ComponentContext, In), State[M, Out]]) = AsyncContextStatefulService(id, service)
    def sync[M, In, Out](id: String, service: PartialFunction[(ComponentContext, In), State[M, Out]]) = SyncContextStatefulService(id, service)
  }


  /**
    * This is used to collect information on the specific service function together
    * with the serialiser and deserialiser to be used to receive and send input/output
    * over the network.
    * @param service
    * @param deserializer
    * @param serializer
    * @tparam S
    * @tparam I
    * @tparam O
    */
  case class StatefulServiceInfo[S, I, O](
    service: StatefulService[S, I, O],
    deserializer: ReactiveDeserializer[_ <: I],
    serializer: ReactiveSerializer[_ <: O]
  )

  case class ContextStatefulServiceInfo[S, I, O](
    service: ContextStatefulService[S, I, O],
    deserializer: ReactiveDeserializer[_ <: I],
    serializer: ReactiveSerializer[_ <: O]
  )


  type CmdInfo[S] = StatefulServiceInfo[S, Command, Seq[Event]]
  type EvtInfo[S] = StatefulServiceInfo[S, Event, Seq[Event]]
  type AskInfo[S] = StatefulServiceInfo[S, Request, Response]

  type Cmd[S] = StatefulService[S, Command, Seq[Event]]
  type Evt[S] = StatefulService[S, Event, Seq[Event]]
  type Ask[S] = StatefulService[S, Request, Response]
  type Recovery[S] = StatefulService[S, Event, Unit]

  type CtxCmdInfo[S] = ContextStatefulServiceInfo[S, Command, Seq[Event]]
  type CtxEvtInfo[S] = ContextStatefulServiceInfo[S, Event, Seq[Event]]
  type CtxAskInfo[S] = ContextStatefulServiceInfo[S, Request, Response]

  type CtxCmd[S] = ContextStatefulService[S, Command, Seq[Event]]
  type CtxEvt[S] = ContextStatefulService[S, Event, Seq[Event]]
  type CtxAsk[S] = ContextStatefulService[S, Request, Response]

}
