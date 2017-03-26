package org.patricknoir.platform.runtime

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.Materializer
import org.patricknoir.kafka.reactive.server.ReactiveSystem
import org.patricknoir.platform.dsl.{PlatformConfig, platform}
import org.patricknoir.platform.{BoundedContext, Processor}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by patrick on 26/03/2017.
  */
case class Platform(
  val processorServers: Map[String, ProcessorServer]
) {
  def run()(implicit ec: ExecutionContext, materializer: Materializer): Future[Unit] = {
    processorServers.values.map(_.queryReactiveSystem.run())
    Future.successful[Unit](()) //FIXME
  }
}

object Platform {
  def apply(bc: BoundedContext)(implicit system: ActorSystem, config: PlatformConfig): Platform = platform(bc)
}

case class ProcessorServer(
  processor: Processor[_],
  server: ActorRef,
  queryReactiveSystem: ReactiveSystem
  //  commandReactiveSystem: ReactiveSystem
)


