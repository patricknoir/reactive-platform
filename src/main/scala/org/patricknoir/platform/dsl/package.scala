package org.patricknoir.platform

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.util.Timeout
import cats.data.State
import org.patricknoir.kafka.reactive.common.{ReactiveDeserializer, ReactiveSerializer}
import org.patricknoir.platform.protocol.{Command, Event, Request, Response}
import org.patricknoir.platform.runtime.Platform

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.concurrent.duration._
import com.typesafe.config.{Config, ConfigFactory}

import scala.collection.convert.ImplicitConversionsToScala._

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
    zookeeperHosts: Set[String],
    serverDefaultTimeout: Timeout
  )

  object PlatformConfig {
    lazy val default = PlatformConfig.load()

    def load(config: Config = ConfigFactory.load("platform")) = PlatformConfig(
      messageFabricServers = config.getStringList("platform.fabric.message.hosts").toList.toSet,
      zookeeperHosts = config.getStringList("platform.fabric.message.zookeeper").toList.toSet,
      serverDefaultTimeout = Timeout(config.getDuration("platform.server.timeout").getSeconds, TimeUnit.SECONDS)
    )
  }

}

