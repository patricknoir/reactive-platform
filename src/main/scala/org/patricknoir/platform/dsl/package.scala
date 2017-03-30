package org.patricknoir.platform

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.kafka.ConsumerMessage.CommittableMessage
import akka.util.Timeout
import cats.data.State
import org.patricknoir.kafka.reactive.common.{ReactiveDeserializer, ReactiveSerializer}
import org.patricknoir.kafka.reactive.server.{ReactiveRoute, ReactiveService, ReactiveSystem}
import org.patricknoir.kafka.reactive.server.streams.{ReactiveKafkaSink, ReactiveKafkaSource}
import org.patricknoir.platform.protocol.{Command, Event, Request, Response}
import org.patricknoir.platform.runtime.{Platform, ProcessorServer}
import org.patricknoir.platform.runtime.actors.ProcessorActor

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.reflect.ClassTag
import org.patricknoir.kafka.reactive.server.dsl._

import scala.concurrent.duration._
import akka.pattern.ask
import akka.stream.scaladsl.Flow
import org.patricknoir.kafka.reactive.client.actors.KafkaConsumerActor.KafkaResponseEnvelope

/**
  * Created by patrick on 20/03/2017.
  */
package object dsl {

  object request {
    def apply[Req <: Request, Resp <: Response, S](id: String)(query: (S, Req) => Resp)(implicit reqCT: ClassTag[Req], respCT: ClassTag[Resp], deserializer: ReactiveDeserializer[Req], serializer: ReactiveSerializer[Resp]) = {
      val fc: PartialFunction[Request, State[S, Response]] = {
        case req: Req => State.inspect(state => query(state, req))
      }
      StatefulServiceInfo[S, Request, Response](StatefulService.sync[S, Request, Response](id, fc), deserializer, serializer)
    }
  }

  object command {
    def apply[C <: Command, E <: Event, S](id: String)(modifier: (S, C) => (S, Seq[E]))(implicit ct: ClassTag[C], ect: ClassTag[E], deserializer: ReactiveDeserializer[C], serializer: ReactiveSerializer[Seq[E]]) = {
      val fc: PartialFunction[Command, State[S, Seq[Event]]] = {
        case cmd: C => State(init => modifier(init, cmd))
      }
      StatefulServiceInfo[S, Command, Seq[Event]](StatefulService.sync[S, Command, Seq[Event]](id, fc), deserializer, serializer)
    }

    def async[C <: Command, E <: Event, S](id: String)(modifier: (S, C) => Future[(S, Seq[E])])(implicit ec: ExecutionContext, timeout: Timeout, ct: ClassTag[C], ect: ClassTag[E], deserializer: ReactiveDeserializer[C], serializer: ReactiveSerializer[Seq[E]]) = {
      val fc: PartialFunction[Command, Future[State[S, Seq[Event]]]] = {
        case cmd: C =>
          Future(State { init =>
            Await.result(modifier(init, cmd), timeout.duration) //can I avoid this blocking?
          })
      }
      StatefulServiceInfo[S, Command, Seq[Event]](StatefulService.async[S, Command, Seq[Event]](id, fc), deserializer, serializer)
    }
  }

  object processor {
    def apply[W](id: String, init: W, version: Version = Version(1, 0, 0))(descriptor: ProcessorDescriptor)(modifiers: CmdInfo[W]*): Processor[W] = {
      Processor[W](
        id = "counterProcessor",
        version = Version(1, 0, 0),
        descriptor = descriptor,
        model = init,
        //TODO:  Will be good the command()() DSL also includes the key extraction if we are using KeySharded strategy
        commandModifiers = modifiers.toSet,
        eventModifiers = Set.empty
      )
    }
  }


  case class PlatformConfig(
    messageFabricServers: Set[String],
    serverDefaultTimeout: Timeout
  )

  object PlatformConfig {
    lazy val default = PlatformConfig(
      messageFabricServers = Set("kafka1:9092"),
      serverDefaultTimeout = Timeout(5 seconds)
    )
  }

  /** Create connectivities */
  object platform {

    def apply(bc: BoundedContext)(implicit system: ActorSystem, config: PlatformConfig): Platform = Platform(
      processorServers = bc.components
        .filter(_.isInstanceOf[Processor[_]])
        .map(component => (component.id -> createProcessorServer(bc, component.asInstanceOf[Processor[_]])))
        .toMap
    )

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

      val topicPrefix = bc.id + "_" + bc.version.formattedString + "_"
      val commandTopic = topicPrefix + bc.commandMailboxName
      val requestTopic = topicPrefix + bc.requestMailboxName

      val commandSource = ReactiveKafkaSource.atLeastOnce(commandTopic, config.messageFabricServers, topicPrefix + "command")
      val requestSource = ReactiveKafkaSource.atLeastOnce(requestTopic, config.messageFabricServers, topicPrefix + "request")


      val commandFlow = Flow.fromFunction[(CommittableMessage[String, String], Future[KafkaResponseEnvelope]), (CommittableMessage[String, String], Future[KafkaResponseEnvelope])] { case (c, fResp) =>
        (c, fResp.map(_.copy(replyTo = bc.eventMailboxName)))
      }
      val commandSink = ReactiveKafkaSink.atLeastOnce(config.messageFabricServers)
      val responseSink = ReactiveKafkaSink.atLeastOnce(config.messageFabricServers)

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
      }.reduce(_ ~ _)
    }
  }

}

