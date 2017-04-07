package org.patricknoir.platform

import org.patricknoir.platform.protocol._
import sun.reflect.generics.reflectiveObjects.NotImplementedException

import scala.concurrent.Future

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
  * A component is an element of a bounded context which manipulates
  * the internal model in order to implement some business functionality
  *
  * A component is always described by an id and a version number.
  */
trait Component {
  val id: String
  val version: Version
  val context: ComponentContext
}

/**
  * A version is used to track the evolution of a component
  * with in a bounded context.
  * The major.minor.patch parts of a version should be used
  * to verify where back compatibilities is guaranteed when
  * interacting with other components.
  * @param major
  * @param minor
  * @param patch
  */
case class Version(
  major: Int,
  minor: Int,
  patch: Int
) {
  val formattedString = s"$major.$minor.$patch"
}

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
  * @param components
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
   components: Set[Component]
)

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
  * @param id processor identifier
  * @param version processor component version
  * @param descriptor describes how the processor should be created (singleton, one per entity etc...)
  * @param model root-aggregate
  * @tparam W represents the root-aggregate type.
  */
abstract class Processor[W] (
  override val id: String,
  override val version: Version,
  override val context: ComponentContext,
  val descriptor: ProcessorDescriptor,
  val model: W
)(implicit failHandler: Throwable => Failure) extends Component {
  type ModelType = W

  def commandModifiers(): Set[CmdInfo[W]] = Set.empty[CmdInfo[W]]
  def eventModifiers(): Set[Evt[W]] = Set.empty[Evt[W]]
  def queries(): Set[AskInfo[W]] = Set.empty[AskInfo[W]]
}

/**
  * Is used to describe how processors should be instantiated.
  * A descriptor is used by the platform at runtime to instantiate
  * the right processors in order to perform the write-logic.
  */
trait ProcessorDescriptor

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

/***
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
  * @param id view component identifier
  * @param version view component version
  * @param descriptor describes how this view should be create (singleton, per entity id etc...)
  * @param model is the root-aggregate instance
  * @param modifiers is the set of events which will couse the view root-aggregate R to be modified
  * @param queries set of requests/responses supported by this view
  * @tparam R is the type representing the root-aggregate
  */
case class View[R](
  override val id: String,
  override val version: Version,
  override val context: ComponentContext,
  descriptor: ViewDescriptor,
  model: R,
  modifiers: Set[Evt[R]],
  queries: Set[Ask[R]]
) extends Component

trait ViewDescriptor

trait ComponentContext {
  def request[R <: Request, RR <: Response](target: String, request: R): Future[RR]
}
case class DefaultComponentContextImpl() extends ComponentContext {
  override def request[R <: Request, RR <: Response](target: String, request: R): Future[RR] =
    Future.failed(new NotImplementedException)
}

case class KeyShardedViewDescriptor(
  eventKeyExtractor: PartialFunction[Event, (String, Event)],
  hashFunction: String => Int,
  shardSpaceSize: Int
)

