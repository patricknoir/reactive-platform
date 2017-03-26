package org.patricknoir.platform.runtime

import akka.actor.{ActorRef, ActorSystem}
import org.patricknoir.kafka.reactive.server.ReactiveSystem
import org.patricknoir.platform.dsl.{PlatformConfig, platform}
import org.patricknoir.platform.{BoundedContext, Processor}

/**
  * Created by patrick on 26/03/2017.
  */
case class Platform(
  val processorServers: Map[String, ProcessorServer]
)

object Platform {
  def apply(bc: BoundedContext)(implicit system: ActorSystem, config: PlatformConfig): Platform = platform(bc)
}

case class ProcessorServer(
  processor: Processor[_],
  server: ActorRef
//  commandReactiveSystem: ReactiveSystem,
//  queryReactiveSystem: ReactiveSystem
)


