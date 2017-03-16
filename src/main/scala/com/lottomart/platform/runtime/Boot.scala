package com.lottomart.platform.runtime

import java.net.InetAddress

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import cats.data.State
import com.lottomart.platform._
import com.lottomart.platform.protocol.{Command, Event}
import com.lottomart.platform.runtime.Util.IncrementCounterCmd
import com.lottomart.platform.runtime.actors.ProcessorActor
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

/**
  * Created by patrick on 15/03/2017.
  */
object Boot extends App with LazyLogging {

  val bc = Util.bc

  implicit val system = ActorSystem("platform")


  val clusters: Map[String, ActorRef] = bc.components.filter(_.isInstanceOf[Processor[_]]).map { component =>
    val processor = component.asInstanceOf[Processor[_]]
    val descriptor = processor.descriptor.asInstanceOf[KeyShardedProcessDescriptor]

    val extractIdFunction: PartialFunction[Any, (String, Any)] = {
      case cmd: Command => descriptor.commandKeyExtractor(cmd)
      case evt: Event => descriptor.eventKeyExtractor(evt)
    }
    val extractShardIdFunction = extractIdFunction.andThen(res => (descriptor.hashFunction(res._1) % descriptor.shardSpaceSize).toString)
    (
      processor.id,
      ClusterSharding(system).start(
        typeName = processor.id,
        entityProps = ProcessorActor.props(processor),
        settings = ClusterShardingSettings(system),
        extractEntityId = extractIdFunction,
        extractShardId = extractShardIdFunction
      )
    )
  }.toMap

  clusters("counterProcessor") ! IncrementCounterCmd("Counter1", 1)
  clusters("counterProcessor") ! IncrementCounterCmd("Counter1", 1)
  clusters("counterProcessor") ! IncrementCounterCmd("Counter2", 1)

  Await.ready(system.whenTerminated, Duration.Inf)
  logger.info(s"Node ${InetAddress.getLocalHost.getHostName} terminated")
}

object Util {

  val incrementSFService = StatefulService[Int, Command, Seq[Event]]("incrementCmd", {
    case cmd: IncrementCounterCmd =>
      val response: State[Int, Seq[Event]] = State.modify[Int](previous => previous + cmd.step).transform((s, _) => (s, Seq(CounterIncrementedEvt(cmd.id, cmd.step))))
      Future.successful[State[Int, Seq[Event]]](response)
  })

  val counterProcessor = Processor[Int](
    id = "counterProcessor",
    version = Version(1, 0, 0),
    descriptor = KeyShardedProcessDescriptor(
      commandKeyExtractor = {
        case cmd @ IncrementCounterCmd(id, _) => (id, cmd)
      },
      eventKeyExtractor = PartialFunction.empty,
      dependencies = Set.empty,
      hashFunction = _.hashCode,
      shardSpaceSize = 100
    ),
    model = 0,
    commandModifiers = Set(incrementSFService),
    eventModifiers = Set.empty
  )

  val bc = BoundedContext(
    id = "counterBC",
    version = Version(1, 0, 0),
    requestMailboxName = "requests",
    responseMailboxName = "responses",
    commandMailboxName = "commands",
    eventMailboxName = "events",
    failureMailboxName = "failures",
    auditingMailboxName = "auditing",
    loggingMailboxName = "logging",
    components = Set(counterProcessor)
  )

  case class IncrementCounterCmd(id: String, step: Int) extends Command
  case class CounterIncrementedEvt(id: String, step: Int) extends Event

}
