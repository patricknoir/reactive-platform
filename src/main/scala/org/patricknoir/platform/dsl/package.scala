package org.patricknoir.platform

import java.util.concurrent.TimeUnit

import akka.util.Timeout
import cats.data.State
import org.patricknoir.kafka.reactive.common.{ReactiveDeserializer, ReactiveSerializer}
import org.patricknoir.platform.protocol.{Command, Event, Request, Response}

import scala.concurrent.{Await, Future}
import scala.reflect.ClassTag
import com.typesafe.config.{Config, ConfigFactory}

import scala.collection.convert.ImplicitConversionsToScala._
import scala.concurrent.duration.FiniteDuration

/**
  * Created by patrick on 20/03/2017.
  */
package object dsl {

  object request {
    def apply[Req <: Request, Resp <: Response, S](id: String)(query: (ComponentContext, S, Req) => Resp)(implicit reqCT: ClassTag[Req], respCT: ClassTag[Resp], deserializer: ReactiveDeserializer[Req], serializer: ReactiveSerializer[Resp]) = {
      val fc: PartialFunction[(ComponentContext, Request), State[S, Response]] = {
        case (ctx: ComponentContext, req: Req) => State.inspect(state => query(ctx, state, req))
      }
      ContextStatefulServiceInfo[S, Request, Response](ContextStatefulService.sync[S, Request, Response](id, fc), deserializer, serializer)
    }
  }

  @deprecated
  object deprecateCommand {
    def apply[C <: Command, E <: Event, S](id: String)(modifier: (S, C) => (S, Seq[E]))(implicit ct: ClassTag[C], ect: ClassTag[E], deserializer: ReactiveDeserializer[C], serializer: ReactiveSerializer[Seq[E]]) = {
      val fc: PartialFunction[Command, State[S, Seq[Event]]] = {
        case cmd: C => State(init => modifier(init, cmd))
      }
      StatefulServiceInfo[S, Command, Seq[Event]](StatefulService.sync[S, Command, Seq[Event]](id, fc), deserializer, serializer)
    }

    def async[C <: Command, E <: Event, S](id: String)(timeout: Timeout, modifier: (S, C) => Future[(S, Seq[E])])(implicit ct: ClassTag[C], ect: ClassTag[E], deserializer: ReactiveDeserializer[C], serializer: ReactiveSerializer[Seq[E]]) = {
      val fc: PartialFunction[Command, State[S, Seq[Event]]] = {
        case cmd: C =>
          State { init =>
            Await.result(modifier(init, cmd), timeout.duration) //can I avoid this blocking?
          }
      }
      StatefulServiceInfo[S, Command, Seq[Event]](StatefulService.async[S, Command, Seq[Event]](id, fc), deserializer, serializer)
    }
  }

  object command {
    def apply[C <: Command, E <: Event, S](id: String)(modifier: (ComponentContext, S, C) => (S, Seq[E]))(implicit ct: ClassTag[C], ect: ClassTag[E], deserializer: ReactiveDeserializer[C], serializer: ReactiveSerializer[Seq[E]]) = {
      val fc: PartialFunction[(ComponentContext, Command), State[S, Seq[Event]]] = {
        case (ctx: ComponentContext, cmd: C) => State(init => modifier(ctx, init, cmd))
      }
      ContextStatefulServiceInfo[S, Command, Seq[Event]](ContextStatefulService.sync[S, Command, Seq[Event]](id, fc), deserializer, serializer)
    }

    def simpleApply[C <: Command, E <: Event, S](id: String)(modifier: (S, C) => (S, Seq[E]))(implicit ct: ClassTag[C], ect: ClassTag[E], deserializer: ReactiveDeserializer[C], serializer: ReactiveSerializer[Seq[E]]) = {
      val fc: PartialFunction[(ComponentContext, Command), State[S, Seq[Event]]] = {
        case (ctx: ComponentContext, cmd: C) => State(init => modifier(init, cmd))
      }
      ContextStatefulServiceInfo[S, Command, Seq[Event]](ContextStatefulService.sync[S, Command, Seq[Event]](id, fc), deserializer, serializer)
    }

    def async[C <: Command, E <: Event, S](id: String)(timeout: Timeout, modifier: (ComponentContext, S, C) => Future[(S, Seq[E])])(implicit ct: ClassTag[C], ect: ClassTag[E], deserializer: ReactiveDeserializer[C], serializer: ReactiveSerializer[Seq[E]]) = {
      val fc: PartialFunction[(ComponentContext, Command), State[S, Seq[Event]]] = {
        case (ctx: ComponentContext, cmd: C) =>
          State { init =>
            Await.result(modifier(ctx, init, cmd), timeout.duration) //can I avoid this blocking?
          }
      }
      ContextStatefulServiceInfo[S, Command, Seq[Event]](ContextStatefulService.async[S, Command, Seq[Event]](id, fc), deserializer, serializer)
    }
  }

  case class PlatformConfig(
    messageFabricServers: Set[String],
    zookeeperHosts: Set[String],
    serverDefaultTimeout: Timeout,
    zkMinBackOff: FiniteDuration,
    zkMaxBackOff: FiniteDuration,
    registryHost: String,
    registryPort: Int
  )

  object PlatformConfig {
    lazy val default = PlatformConfig.load()

    def load(config: Config = ConfigFactory.load()) = PlatformConfig(
      messageFabricServers = config.getStringList("platform.fabric.message.hosts").toList.toSet,
      zookeeperHosts = config.getStringList("platform.fabric.message.zookeeper").toList.toSet,
      serverDefaultTimeout = Timeout(config.getDuration("platform.server.timeout").getSeconds, TimeUnit.SECONDS),
      zkMinBackOff = FiniteDuration(config.getDuration("platform.fabric.message.backoff.min").getSeconds, TimeUnit.SECONDS),
      zkMaxBackOff = FiniteDuration(config.getDuration("platform.fabric.message.backoff.max").getSeconds, TimeUnit.SECONDS),
      registryHost = config.getString("platform.registry.host"),
      registryPort = config.getInt("platform.registry.port")
    )
  }

}

