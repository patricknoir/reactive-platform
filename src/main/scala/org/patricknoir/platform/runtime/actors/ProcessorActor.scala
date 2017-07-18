package org.patricknoir.platform.runtime.actors

import java.util.concurrent.TimeoutException

import akka.actor.{ActorLogging, ActorRef, Props, ReceiveTimeout, Stash, Status}
import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.util.Timeout
import cats.data.State
import org.patricknoir.platform._
import org.patricknoir.platform.protocol.{Command, Event, Request, Response}
import org.patricknoir.platform.runtime.actors.ProcessorActor.{CompleteCommand, EventRecover, WaitingComplete}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.Try

/**
  * Created by patrick on 26/03/2017.
  */
class ProcessorActor[T](ctx: ComponentContext, processor: Processor[T], timeout: Timeout) extends PersistentActor with Stash with ActorLogging {

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

  var isWaitingComplete: Option[WaitingComplete] = None

  override def receiveRecover: Receive = {
    case eventRecover: EventRecover[T] =>
      val (newState, _) = eventRecover.stateM.run(model).value
      model = newState
      isWaitingComplete = None
    case wc: WaitingComplete =>
      isWaitingComplete = Some(wc)
    case RecoveryCompleted =>
      isWaitingComplete.map {
        case WaitingComplete(cmd, origin) =>
          context.setReceiveTimeout(timeout.duration)
          context.become(awaitingCommandComplete(cmd, origin))
      }
    case msg => log.warning(s"Recovery message received: $msg")
  }

  private def handleRequest(req: Request, origin: ActorRef) = {
    log.debug(s"Receoved request: $req")
    findServiceForQuery(req).map { service =>
      log.debug(s"Service for request: $req found: ${service.id}")
      val resp = service.func(ctx, req).run(model).value._2
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
      case ctxService: ContextStatefulService[T, Command, Seq[Event]] => handleCtxCommand(ctxService, cmd, origin)
    }.orElse {
      log.warning(s"Service not found for command: $cmd")
      None // FIXME: Need to reply in order to get the future completed
    }
  }

  private def handleCtxCommand(css: ContextStatefulService[T, Command, Seq[Event]], cmd: Command, origin: ActorRef) = {
    Try {
      css.func(ctx, cmd)
    }.map { stateM =>
      persist(EventRecover(stateM)) { event =>
        val state = event.stateM
        val (newModel, events) = state.run(model).value
        log.info(s"Internal state for entity: $persistenceId updated to: $newModel")
        updateStateAndReply((newModel, events), origin)
      }
    }.recover { case err: Throwable =>
      reportErrorAndReply(err, cmd, origin)
    }
  }

  private def handleSyncCommand(svc: SyncStatefulService[T, Command, Seq[Event]], cmd: Command, origin: ActorRef) = {
    Try {
      svc.func(cmd)
    }.map { stateM =>
        persist(EventRecover(stateM)) { event =>
          val state = event.stateM
          val (newModel, events) = state.run(model).value
          log.info(s"Internal state for entity: $persistenceId updated to: $newModel")
          updateStateAndReply((newModel, events), origin)
        }
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
    persist(WaitingComplete(cmd, origin)) { _ =>
      context.setReceiveTimeout(timeout.duration)
      context.become(awaitingCommandComplete(cmd, origin), discardOld = true)
    }
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
          stateAndEvents => {
            val (s, evts) = stateAndEvents
            val eventRecover = EventRecover[T](State[T, Seq[Event]](_ => (s, evts)))
            persist(eventRecover) { _ =>
              updateStateAndReply(stateAndEvents, cc.origin)
              restoreBehaviour()
            }
          }
        )
      } else {
        // This can happen when a previous command timed out
        log.warning("Received CompleteCommand for command {} while awaiting for {}, skipping ...", cc.cmd, cmd)
      }
    case ReceiveTimeout =>
      origin ! Status.Failure(new TimeoutException(s"Command Complete Timeout error: $cmd"))
      restoreBehaviour()
  }

  private def restoreBehaviour() = {
    context.become(receiveCommand)
    context.setReceiveTimeout(Duration.Undefined)
    unstashAll()
  }

  private def findServiceForCommand(cmd: Command) = processor.commandModifiers.map(_.service).find(_.func.isDefinedAt(ctx, cmd))
  private def findServiceForEvent(evt: Event) = processor.eventModifiers.map(_.service).find(_.func.isDefinedAt(ctx, evt))
  private def findServiceForQuery(req: Request) = processor.queries.map(_.service).find(_.func.isDefinedAt(ctx, req))

}

object ProcessorActor {
  def props(ctx: ComponentContext, processor: Processor[_])(implicit timeout: Timeout): Props = Props(new ProcessorActor(ctx, processor, timeout))
  case class CompleteCommand[S](cmd: Command, result: Either[Throwable, (S, Seq[Event])], origin: ActorRef)

  case class EventRecover[S](stateM: State[S, Seq[Event]])
  case class WaitingComplete(cmd: Command, origin: ActorRef)
  case object Processing
}
