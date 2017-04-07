package org.patricknoir.platform.runtime

import org.patricknoir.platform.dsl._
import java.net.InetAddress

import akka.actor.ActorSystem
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging
import org.patricknoir.platform._

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.stream.ActorMaterializer
import org.patricknoir.platform.Util._

/**
  * Created by patrick on 15/03/2017.
  */
object Boot extends App with LazyLogging {

  import io.circe.generic.auto._

  // TODO: ??? had to take this out from the constructor
  val counterDescriptor = KeyShardedProcessDescriptor(
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
  )

  val counterProcessor = new Processor[Int](
    id = "counterProcessor",
    version = Version(1, 0, 0),
    context = DefaultComponentContextImpl(), // FIXME:
    descriptor = counterDescriptor,
    model = 0
  ) {

    // FIXME: This execution context should not be configured like this !!!
    import scala.concurrent.ExecutionContext.Implicits.global

    implicit val defaultTimeout = Timeout(10 seconds)

    override def commandModifiers() = Set(
      command("incrementCmd") { (counter: Int, ic: IncrementCounterCmd) =>
        (counter + ic.step, Seq(CounterIncrementedEvt(ic.id, ic.step)))
      },
      command("decrementCmd") { (counter: Int, ic: DecrementCounterCmd) =>
        (counter - ic.step, Seq(CounterDecrementedEvt(ic.id, ic.step)))
      },
      command.async("incrementIfCmd") { (counter: Int, ic: IncrementCounterIfCmd) =>
        // Following handling is just for demo purposes
        context.request[CounterValueReq, CounterValueResp]("counterProcessor", CounterValueReq(ic.id)).map(resp =>
          if (resp.value == ic.ifValue) (counter + ic.step, Seq(CounterIncrementedEvt(ic.id, ic.step)))
          else throw new RuntimeException(s"Value ${ic.ifValue} does not match returned: ${resp.value}")
        )
      }
    )
    override def eventModifiers() = Set.empty
    override def queries() = Set(
      request("counterValueReq") { (counter: Int, req: CounterValueReq) => CounterValueResp(req.id, counter) }
    )
  }

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

  import Util._

  implicit val system = ActorSystem("platform")
  implicit val materializer = ActorMaterializer()

  import system.dispatcher

  implicit val config = PlatformConfig.default
  implicit val timeout = Timeout(5 seconds)

  val runtime = Platform.install(bc)

  Await.ready(system.whenTerminated, Duration.Inf)
  logger.info(s"Node ${InetAddress.getLocalHost.getHostName} terminated")
}