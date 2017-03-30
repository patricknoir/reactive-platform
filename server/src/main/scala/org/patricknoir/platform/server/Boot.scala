package org.patricknoir.platform.runtime

import org.patricknoir.platform.dsl._
import java.net.InetAddress

import akka.actor.ActorSystem
import akka.util.Timeout
import org.patricknoir.platform.protocol.{Command, Event, Request, Response}
import com.typesafe.scalalogging.LazyLogging
import org.patricknoir.platform._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import org.patricknoir.platform.Util._

/**
  * Created by patrick on 15/03/2017.
  */
object Boot extends App with LazyLogging {

  import io.circe.generic.auto._

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

  import Util._

  implicit val system = ActorSystem("platform")
  implicit val materializer = ActorMaterializer()

  import system.dispatcher

  implicit val config = PlatformConfig.default
  implicit val timeout = Timeout(5 seconds)

  val runtime = platform(bc)

  runtime.run()

  Await.ready(system.whenTerminated, Duration.Inf)
  logger.info(s"Node ${InetAddress.getLocalHost.getHostName} terminated")
}