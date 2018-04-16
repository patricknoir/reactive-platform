package org.patricknoir.platform.runtime

import org.patricknoir.platform.dsl._
import java.net.InetAddress

import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import org.patricknoir.platform._

/**
  * Created by patrick on 15/03/2017.
  */
object Boot extends App with LazyLogging {

  import io.circe.generic.auto._
  import Util._
  import scala.concurrent.ExecutionContext.Implicits.global

  implicit val timeout = Timeout(10 seconds)

  val counterProcessor = Processor[Int](
    id = "counterProcessor",
    version = Version(1, 0, 0),
    descriptor = KeyShardedProcessDescriptor(
      commandKeyExtractor = {
        case cmd @ IncrementCounterCmd(id, _) => (id, cmd)
        case cmd @ DecrementCounterCmd(id, _) => (id, cmd)
        case cmd @ IncrementCounterIfCmd(id, _, _) => (id, cmd)
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
      command{ ctx => (counter: Int, ic: IncrementCounterCmd) =>
        ctx.log("incrementCmd").info("Called with {}", counter)
        (counter + ic.step, CounterIncrementedEvt(ic.id, ic.step))
      }("incrementCmd"),
      command { ctx =>  (counter: Int, ic: DecrementCounterCmd) =>
        ctx.log("decrementCmd").info("Called with {}", counter)
        (counter - ic.step, CounterDecrementedEvt(ic.id, ic.step))
      }("decrementCmd"),
      asyncCommand(
        timeout = Timeout(10 seconds),
        modifier = (context: ComponentContext, counter: Int, ic: IncrementCounterIfCmd) => {
          // Following handling is just for demo purposes
          context.request[CounterValueReq, CounterValueResp](ServiceURL("counterBC", Version(1, 0, 0), "counterProcessor"), CounterValueReq(ic.id)).map(resp =>
            if (resp.value == ic.ifValue) (counter + ic.step, CounterIncrementedEvt(ic.id, ic.step))
            else throw new RuntimeException(s"Value ${ic.ifValue} does not match returned: ${resp.value}") // This is the same as failing the future
          )
        }
      )("incrementIfCmd")
    ),
    eventModifiers = Set(),
    queries = Set(
      request { ctx: ComponentContext => (counter: Int, req: CounterValueReq) =>
        ctx.log.info("Handling request")
        CounterValueResp(req.id, counter)
      }("counterValueReq")
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
    auditMailboxName = "auditing",
    logMailboxName = "logging",
    components = Set(counterProcessor)
  )

  val (installed, runtime) = Platform.install(bc)

  Await.ready(runtime, Duration.Inf)
  logger.info(s"Node ${InetAddress.getLocalHost.getHostName} terminated")

}