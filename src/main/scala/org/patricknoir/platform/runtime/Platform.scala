package org.patricknoir.platform.runtime

import akka.actor.{ActorRef, ActorSystem, Terminated}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.kafka.ConsumerMessage.CommittableMessage
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import org.patricknoir.kafka.reactive.common.{KafkaResponseEnvelope, ReactiveDeserializer, ReactiveSerializer}
import org.patricknoir.kafka.reactive.server.streams.{ReactiveKafkaSink, ReactiveKafkaSource}
import org.patricknoir.kafka.reactive.server.{ReactiveRoute, ReactiveService, ReactiveSystem}
import org.patricknoir.platform.dsl.PlatformConfig
import org.patricknoir.platform.protocol.{Command, Event, Request}
import org.patricknoir.platform.runtime.actors.ProcessorActor
import org.patricknoir.platform._

import scala.concurrent.{Await, ExecutionContext, Future}
import org.patricknoir.kafka.reactive.server.dsl._
import akka.pattern.ask
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}

import scala.concurrent.duration._
import scala.util.Try

/**
  * Created by patrick on 26/03/2017.
  */
case class Platform(
  val processorServers: Map[String, ProcessorServer]
) {
  def run()(implicit ec: ExecutionContext, materializer: Materializer): Future[Unit] = {
    processorServers.values.map { server =>
      server.queryReactiveSystem.run()
      server.commandReactiveSystem.run()
    }

    Future.successful[Unit](()) //FIXME

  }
}

object Platform extends LazyLogging {

  def install(bc: BoundedContext)(implicit config: PlatformConfig = PlatformConfig.default): (Future[Unit], Future[Terminated]) = { //platform(bc)

    import scala.collection.convert.ImplicitConversionsToJava._
    val reference = ConfigFactory.load()
    val serverHost = reference.getString("akka.remote.netty.tcp.hostname")
    val serverPort = reference.getInt("akka.remote.netty.tcp.port")
    implicit val akkaConfig = reference.withValue("akka.cluster.seed-nodes", ConfigValueFactory.fromIterable(List(s"akka.tcp://${bc.id}@$serverHost:$serverPort")))
    implicit val system = ActorSystem(bc.id, akkaConfig)
    implicit val materializer = ActorMaterializer()
    import system.dispatcher

    implicit val timeout = config.serverDefaultTimeout

    val context = new DefaultComponentContextImpl(bc)
    val registry = new DefaultRegistryImpl(context)
    val messageFabric = MessageFabric.create(config.zookeeperHosts.mkString(","), config.zkMinBackOff, config.zkMaxBackOff)

    val result = for {
      info <- Future.fromTry(registry.register(bc))
      _ <- Future.sequence(Seq(
        bc.fullCommandMailboxName,
        bc.fullEventMailboxName,
        bc.fullRequestMailboxName,
        bc.fullFailureMailboxName,
        bc.fullResponseMailboxName,
        bc.fullLogMailboxName
      ).map(messageFabric.createMailbox))
    } yield info

    Try(Await.ready(result, Duration.Inf))

    (
      Platform(
        processorServers = bc.componentDefs
          .filter(_.isInstanceOf[ProcessorDef[_]])
          .map(component => (component.id -> createProcessorServer(context, bc, component.asInstanceOf[ProcessorDef[_]])))
          .toMap
      ).run(),
      system.whenTerminated
    )
  }

  def uninstall(bc: BoundedContext)(implicit config: PlatformConfig) = {
    import scala.collection.convert.ImplicitConversionsToJava._
    implicit val akkaConfig = ConfigFactory.load().withValue("akka.cluster.seed-nodes", ConfigValueFactory.fromIterable(List(s"akka.tcp://${bc.id}@127.0.0.1:7551")))
    implicit val system = ActorSystem(bc.id, akkaConfig)

    import system.dispatcher

    implicit val timeout = config.serverDefaultTimeout

    val context = new DefaultComponentContextImpl(bc)
    val registry = new DefaultRegistryImpl(context)
    val messageFabric = MessageFabric.create(config.zookeeperHosts.mkString(","), config.zkMinBackOff, config.zkMaxBackOff)


    for {
      _ <- Future.successful(registry.unregister(bc.id, bc.version))
      _ <- Future.sequence(Seq(
        bc.fullCommandMailboxName,
        bc.fullEventMailboxName,
        bc.fullRequestMailboxName,
        bc.fullFailureMailboxName,
        bc.fullResponseMailboxName,
        bc.fullLogMailboxName
      ).map(messageFabric.createMailbox))
    } yield ()
  }

  private def createProcessorServer(ctx: ComponentContext, bc: BoundedContext, processorProps: ProcessorDef[_])(implicit system: ActorSystem, config: PlatformConfig): ProcessorServer = {
    import system.dispatcher
    implicit val timeout = config.serverDefaultTimeout

    val processor: Processor[_] = processorProps.instantiate(ctx)

    val descriptor = processor.descriptor.asInstanceOf[KeyShardedProcessDescriptor]
    val extractIdFunction: PartialFunction[Any, (String, Any)] = {
      case cmd: Command => descriptor.commandKeyExtractor(cmd)
      case evt: Event => descriptor.eventKeyExtractor(evt)
      case req: Request => descriptor.queryKeyExtractor(req)
    }
    val extractShardIdFunction = extractIdFunction.andThen(res => (descriptor.hashFunction(res._1) % descriptor.shardSpaceSize).toString)

    val server = ClusterSharding(system).start(
      typeName = processor.id,
      entityProps = ProcessorActor.props(processor),
      settings = ClusterShardingSettings(system),
      extractEntityId = extractIdFunction,
      extractShardId = extractShardIdFunction
    )

    val groupName = bc.id + "_" + bc.version.toString
    val topicPrefix = bc.id + "_" + bc.version.toString + "_"

    val commandSource = ReactiveKafkaSource.atLeastOnce(bc.fullCommandMailboxName, config.messageFabricServers, topicPrefix + bc.commandMailboxName + "_consumer", groupName)
    val requestSource = ReactiveKafkaSource.atLeastOnce(bc.fullRequestMailboxName, config.messageFabricServers, topicPrefix + bc.requestMailboxName + "_consumer", groupName)

    val commandFlow = Flow[(CommittableMessage[String, String], Future[KafkaResponseEnvelope])].map{ case (c, fResp) =>
      (c, fResp.map(_.copy(replyTo = bc.fullEventMailboxName)))
    }

    val commandSink = ReactiveKafkaSink.atLeastOnce(config.messageFabricServers, 4, 10, 1 second)
    val responseSink = ReactiveKafkaSink.atLeastOnce(config.messageFabricServers, 4, 10, 1 second)

    val cmdRS = commandSource ~> createCommandRoute(processor.props.commandModifiers, server) ~> (commandFlow to commandSink)
    val reqRS = requestSource ~> createRequestRoute(processor.props.queries, server) ~> responseSink

    ProcessorServer(
      processor,
      server,
      reqRS,
      cmdRS
    )
  }

  private def createCommandRoute[S](cmds: Set[CmdInfo[S]], server: ActorRef)(implicit ec: ExecutionContext, timeout: Timeout) = {
    cmds.map { case StatefulServiceInfo(cmd, deserializer, serializer) =>
      implicit val des = deserializer.asInstanceOf[ReactiveDeserializer[cmd.Input]]
      implicit val ser = serializer.asInstanceOf[ReactiveSerializer[cmd.Output]]
      ReactiveRoute(Map(cmd.id -> (ReactiveService[cmd.Input, cmd.Output](cmd.id)(in => (server ? in).mapTo[cmd.Output]))))
    }.reduce(_ ~ _)
  }

  private def createRequestRoute[W](reqs: Set[AskInfo[W]], server: ActorRef)(implicit ec: ExecutionContext, timeout: Timeout) = {
    reqs.map { case StatefulServiceInfo(req, deserializer, serializer) =>
      implicit val des = deserializer.asInstanceOf[ReactiveDeserializer[req.Input]]
      implicit val ser = serializer.asInstanceOf[ReactiveSerializer[req.Output]]
      ReactiveRoute(Map(req.id -> ReactiveService[req.Input, req.Output](req.id)(in => (server ? in).mapTo[req.Output])))
    }.foldLeft(ReactiveRoute())(_ ~ _)
  }

}

case class ProcessorServer(
  processor: Processor[_],
  server: ActorRef,
  queryReactiveSystem: ReactiveSystem,
  commandReactiveSystem: ReactiveSystem
)


