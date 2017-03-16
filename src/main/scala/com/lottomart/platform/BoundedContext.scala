package com.lottomart.platform

import cats.Monoid
import cats.syntax.all._
import cats._
import cats.data._
import com.lottomart.platform.protocol._

import scala.concurrent.Future

case class SFService[M, In, Out](id: String, func: In => Future[State[M, Out]])

case class Service[In, Out](id: String, f: PartialFunction[In, Future[Out]])
case class StatefullService[M, In, Out](id: String, func: PartialFunction[In, Future[State[M, Out]]])

case class ServiceURL(boundedContextId: String, componentId: String, serviceId: String)

trait Component {
  val id: String
  val version: Version
}

trait Model[T] {
  def apply(event: Event): Model[T]
}

object Model {
  def lift[T](t: T): Model[T] = ???
}

trait ProcessorDescriptor
case class KeyShardedProcessDescriptor(
  commandKeyExtractor: PartialFunction[Command, (String, Command)],
  eventKeyExtractor: PartialFunction[Event, (String, Event)],
  dependencies: Set[ServiceURL],
  hashFunction: String => Int,
  shardSpaceSize: Int
) extends ProcessorDescriptor

case class Processor[W] (
  override val id: String,
  override val version: Version,
  descriptor: ProcessorDescriptor,
  model: W,
  commandModifiers: Set[StatefullService[W, Command, Seq[Event]]],
  eventModifiers: Set[StatefullService[W, Event, Seq[Event]]]
) extends Component

trait ViewDescriptor
case class KeyShardedViewDescriptor(
  eventKeyExtractor: PartialFunction[Event, (String, Event)],
  hashFunction: String => Int,
  shardSpaceSize: Int
)

case class View[R](
  override val id: String,
  override val version: Version,
  descriptor: ViewDescriptor,
  model: Model[R],
  modifiers: Set[StatefullService[Model[R], Event, Seq[Event]]],
  queries: Set[StatefullService[Model[R], Request, Response]]
) extends Component

case class Version(
  major: Int,
  minor: Int,
  patch: Int
)


case class BoundedContext(
  id: String,
  version: Version,
  requestMailboxName: String,
  responseMailboxName: String,
  commandMailboxName: String,
  eventMailboxName: String,
  failureMailboxName: String,
  auditingMailboxName: String,
  loggingMailboxName: String,
  components: Set[Component]
)
