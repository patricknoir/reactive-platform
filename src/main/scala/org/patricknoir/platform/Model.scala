package org.patricknoir.platform

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import org.patricknoir.platform.Util.CounterValueResp
import org.patricknoir.platform.protocol._
import sun.reflect.generics.reflectiveObjects.NotImplementedException

import scala.concurrent.{ExecutionContext, Future}

/**
  * Represents the coordinate of a specific server
  * which lives under a bounded context and inside
  * a specific component.
  * @param boundedContextId identifier for the bounded-context
  * @param componentId identifier for the component which owns the service
  * @param serviceId the specific service id
  *
  */
case class ServiceURL(boundedContextId: String, componentId: String, serviceId: String)

/**
  * A bounded context is a self contained system which delivers
  * some business functionality by mastering its internal model
  * and exposing a Protocol in order to interact with other
  * bounded contexts.
  * @param id
  * @param version
  * @param requestMailboxName
  * @param responseMailboxName
  * @param commandMailboxName
  * @param eventMailboxName
  * @param failureMailboxName
  * @param auditMailboxName
  * @param logMailboxName
  * @param componentDefs
  */
case class BoundedContext(
  id: String,
  version: Version,
  requestMailboxName: String = "requests",
  responseMailboxName: String = "responses",
  commandMailboxName: String = "commands",
  eventMailboxName: String = "events",
  failureMailboxName: String = "failures",
  auditMailboxName: String = "audits",
  logMailboxName: String = "logs",
  componentDefs: Set[ComponentDef[_]]
)

/**
  * DESCRIPTORS
  */

sealed trait ComponentDescriptor
sealed trait ProcessorDescriptor extends ComponentDescriptor
sealed trait ViewDescriptor extends ComponentDescriptor

/**
  * Describes a strategy to create a processor for each entity id,
  * using specific functions to extract ids from commands/events/requests.
  * @param commandKeyExtractor
  * @param eventKeyExtractor
  * @param queryKeyExtractor
  * @param dependencies
  * @param hashFunction
  * @param shardSpaceSize
  */
case class KeyShardedProcessDescriptor(
  commandKeyExtractor: PartialFunction[Command, (String, Command)],
  eventKeyExtractor: PartialFunction[Event, (String, Event)],
  queryKeyExtractor: PartialFunction[Request, (String, Request)],
  dependencies: Set[ServiceURL],
  hashFunction: String => Int,
  shardSpaceSize: Int
) extends ProcessorDescriptor

case class KeyShardedViewDescriptor(
  eventKeyExtractor: PartialFunction[Event, (String, Event)],
  hashFunction: String => Int,
  shardSpaceSize: Int
)

/**
  * COMPONENTS
  */

sealed trait ComponentProps[T]

case class Version(
  major: Int,
  minor: Int,
  patch: Int
) {
  override def toString = s"$major.$minor.$patch"
}

sealed trait ComponentDef[T] {
  val id: String
  val version: Version
  val descriptor: ComponentDescriptor
  val model: T
  val propsFactory: (ComponentContext) => ComponentProps[T]

  def instantiate(ctx: ComponentContext): Component[T]
}

sealed trait Component[T] {
  val id: String
  val version: Version
  val descriptor: ComponentDescriptor
  val model: T
  val props: ComponentProps[T]
}

/**
  * PROCESSORS
  */

/**
  * A processor represents the component in charge to handle the write-logic
  * for a specific root-aggregate in your service.
  *
  * Is parametric on the type `W` where `W` represents is your root-aggregate type.
  *
  * You describe a Processor by defining its component name and version, a descriptor which
  * contains some information related to the semantic on how processors are created (singleton, entity etc...)
  *
  * A processor is in charge to manipulate the root-aggregate `W` in order to guarantee consistence, this is done
  * by defining the `reducers` commandModifiers, eventModifiers, which describes how the root-aggregate should be
  * modified in reaction to specific commands or events.
  *
  * @param id processor component identifier.
  * @param version processor component version.
  * @param descriptor describes how this view should be create (singleton, per entity id etc...).
  * @param props describes the behaviour of this processor.
  * @tparam W represents the root-aggregate type.
  */
sealed case class Processor[W](
  override val id: String,
  override val version: Version,
  override val descriptor: ProcessorDescriptor,
  override val model: W,
  override val props: ProcessorProps[W]
) extends Component[W]

case class ProcessorProps[W](
  commandModifiers: Set[CmdInfo[W]] = Set.empty[CmdInfo[W]],
  eventModifiers: Set[Evt[W]] = Set.empty[Evt[W]],
  queries: Set[AskInfo[W]] = Set.empty[AskInfo[W]]
) extends ComponentProps[W]

case class ProcessorDef[W](
  override val id: String,
  override val version: Version,
  override val descriptor: ProcessorDescriptor,
  override val model: W,
  override val propsFactory: (ComponentContext) => ProcessorProps[W]
) extends ComponentDef[W] {
  override def instantiate(ctx: ComponentContext): Processor[W] =
    Processor(id, version, descriptor, model, propsFactory(ctx))
}

/**
  * VIEWS
  */

/**
  * A view represents the read-logic of your service.
  * It has an aggregate-root of generic type `R` and react to
  * events in order to track relevant changes.
  *
  * The purpose of a view is to respond to query which will come
  * under form of requests.
  *
  * As a component is described by an identifier, is versioned and
  * has a descriptor which defines the strategy to be used in order
  * to create view instances.
  *
  * @param id view component identifier.
  * @param version view component version.
  * @param descriptor describes how this view should be create (singleton, per entity id etc...).
  * @param props describes the behaviour of this view.
  * @tparam R represents the root-aggregate type.
  */
sealed case class View[R](
  override val id: String,
  override val version: Version,
  override val descriptor: ViewDescriptor,
  override val model: R,
  override val props: ViewProps[R]
) extends Component[R]

case class ViewProps[R](
  modifiers: Set[Evt[R]],
  queries: Set[Ask[R]]
) extends ComponentProps[R]

case class ViewDef[R](
  override val id: String,
  override val version: Version,
  override val descriptor: ViewDescriptor,
  override val model: R,
  override val propsFactory: (ComponentContext) => ViewProps[R]
) extends ComponentDef[R] {
  override def instantiate(ctx: ComponentContext): Component[R] =
    View(id, version, descriptor, model, propsFactory(ctx))
}

sealed trait ComponentContext {
  def request[R <: Request, RR <: Response](target: String, request: R): Future[RR]

  def log(): LoggingAdapter
  def log(source: AnyRef): LoggingAdapter
}
case class DefaultComponentContextImpl()(implicit system: ActorSystem) extends ComponentContext {

  override def request[R <: Request, RR <: Response](target: String, request: R): Future[RR] =
    Future.failed(new NotImplementedException)

  override def log(): LoggingAdapter = system.log
  override def log(source: AnyRef): LoggingAdapter = Logging.getLogger(system.eventStream, source)

}
