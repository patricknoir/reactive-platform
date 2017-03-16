package com.lottomart.platform.runtime.actors

import akka.actor.{ActorLogging, Props}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import cats.data.State
import com.lottomart.platform.{Processor, StatefulService}
import com.lottomart.platform.protocol.{Command, Event}
import com.lottomart.platform.runtime.actors.ProcessorActor.CompleteCommand

import scala.collection.immutable.Queue
import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * Created by patrick on 15/03/2017.
  */
class ProcessorActor[T](processor: Processor[T]) extends PersistentActor with ActorLogging { //with AtLeastOnceDelivery

  override val persistenceId = self.path.name

  import context.dispatcher

  var model = processor.model
  var pendingOperations: Queue[Command] = Queue.empty
  var completedCommandsSet: Set[CompleteCommand[T]] = Set.empty

  def findServiceForCommand(cmd: Command): Option[StatefulService[T, Command, Seq[Event]]] =
    processor.commandModifiers.find(_.func.isDefinedAt(cmd))


  override def receiveCommand = {
    case cmd: Command =>
      log.info(s"Received command: $cmd")
      findServiceForCommand(cmd).map { service =>
        val fState: Future[State[T, Seq[Event]]] = service.func(cmd)
        log.info(s"modifier found for command: $cmd")
        pendingOperations = pendingOperations.enqueue(cmd)
        fState.onComplete(r => self ! CompleteCommand[T](cmd, r.toEither))
      }
    case cc: CompleteCommand[T] =>
      log.info(s"future completed for command: ${cc.cmd}")
      handleCompleteCommand(cc)
    case evt: Event =>
      log.warning(s"Handling input events not implemented yet, received: $evt")
  }

  private def handleCompleteCommand(cc: CompleteCommand[T]) = {
    if(pendingOperations.head != cc.cmd) {
      log.info(s"Completed Command: ${cc.cmd} is not the first fired, adding to the waiting Set")
      completedCommandsSet = completedCommandsSet + cc
    } else {
      cc.result.fold(
        err => log.error(err, s"error processing command: ${cc.cmd}"),
        state => {
          val (newModel, events) = state.run(model).value
          log.info(s"Internal state for entity: $persistenceId updated to: $newModel")
          model = newModel
          fireEvents(events)
          //confirmDelivery(cc.hashCode)
        }
      )
    }
  }

  def fireEvents(events: Seq[Event]) = {
    events.foreach(e => s"Notifying event: $e")
  }

  override def receiveRecover = {
    case any => log.info(s"Nothing to do: $any")
  }

}

object ProcessorActor {
  def props(processor: Processor[_]): Props = Props(new ProcessorActor(processor))

  case class CompleteCommand[S](cmd: Command, result: Either[Throwable, State[S, Seq[Event]]])
}
