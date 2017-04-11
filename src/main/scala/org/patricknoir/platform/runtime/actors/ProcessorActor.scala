package org.patricknoir.platform.runtime.actors

import java.util.concurrent.TimeoutException

import akka.actor.{ActorLogging, ActorRef, Props, ReceiveTimeout, Stash}
import akka.persistence.PersistentActor
import akka.util.Timeout
import cats.data.State
import org.patricknoir.platform.{AsyncStatefulService, Cmd, Processor, SyncStatefulService}
import org.patricknoir.platform.protocol.{Command, Event, Request, Response}
import org.patricknoir.platform.runtime.actors.ProcessorActor.CompleteCommand

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * Created by patrick on 26/03/2017.
  */
class ProcessorActor[T](processor: Processor[T], timeout: Timeout) extends PersistentActor with Stash with ActorLogging {

  override val persistenceId = self.path.name

  import context.dispatcher

  var model: T = processor.model

  override def receiveCommand: Receive = {
    case cmd: Command =>
      handleCommand(cmd, sender)
    case evt: Event =>
      log.warning(s"Handling input events not implemented yet, received: $evt")
    case req: Request =>
      handleRequest(req, sender)
    case other => log.warning(s"Unhandled message: $other")
  }

  override def receiveRecover: Receive = {
    case msg => log.warning(s"Recovery message received: $msg")
  }

  private def handleRequest(req: Request, origin: ActorRef) = {
    log.debug(s"Receoved request: $req")
    findServiceForQuery(req).map { service =>
      log.debug(s"Service for request: $req found: ${service.id}")
      val fState: Future[State[T, Response]] = service.func(req)
      fState.onComplete {
        case Success(s)   =>
          val resp = s.run(model).value._2
          log.debug(s"Replying to request: $req with response: $resp")
          origin ! resp
        case Failure(err) =>
          log.error(s"Error failed for request: $req")
      }
    }.orElse {
      log.warning(s"Service not found for request: $req")
      None
    }
  }

  private def handleCommand(cmd: Command, origin: ActorRef) = {
    log.debug(s"Received command: $cmd")
    findServiceForCommand(cmd).map {
      case asyncService: AsyncStatefulService[T, Command, Seq[Event]] => handleAsyncCommand(asyncService, cmd, origin)
      case syncService: SyncStatefulService[T, Command, Seq[Event]] => handleSyncCommand(syncService, cmd, origin)
    }.orElse {
      log.warning(s"Service not found for command: $cmd")
      None
    }
  }

  private def handleAsyncCommand(service: Cmd[T], cmd: Command, origin: ActorRef) = {
    log.debug(s"Service for command: $cmd found: ${service.id}")
    val fState: Future[State[T, Seq[Event]]] = service.func(cmd)
    fState.onComplete(r => self ! CompleteCommand[T](cmd, r.toEither, origin))
    context.setReceiveTimeout(timeout.duration)
    context.become(awaitingCommandComplete(cmd), discardOld = false)
  }

  private def update(state: State[T, Seq[Event]]): Seq[Event] = {
    val (newModel, events) = state.run(model).value
    log.info(s"Internal state for entity: $persistenceId updated to: $newModel")
    model = newModel
    events
  }

  private def handleSyncCommand(service: SyncStatefulService[T, Command, Seq[Event]], cmd: Command, origin: ActorRef) = {
    val state: State[T, Seq[Event]] = service.syncFunc(cmd)
    val evs = update(state)
    origin ! evs
  }

  private def awaitingCommandComplete(cmd: Command): Receive = {
    case newCmd: Command => stash()
    case evt: Event =>
      log.warning(s"Handling input events not implemented yet, received: $evt")
    case req: Request =>
      handleRequest(req, sender)
    case cc : CompleteCommand[T] =>
      cc.result.fold(
        err =>
          log.error(err, s"error processing command: ${cc.cmd}"),
        state => {
          val evs = update(state)
          cc.origin ! evs
        }
      )
      context.unbecome()
      unstashAll()
    case ReceiveTimeout => throw new TimeoutException(s"Command Complete Timeout error: $cmd")
  }

  private def findServiceForCommand(cmd: Command) = processor.props.commandModifiers.map(_.service).find(_.func.isDefinedAt(cmd))
  private def findServiceForEvent(evt: Event) = processor.props.eventModifiers.find(_.func.isDefinedAt(evt))
  private def findServiceForQuery(req: Request) = processor.props.queries.map(_.service).find(_.func.isDefinedAt(req))

}

object ProcessorActor {
  def props(processor: Processor[_])(implicit timeout: Timeout): Props = Props(new ProcessorActor(processor, timeout))
  case class CompleteCommand[S](cmd: Command, result: Either[Throwable, State[S, Seq[Event]]], origin: ActorRef)
}
