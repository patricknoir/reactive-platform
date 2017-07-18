package org.patricknoir.platform

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.stream.ActorMaterializer
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.patricknoir.kafka.reactive.client.ReactiveKafkaClient
import org.patricknoir.kafka.reactive.client.config.KafkaReactiveClientConfig
import org.patricknoir.kafka.reactive.common.{ReactiveDeserializer, ReactiveSerializer}
import org.patricknoir.platform.Util.CounterValueResp
import org.patricknoir.platform.dsl.PlatformConfig
import org.patricknoir.platform.protocol._
import org.patricknoir.platform.runtime.{BoundedContextInfo, DefaultRegistryImpl, EtcdRegistryImpl, MessageFabric}
import sun.reflect.generics.reflectiveObjects.NotImplementedException

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Try

case class ServiceURL(bcId: String, version: Version, messageName: String)

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
  components: Set[Component[_]]
//  componentDefs: Set[ComponentDef[_]]
) {
  private val prefix = s"${id}_${version}_"
  val fullRequestMailboxName: String = prefix + requestMailboxName
  val fullResponseMailboxName: String = prefix + responseMailboxName
  val fullCommandMailboxName: String = prefix + commandMailboxName
  val fullEventMailboxName: String = prefix + eventMailboxName
  val fullFailureMailboxName: String = prefix + failureMailboxName
  val fullLogMailboxName: String = prefix + logMailboxName
}

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

object Version {
  def fromString(versionStr: String): Try[Version] = Try {
    val parts = versionStr.split(".")
    Version(parts(0).toInt, parts(1).toInt, parts(2).toInt)
  }
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
//  val props: ComponentProps[T]
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
  * @tparam W represents the root-aggregate type.
  */
sealed case class Processor[W](
  override val id: String,
  override val version: Version,
  override val descriptor: ProcessorDescriptor,
  override val model: W,
  val commandModifiers: Set[CtxCmdInfo[W]] = Set.empty[CtxCmdInfo[W]],
  val eventModifiers: Set[CtxEvtInfo[W]] = Set.empty[CtxEvtInfo[W]],
  val queries: Set[CtxAskInfo[W]] = Set.empty[CtxAskInfo[W]]
//  override val props: ProcessorProps[W]
) extends Component[W]

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
  * @tparam R represents the root-aggregate type.
  */
sealed case class View[R](
  override val id: String,
  override val version: Version,
  override val descriptor: ViewDescriptor,
  override val model: R,
  val modifiers: Set[CtxEvtInfo[R]] = Set.empty[CtxEvtInfo[R]],
  val queries: Set[CtxAskInfo[R]] = Set.empty[CtxAskInfo[R]]
//  override val props: ViewProps[R]
) extends Component[R]

sealed trait ComponentContext {

  def request[R <: Request, RR <: Response](target: ServiceURL, request: R)(implicit serializer: ReactiveSerializer[R], deserializer: ReactiveDeserializer[RR], ctRR: ClassTag[RR]): Future[RR]
  def send[C <: Command](target: ServiceURL, cmd: C)(implicit serializer: ReactiveSerializer[C]): Future[Unit]

  def log(): LoggingAdapter
  def log(source: AnyRef): LoggingAdapter
}
case class DefaultComponentContextImpl(bc: BoundedContext)(implicit system: ActorSystem, config: PlatformConfig) extends ComponentContext {

  import system.dispatcher

  private implicit val timeout =  config.serverDefaultTimeout

  private val registry = new EtcdRegistryImpl(config)

  private implicit val materializer = ActorMaterializer()

  private var destinationCache = Map.empty[ServiceURL, BoundedContextInfo]

  import scala.collection.JavaConverters._

  private val clientConfig = KafkaReactiveClientConfig(ConfigFactory.load().getConfig("reactive.system.client.kafka")
    .withValue("response-topic", ConfigValueFactory.fromAnyRef(bc.fullResponseMailboxName))
    .withValue("consumer.bootstrap-servers", ConfigValueFactory.fromIterable(config.messageFabricServers.asJava))
    .withValue("consumer.group-id", ConfigValueFactory.fromAnyRef(s"${bc.id}_${bc.version}"))
    .withValue("producer.bootstrap-servers", ConfigValueFactory.fromIterable(config.messageFabricServers.asJava))
  )

  private val client = new ReactiveKafkaClient(clientConfig)

  override def request[R <: Request, RR <: Response](target: ServiceURL, request: R)(implicit serializer: ReactiveSerializer[R], deserializer: ReactiveDeserializer[RR], ctRR: ClassTag[RR]): Future[RR] = for {
    destination <- Future.fromTry(resolveRequestDestination(target))
    response <- client.request(s"kafka:${destination.requests}/${target.messageName}", request)
  } yield response

  override def send[C <: Command](target: ServiceURL, cmd: C)(implicit serializer: ReactiveSerializer[C]): Future[Unit] = for {
    destination <- Future.fromTry(resolveRequestDestination(target))
    _ <- client.send(s"kafka:${destination.inputCommands}/${target.messageName}", cmd, true)
  } yield ()

  private def resolveRequestDestination(target: ServiceURL): Try[BoundedContextInfo] = Try {
    destinationCache.getOrElse(target, {
      val dest = registry.get(target.bcId, target.version).get
      destinationCache += (target -> dest)
      dest
    })
  }

  override def log(): LoggingAdapter = system.log
  override def log(source: AnyRef): LoggingAdapter = Logging.getLogger(system.eventStream, source)

}
