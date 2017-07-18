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

  val counterProcessorDef = ProcessorDef[Int](
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
    propsFactory = (context: ComponentContext) =>
      ProcessorProps(
        commandModifiers = Set(
          command("incrementCmd") { (counter: Int, ic: IncrementCounterCmd) =>
            context.log("incrementCmd").info("Called with {}", counter)
            (counter + ic.step, Seq(CounterIncrementedEvt(ic.id, ic.step)))
          },
          command("decrementCmd") { (counter: Int, ic: DecrementCounterCmd) =>
            context.log("decrementCmd").info("Called with {}", counter)
            (counter - ic.step, Seq(CounterDecrementedEvt(ic.id, ic.step)))
          },
          command.async("incrementIfCmd")(
            timeout = Timeout(10 seconds),
            modifier = (counter: Int, ic: IncrementCounterIfCmd) => {
              // Following handling is just for demo purposes
              context.request[CounterValueReq, CounterValueResp](ServiceURL("counterBC", Version(1, 0, 0), "counterProcessor"), CounterValueReq(ic.id)).map(resp =>
                if (resp.value == ic.ifValue) (counter + ic.step, Seq(CounterIncrementedEvt(ic.id, ic.step)))
                else throw new RuntimeException(s"Value ${ic.ifValue} does not match returned: ${resp.value}") // This is the same as failing the future
              )
            }
          )
        ),
        eventModifiers = Set(),
        queries = Set(
          request("counterValueReq") { (counter: Int, req: CounterValueReq) =>
            context.log("counterValueReq").info("Handling request")
            CounterValueResp(req.id, counter)
          }
        )
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
    componentDefs = Set(counterProcessorDef)
  )

  val (installed, runtime) = Platform.install(bc)

  Await.ready(runtime, Duration.Inf)
  logger.info(s"Node ${InetAddress.getLocalHost.getHostName} terminated")

}