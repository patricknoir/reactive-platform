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

  def request[Req <: Request, Resp <: Response, S](query: ComponentContext => (S, Req) => Resp)(id: String)(implicit reqCT: ClassTag[Req], respCT: ClassTag[Resp], deserializer: ReactiveDeserializer[Req], serializer: ReactiveSerializer[Resp]) = {
    val fc: PartialFunction[(ComponentContext, Request), State[S, Response]] = {
      case (ctx: ComponentContext, req: Req) => State.inspect(state => query(ctx)(state, req))
    }
    ContextStatefulServiceInfo[S, Request, Response](ContextStatefulService.sync[S, Request, Response](id, fc), deserializer, serializer)
  }

  def request[Req <: Request, Resp <: Response, S](query: (S, Req) => Resp)(id: String)(implicit reqCT: ClassTag[Req], respCT: ClassTag[Resp], deserializer: ReactiveDeserializer[Req], serializer: ReactiveSerializer[Resp]) = {
    val fc: PartialFunction[(ComponentContext, Request), State[S, Response]] = {
      case (_: ComponentContext, req: Req) => State.inspect(state => query(state, req))
    }
    ContextStatefulServiceInfo[S, Request, Response](ContextStatefulService.sync[S, Request, Response](id, fc), deserializer, serializer)
  }

  def command[C <: Command, E <: Event, S](modifier: ComponentContext => (S, C) => (S, E))(id: String)(implicit ct: ClassTag[C], ect: ClassTag[E], deserializer: ReactiveDeserializer[C], serializer: ReactiveSerializer[E]) = {
    val fc: PartialFunction[(ComponentContext, Command), State[S, Event]] = {
      case (ctx: ComponentContext, cmd: C) => State(init => modifier(ctx)(init, cmd))
    }
    ContextStatefulServiceInfo[S, Command, Event](ContextStatefulService.sync[S, Command, Event](id, fc), deserializer, serializer)
  }

  def command[C <: Command, E <: Event, S](modifier: (S, C) => (S, E))(id: String)(implicit ct: ClassTag[C], ect: ClassTag[E], deserializer: ReactiveDeserializer[C], serializer: ReactiveSerializer[E]) = {
    val fc: PartialFunction[(ComponentContext, Command), State[S, Event]] = {
      case (_: ComponentContext, cmd: C) => State(init => modifier(init, cmd))
    }
    ContextStatefulServiceInfo[S, Command, Event](ContextStatefulService.sync[S, Command, Event](id, fc), deserializer, serializer)
  }

  def asyncCommand[C <: Command, E <: Event, S](timeout: Timeout, modifier: (ComponentContext, S, C) => Future[(S, E)])(id: String)(implicit ct: ClassTag[C], ect: ClassTag[E], deserializer: ReactiveDeserializer[C], serializer: ReactiveSerializer[E]) = {
    val fc: PartialFunction[(ComponentContext, Command), State[S, Event]] = {
      case (ctx: ComponentContext, cmd: C) =>
        State { init =>
          Await.result(modifier(ctx, init, cmd), timeout.duration) //can I avoid this blocking?
        }
    }
    ContextStatefulServiceInfo[S, Command, Event](ContextStatefulService.async[S, Command, Event](id, fc), deserializer, serializer)
  }

  def recovery[E <: Event, S](rec: (S, E) => S)(id: String)(implicit ect: ClassTag[E]): Recovery[S] = {
    val pf: PartialFunction[Event, State[S, Unit]] = {
      case event: E => State(init => (rec(init, event), ()))
    }
    StatefulService.sync[S, Event, Unit](id, pf)
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

