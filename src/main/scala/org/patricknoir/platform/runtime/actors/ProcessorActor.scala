package org.patricknoir.platform.runtime.actors

import java.util.concurrent.TimeoutException

import akka.actor.{ActorLogging, Props, ReceiveTimeout, Stash}
import akka.persistence.PersistentActor
import akka.util.Timeout
import cats.data.State
import org.patricknoir.platform.Processor
import org.patricknoir.platform.protocol.{Command, Event}
import org.patricknoir.platform.runtime.actors.ProcessorActor.CompleteCommand

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Created by patrick on 26/03/2017.
  */
class ProcessorActor[T](processor: Processor[T], timeout: Timeout) extends PersistentActor with Stash with ActorLogging {

  override val persistenceId = self.path.name

  import context.dispatcher

  var model: T = processor.model

  override def receiveCommand: Receive = {
    case cmd: Command =>
      handleCommand(cmd)
    case evt: Event =>
      log.warning(s"Handling input events not implemented yet, received: $evt")
  }

  override def receiveRecover: Receive = {
    case msg => log.warning(s"Recovery message received: $msg")
  }

  def handleCommand(cmd: Command) = {
    log.debug("Received command: $cmd")
    findServiceForCommand(cmd).map { service =>
      log.debug(s"Service for command: $cmd found: ${service.id}")
      val fState: Future[State[T, Seq[Event]]] = service.func(cmd)
      //TODO: if Service type is Sync avoid to send CompleteCommand, investigate performance impact
      fState.onComplete(r => self ! CompleteCommand[T](cmd, r.toEither))
      context.setReceiveTimeout(timeout.duration)
      context.become(awaitingCommandComplete(cmd), discardOld = false)
    }.orElse {
      log.warning(s"Command Modifier not found for command: $cmd")
      None
    }
  }

  def awaitingCommandComplete(cmd: Command): Receive = {
    case newCmd: Command => stash()
    case evt: Event =>
      log.warning(s"Handling input events not implemented yet, received: $evt")
    case cc : CompleteCommand[T] =>
      cc.result.fold(
        err => log.error(err, s"error processing command: ${cc.cmd}"),
        state => {
          val (newModel, events) = state.run(model).value
          log.info(s"Internal state for entity: $persistenceId updated to: $newModel")
          model = newModel
          fireEvents(events)
        }
      )
      context.unbecome()
      unstashAll()
    case ReceiveTimeout => throw new TimeoutException(s"Command Complete Timeout error: $cmd")
  }

  def findServiceForCommand(cmd: Command) = processor.commandModifiers.find(_.func.isDefinedAt(cmd))

  def fireEvents(events: Seq[Event]) = {
    events.foreach(e => s"Notifying event: $e")
  }
}

object ProcessorActor {
  def props(processor: Processor[_])(implicit timeout: Timeout): Props = Props(new ProcessorActor(processor, timeout))
  case class CompleteCommand[S](cmd: Command, result: Either[Throwable, State[S, Seq[Event]]])
}
