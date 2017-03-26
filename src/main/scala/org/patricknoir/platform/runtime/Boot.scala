package org.patricknoir.platform.runtime

import org.patricknoir.platform.dsl._
import java.net.InetAddress

import akka.actor.ActorSystem
import akka.util.Timeout
import org.patricknoir.platform.protocol.{Command, Event, Request, Response}
import com.typesafe.scalalogging.LazyLogging
import org.patricknoir.platform._
import org.patricknoir.platform.runtime.Util.{CounterValueReq, CounterValueResp, DecrementCounterCmd, IncrementCounterCmd}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import io.circe.generic.auto._

/**
  * Created by patrick on 15/03/2017.
  */
object Boot extends App with LazyLogging {

  val bc = Util.bc

  implicit val system = ActorSystem("platform")
  implicit val materializer = ActorMaterializer()

  import system.dispatcher

  implicit val config = PlatformConfig.default
  implicit val timeout = Timeout(5 seconds)

  val runtime = platform(bc)

  val server = runtime.processorServers("counterProcessor").server

  server ! IncrementCounterCmd("Counter1", 1)
  server ! IncrementCounterCmd("Counter1", 1)
  server ! IncrementCounterCmd("Counter2", 1)
  server ! DecrementCounterCmd("Counter2", 1)

  val statusCounter1: Future[CounterValueResp] = (server ? CounterValueReq("Counter1")).mapTo[CounterValueResp]
  val statusCounter2: Future[CounterValueResp] = (server ? CounterValueReq("Counter2")).mapTo[CounterValueResp]

  Future.sequence(Set(statusCounter1, statusCounter2)).onComplete(println)

  runtime.run()

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
        case cmd @ DecrementCounterCmd(id, _) => (id, cmd)
      },
      eventKeyExtractor = PartialFunction.empty,
      queryKeyExtractor = {
        case req @ CounterValueReq(id) => (id, req)
      },
      dependencies = Set.empty,
      hashFunction = _.hashCode,
      shardSpaceSize = 100
    ),
    model = 0,
    //TODO:  Will be good the command()() DSL also includes the key extraction if we are using KeySharded strategy
    commandModifiers = Set(
      command("incrementCmd") { (counter: Int, ic: IncrementCounterCmd) =>
        (counter + ic.step, Seq(CounterIncrementedEvt(ic.id, ic.step)))
      },
      command("decrementCmd") { (counter: Int, ic: DecrementCounterCmd) =>
        (counter - ic.step, Seq(CounterDecrementedEvt(ic.id, ic.step)))
      }
    ),
    eventModifiers = Set.empty,
    queries = Set(
      request("counterValueReq") { (counter: Int, req: CounterValueReq) => CounterValueResp(req.id, counter) }
    )
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

  case class CounterValueReq(id: String) extends Request
  case class CounterValueResp(id: String, value: Int) extends Response

}
