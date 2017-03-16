package org.patricknoir.platform.runtime

import java.net.InetAddress

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.util.Timeout
import cats.data.State
import org.patricknoir.platform.protocol.{Command, Event}
import com.typesafe.scalalogging.LazyLogging
import org.patricknoir.platform._
import org.patricknoir.platform.runtime.Util.{DecrementCounterCmd, IncrementCounterCmd}
import org.patricknoir.platform.runtime.actors.ProcessorActor

import scala.concurrent.{Await, ExecutionContext, Future}
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
  clusters("counterProcessor") ! DecrementCounterCmd("Counter2", 1)

  Await.ready(system.whenTerminated, Duration.Inf)
  logger.info(s"Node ${InetAddress.getLocalHost.getHostName} terminated")
}

object Util {


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
    commandModifiers = Set(
      command("incrementCmd") { (counter: Int, ic: IncrementCounterCmd) =>
        (counter + ic.step, Seq(CounterIncrementedEvt(ic.id, ic.step)))
      },
      command("decrementCmd") { (counter: Int, ic: DecrementCounterCmd) =>
        (counter - ic.step, Seq(CounterDecrementedEvt(ic.id, ic.step)))
      }
    ),
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
  case class DecrementCounterCmd(id: String, step: Int) extends Command
  case class CounterDecrementedEvt(id: String, step: Int) extends Event

  object command {
    def apply[C <: Command, S](id: String)(modifier: (S, C) => (S, Seq[Event])) = {
      val fc: PartialFunction[Command, Future[State[S, Seq[Event]]]] = {
        case cmd: C => Future.successful(State(init => modifier(init, cmd)))
      }
      StatefulService[S, Command, Seq[Event]](id, fc)
    }

    def async[C <: Command, S](id: String)(modifier: (S, C) => Future[(S, Seq[Event])])(implicit ec: ExecutionContext, timeout: Timeout) = {
      val fc: PartialFunction[Command, Future[State[S, Seq[Event]]]] = {
        case cmd: C =>
          Future(State { init =>
            Await.result(modifier(init, cmd), timeout.duration) //can I avoid this blocking?
          })
      }
      StatefulService[S, Command, Seq[Event]](id, fc)
    }
  }

}
