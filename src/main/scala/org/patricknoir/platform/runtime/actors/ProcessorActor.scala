package org.patricknoir.platform.runtime.actors

import java.util.concurrent.TimeoutException

import akka.actor.{ActorLogging, ActorRef, Props, ReceiveTimeout, Stash, Status}
import akka.persistence.PersistentActor
import akka.util.Timeout
import org.patricknoir.platform.{AsyncStatefulService, Processor, SyncStatefulService}
import org.patricknoir.platform.protocol.{Command, Event, Request, Response}
import org.patricknoir.platform.runtime.actors.ProcessorActor.CompleteCommand

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.Try

/**
  * Created by patrick on 26/03/2017.
  */
class ProcessorActor[T](processor: Processor[T], timeout: Timeout) extends PersistentActor with Stash with ActorLogging {

  val defaulEc: ExecutionContext = context.dispatcher
  val asyncCmdEc: ExecutionContext = context.system.dispatchers.lookup(context.system.settings.config.getString("platform.server.async-command-dispatcher"))

  override val persistenceId = self.path.name

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
      val resp = service.func(req).run(model).value._2
      log.debug(s"Replying to request: $req with response: $resp")
      origin ! resp
    }.orElse {
      log.warning(s"Service not found for request: $req")
      origin ! Status.Failure(new RuntimeException(s"Service not found for request: $req")) // FIXME: Domain-specific Exception
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
      None // FIXME: Need to reply in order to get the future completed
    }
  }

  private def handleSyncCommand(svc: SyncStatefulService[T, Command, Seq[Event]], cmd: Command, origin: ActorRef) = {
    Try {
      svc.func(cmd)
    }.map { state =>
      val (newModel, events) = state.run(model).value
      log.info(s"Internal state for entity: $persistenceId updated to: $newModel")
      updateStateAndReply((newModel, events), origin)
    }.recover { case err: Throwable =>
      reportErrorAndReply(err, cmd, origin)
    }

  }

  private def handleAsyncCommand(svc: AsyncStatefulService[T, Command, Seq[Event]], cmd: Command, origin: ActorRef) = {
    log.debug(s"Service for command: $cmd found: ${svc.id}")
    implicit val ec = asyncCmdEc
    Future {
      svc.func(cmd).run(model).value
    }.onComplete(r =>
      self ! CompleteCommand[T](cmd, r.toEither, origin)
    )
    context.setReceiveTimeout(timeout.duration)
    context.become(awaitingCommandComplete(cmd, origin), discardOld = false)
  }

  private def reportErrorAndReply(err: Throwable, cmd: Command, origin: ActorRef) = {
    log.error(err, s"Error processing command: ${cmd}")
    origin ! Status.Failure(err)
  }

  private def updateStateAndReply(valueAndEvents: (T, Seq[Event]), origin: ActorRef) = {
    model = valueAndEvents._1
    origin ! valueAndEvents._2
  }

  private def awaitingCommandComplete(cmd: Command, origin: ActorRef): Receive = {
    case newCmd: Command =>
      stash()
    case evt: Event =>
      log.warning(s"Handling input events not implemented yet, received: $evt")
    case req: Request =>
      handleRequest(req, sender)
    case cc: CompleteCommand[T] =>
      if (cc.cmd == cmd) { // FIXME: Safer comparison ???
        cc.result.fold(
          err => reportErrorAndReply(err, cc.cmd, origin),
          stateAndEvents => updateStateAndReply(stateAndEvents, cc.origin)
        )
        restoreBehaviour()
      } else {
        // This can happen when a previous command timed out
        log.warning("Received CompleteCommand for command {} while awaiting for {}, skipping ...", cc.cmd, cmd)
      }
    case ReceiveTimeout =>
      origin ! Status.Failure(new TimeoutException(s"Command Complete Timeout error: $cmd"))
      restoreBehaviour()
  }

  private def restoreBehaviour() = {
    context.unbecome()
    context.setReceiveTimeout(Duration.Undefined)
    unstashAll()
  }

  private def findServiceForCommand(cmd: Command) = processor.props.commandModifiers.map(_.service).find(_.func.isDefinedAt(cmd))
  private def findServiceForEvent(evt: Event) = processor.props.eventModifiers.find(_.func.isDefinedAt(evt))
  private def findServiceForQuery(req: Request) = processor.props.queries.map(_.service).find(_.func.isDefinedAt(req))

}

object ProcessorActor {
  def props(processor: Processor[_])(implicit timeout: Timeout): Props = Props(new ProcessorActor(processor, timeout))
  case class CompleteCommand[S](cmd: Command, result: Either[Throwable, (S, Seq[Event])], origin: ActorRef)
}
