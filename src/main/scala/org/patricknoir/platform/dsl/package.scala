package org.patricknoir.platform

import akka.util.Timeout
import cats.data.State
import org.patricknoir.platform.protocol.{Command, Event}
import org.patricknoir.platform.runtime.Util.{CounterDecrementedEvt, CounterIncrementedEvt, DecrementCounterCmd, IncrementCounterCmd}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.reflect.ClassTag

/**
  * Created by patrick on 20/03/2017.
  */
package object dsl {

  object command {
    def apply[C <: Command, S](id: String)(modifier: (S, C) => (S, Seq[Event]))(implicit ct: ClassTag[C]) = {
      val fc: PartialFunction[Command, Future[State[S, Seq[Event]]]] = {
        case cmd: C => Future.successful(State(init => modifier(init, cmd)))
      }
      StatefulService[S, Command, Seq[Event]](id, fc)
    }

    def async[C <: Command, S](id: String)(modifier: (S, C) => Future[(S, Seq[Event])])(implicit ec: ExecutionContext, timeout: Timeout, ct: ClassTag[C]) = {
      val fc: PartialFunction[Command, Future[State[S, Seq[Event]]]] = {
        case cmd: C =>
          Future(State { init =>
            Await.result(modifier(init, cmd), timeout.duration) //can I avoid this blocking?
          })
      }
      StatefulService[S, Command, Seq[Event]](id, fc)
    }
  }

  object processor {
    def apply[W](id: String, init: W, version: Version = Version(1, 0, 0))(descriptor: ProcessorDescriptor)(modifiers: Cmd[W]*): Processor[W] = {
      Processor[W](
        id = "counterProcessor",
        version = Version(1, 0, 0),
        descriptor = descriptor,
        model = init,
        //TODO:  Will be good the command()() DSL also includes the key extraction if we are using KeySharded strategy
        commandModifiers = modifiers.toSet,
        eventModifiers = Set.empty
      )
    }
  }

  object ProcessorExample {
    val counterProcessor = processor[Int]("counterProcessor", 0)(KeyShardedProcessDescriptor(
      commandKeyExtractor = {
        case cmd @ IncrementCounterCmd(id, _) => (id, cmd)
        case cmd @ DecrementCounterCmd(id, _) => (id, cmd)
      },
      eventKeyExtractor = PartialFunction.empty,
      dependencies = Set.empty,
      hashFunction = _.hashCode,
      shardSpaceSize = 100
    )) (
      command("incrementCmd") { (counter: Int, ic: IncrementCounterCmd) =>
        (counter + ic.step, Seq(CounterIncrementedEvt(ic.id, ic.step)))
      },
      command("decrementCmd") { (counter: Int, dc: DecrementCounterCmd) =>
        (counter - dc.step, Seq(CounterDecrementedEvt(dc.id, dc.step)))
      }
    )
  }
}

