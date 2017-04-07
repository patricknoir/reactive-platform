package org.patricknoir.platform.runtime

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.kafka.ConsumerMessage.CommittableMessage
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.Flow
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import kafka.admin.TopicCommand
import kafka.utils.ZkUtils
import org.patricknoir.kafka.reactive.common.{KafkaResponseEnvelope, ReactiveDeserializer, ReactiveSerializer}
import org.patricknoir.kafka.reactive.server.streams.{ReactiveKafkaSink, ReactiveKafkaSource}
import org.patricknoir.kafka.reactive.server.{ReactiveRoute, ReactiveService, ReactiveSystem}
import org.patricknoir.platform.dsl.PlatformConfig
import org.patricknoir.platform.protocol.{Command, Event, Request}
import org.patricknoir.platform.runtime.actors.ProcessorActor
import org.patricknoir.platform._

import scala.concurrent.{ExecutionContext, Future}
import org.patricknoir.kafka.reactive.server.dsl._
import akka.pattern.ask
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import scala.concurrent.duration._

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
  def install(bc: BoundedContext)(implicit config: PlatformConfig = PlatformConfig.default): Future[Unit] = { //platform(bc)
    import scala.collection.convert.ImplicitConversionsToJava._
    implicit val akkaConfig = ConfigFactory.load().withValue("akka.cluster.seed-nodes", ConfigValueFactory.fromIterable(List(s"akka.tcp://${bc.id}@127.0.0.1:7551")))
    implicit val system = ActorSystem(bc.id, akkaConfig)
    implicit val materializer = ActorMaterializer()
    import system.dispatcher

    createTopics(bc, config)

    Platform(
      processorServers = bc.components
        .filter(_.isInstanceOf[Processor[_]])
        .map(component => (component.id -> createProcessorServer(bc, component.asInstanceOf[Processor[_]])))
        .toMap
    ).run()
  }

  private def createTopics(bc: BoundedContext, config: PlatformConfig) = {
    import kafka.admin.AdminUtils
    import org.I0Itec.zkclient.ZkClient

    TopicCommand

    val zkUtils = ZkUtils(config.zookeeperHosts.mkString(" "), 10000, 10000, false)


    val mailboxPrefix = s"${bc.id}_${bc.version.formattedString}_"

    (bc.requestMailboxName :: bc.responseMailboxName :: bc.commandMailboxName :: bc.eventMailboxName :: bc.failureMailboxName :: bc.auditMailboxName :: bc.logMailboxName :: Nil).map( mailboxPrefix + _ ).foreach(createMailbox)

    zkUtils.close()

    def createMailbox(mailboxName: String) {
      logger.info(s"Creating mailbox: $mailboxName")
      if(!AdminUtils.topicExists(zkUtils, mailboxName))
        AdminUtils.createTopic(zkUtils, mailboxName, 1, 1)//.createTopic(zkClient, mailboxName, 1, 1, new Nothing)
      logger.info(s"Mailbox: $mailboxName created")
    }
  }

  def createProcessorServer(bc: BoundedContext, processor: Processor[_])(implicit system: ActorSystem, config: PlatformConfig): ProcessorServer = {
    import system.dispatcher
    implicit val timeout = config.serverDefaultTimeout

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

    val groupName = bc.id + "_" + bc.version.formattedString
    val topicPrefix = bc.id + "_" + bc.version.formattedString + "_"
    val commandTopic = topicPrefix + bc.commandMailboxName
    val requestTopic = topicPrefix + bc.requestMailboxName

    val commandSource = ReactiveKafkaSource.atLeastOnce(commandTopic, config.messageFabricServers, topicPrefix + bc.commandMailboxName + "_consumer", groupName)
    val requestSource = ReactiveKafkaSource.atLeastOnce(requestTopic, config.messageFabricServers, topicPrefix + bc.requestMailboxName + "_consumer", groupName)


    val commandFlow = Flow.fromFunction[(CommittableMessage[String, String], Future[KafkaResponseEnvelope]), (CommittableMessage[String, String], Future[KafkaResponseEnvelope])] { case (c, fResp) =>
      (c, fResp.map(_.copy(replyTo = bc.eventMailboxName)))
    }
    val commandSink = ReactiveKafkaSink.atLeastOnce(config.messageFabricServers, 4, 10, 1 second)
    val responseSink = ReactiveKafkaSink.atLeastOnce(config.messageFabricServers, 4, 10, 1 second)

    val cmdRS = commandSource ~> createCommandRoute(processor.commandModifiers, server) ~> (commandFlow to commandSink)
    val reqRS = requestSource ~> createRequestRoute(processor.queries, server) ~> responseSink

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


