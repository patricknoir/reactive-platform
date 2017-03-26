package org.patricknoir.platform

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.util.Timeout
import cats.data.State
import org.patricknoir.kafka.reactive.server.{ReactiveRoute, ReactiveService, ReactiveSystem}
import org.patricknoir.kafka.reactive.server.streams.{ReactiveKafkaSink, ReactiveKafkaSource}
import org.patricknoir.platform.protocol.{Command, Event, Request, Response}
import org.patricknoir.platform.runtime.Boot.system
import org.patricknoir.platform.runtime.{Platform, ProcessorServer}
import org.patricknoir.platform.runtime.Util._
import org.patricknoir.platform.runtime.actors.ProcessorActor

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.reflect.ClassTag
import org.patricknoir.kafka.reactive.server.dsl._
import scala.concurrent.duration._

/**
  * Created by patrick on 20/03/2017.
  */
package object dsl {

  object request {
    def apply[Req <: Request, Resp <: Response, S](id: String)(query: (S, Req) => Resp)(implicit reqCT: ClassTag[Req], respCT: ClassTag[Resp]) = {
      val fc: PartialFunction[Request, Future[State[S, Response]]] = {
        case req: Req => Future.successful(State.inspect(state => query(state, req)))
      }
      StatefulService[S, Request, Response](id, fc)
    }
  }

  object command {
    def apply[C <: Command, S](id: String)(modifier: (S, C) => (S, Seq[Event]))(implicit ct: ClassTag[C]) = {
      val fc: PartialFunction[Command, Future[State[S, Seq[Event]]]] = {
        case cmd: C => Future.successful(State(init => modifier(init, cmd)))
      }
      StatefulService[S, Command, Seq[Event]](id, fc)
    }

    def async[C <: Command, S](id: String)(modifier: (S, C) => Future[(S, Seq[Event])])(implicit ec: ExecutionContext, timeout: Timeout, ct: ClassTag[C]) = {
      val fc: PartialFunction[Command, Future[State[S, Seq[Event]]]] = {
        case cmd: C =>
          Future(State { init =>
            Await.result(modifier(init, cmd), timeout.duration) //can I avoid this blocking?
          })
      }
      StatefulService[S, Command, Seq[Event]](id, fc)
    }
  }

  object processor {
    def apply[W](id: String, init: W, version: Version = Version(1, 0, 0))(descriptor: ProcessorDescriptor)(modifiers: Cmd[W]*): Processor[W] = {
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

  object ProcessorExample {
    val counterProcessor = processor[Int]("counterProcessor", 0)(KeyShardedProcessDescriptor(
      commandKeyExtractor = {
        case cmd@IncrementCounterCmd(id, _) => (id, cmd)
        case cmd@DecrementCounterCmd(id, _) => (id, cmd)
      },
      eventKeyExtractor = PartialFunction.empty,
      queryKeyExtractor = {
        case req@CounterValueReq(id) => (id, req)
      },
      dependencies = Set.empty,
      hashFunction = _.hashCode,
      shardSpaceSize = 100
    ))(
      command("incrementCmd") { (counter: Int, ic: IncrementCounterCmd) =>
        (counter + ic.step, Seq(CounterIncrementedEvt(ic.id, ic.step)))
      },
      command("decrementCmd") { (counter: Int, dc: DecrementCounterCmd) =>
        (counter - dc.step, Seq(CounterDecrementedEvt(dc.id, dc.step)))
      }
    )
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

//      val commandSource = ReactiveKafkaSource.atLeastOnce(commandTopic, config.messageFabricServers, topicPrefix + "command")
//      val requestSource = ReactiveKafkaSource.atLeastOnce(requestTopic, config.messageFabricServers, topicPrefix + "request")
//
//      val commandSink = ReactiveKafkaSink.atLeastOnce(config.messageFabricServers)
//      val responseSink = ReactiveKafkaSink.atLeastOnce(config.messageFabricServers)
//
//      val cmdRS = commandSource ~> createCommandRoute(processor.commandModifiers) ~> commandSink
//      val reqRS = requestSource ~> createRequestRoute(processor.queries) ~> responseSink

      ProcessorServer(
        processor,
        server
//        cmdRS,
//        reqRS
      )
    }

//    private def createCommandRoute(cmds: Set[Cmd[_]]) = {
//      ReactiveRoute(cmds.map { cmd =>
//        cmd.id -> ReactiveService(cmd.id)(cmd.func)
//      }.toMap)
//    }
//
//    private def createRequestRoute(reqs: Set[Ask[_]]) = {
//      ReactiveRoute(reqs.map { req =>
//        req.id -> ReactiveService(req.id)(req.func)
//      }.toMap)
//    }
  }

}

