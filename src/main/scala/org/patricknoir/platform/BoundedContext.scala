package org.patricknoir.platform

import cats.Monoid
import cats.syntax.all._
import cats._
import cats.data._
import org.patricknoir.platform.protocol._

import scala.concurrent.Future

case class ServiceURL(boundedContextId: String, componentId: String, serviceId: String)

trait Component {
  val id: String
  val version: Version
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
  commandModifiers: Set[Cmd[W]],
  eventModifiers: Set[Evt[W]]
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
  model: R,
  modifiers: Set[Evt[R]],
  queries: Set[Ask[R]]
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
