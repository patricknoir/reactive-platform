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

  log.debug("ProcessorActor: {} created", self.path.name)

  override val persistenceId = self.path.name

  var model: T = processor.model

  override def receiveCommand: Receive = {
    case cmd: Command =>
      handleCommand(cmd, sender)
    case evt: Event =>
      log.warning(s"Handling input event not implemented yet, received: $evt")
    case req: Request =>
      handleRequest(req, sender)
    case other => log.warning(s"Unhandled message: $other")
  }

  var isWaitingComplete: Option[WaitingComplete] = None

  override def receiveRecover: Receive = {
    case event: Event =>
      findServiceForRecovery(event).map { service =>
        log.debug("Recovering applying event: {} on state: {}", event, model)
        val res: State[T, Unit] = service.func(event)
        this.model = res.run(this.model).value._1
      }
    case eventRecover: EventRecover[T] =>
      model = eventRecover.model
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
    log.debug(s"Received request: $req")
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
      case ctxService: ContextStatefulService[T, Command, Event] => handleCtxCommand(ctxService, cmd, origin)
    }.orElse {
      log.warning(s"Service not found for command: $cmd")
      None // FIXME: Need to reply in order to get the future completed
    }
  }

  private def handleCtxCommand(css: ContextStatefulService[T, Command, Event], cmd: Command, origin: ActorRef): Try[Unit] = {
    Try {
      css.func(ctx, cmd)
    }.map { stateM =>
      val (newModel, event) = stateM.run(this.model).value
      log.info(s"about to persist: $event")
      persist(event) { e =>
        log.info("Event: {} persisted in the entity event-journal", event)
        updateStateAndReply((newModel, e), origin)
      }
    }.recover { case err: Throwable =>
      reportErrorAndReply(err, cmd, origin)
    }
  }

  private def handleSyncCommand(svc: SyncStatefulService[T, Command, Event], cmd: Command, origin: ActorRef) = {
    Try {
      svc.func(cmd)
    }.map { stateM =>
        val (newModel, event) = stateM.run(model).value
        persist(EventRecover(stateM)) { er =>
          log.info(s"Internal state for entity: $persistenceId updated to: $newModel")
          updateStateAndReply((newModel, event), origin)
        }
    }.recover { case err: Throwable =>
      reportErrorAndReply(err, cmd, origin)
    }

  }

  private def handleAsyncCommand(svc: AsyncStatefulService[T, Command, Event], cmd: Command, origin: ActorRef) = {
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

  private def updateStateAndReply(valueAndEvent: (T, Event), origin: ActorRef) = {
    log.info("Updating current state with: {}", valueAndEvent._1)
    model = valueAndEvent._1
    origin ! valueAndEvent._2
  }

  private def awaitingCommandComplete(cmd: Command, origin: ActorRef): Receive = {
    case newCmd: Command =>
      stash()
    case evt: Event =>
      log.warning(s"Handling input event not implemented yet, received: $evt")
    case req: Request =>
      handleRequest(req, sender)
    case cc: CompleteCommand[T] =>
      if (cc.cmd == cmd) { // FIXME: Safer comparison ???
        cc.result.fold(
          err => reportErrorAndReply(err, cc.cmd, origin),
          stateAndEvent => {
            val (s, evts) = stateAndEvent
            val eventRecover = EventRecover[T](s)
            persist(eventRecover) { _ =>
              updateStateAndReply(stateAndEvent, cc.origin)
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

  private def findServiceForCommand(cmd: Command) = processor.commandModifiers.map(_.service).find(_.func.isDefinedAt((ctx, cmd)))
  private def findServiceForEvent(evt: Event) = processor.eventModifiers.map(_.service).find(_.func.isDefinedAt((ctx, evt)))
  private def findServiceForQuery(req: Request) = processor.queries.map(_.service).find(_.func.isDefinedAt((ctx, req)))
  private def findServiceForRecovery(evt: Event) = processor.recover.find(_.func.isDefinedAt(evt))

}

object ProcessorActor {
  def props(ctx: ComponentContext, processor: Processor[_])(implicit timeout: Timeout): Props = Props(new ProcessorActor(ctx, processor, timeout))
  case class CompleteCommand[S](cmd: Command, result: Either[Throwable, (S, Event)], origin: ActorRef)

  case class EventRecover[S](model: S)
  case class WaitingComplete(cmd: Command, origin: ActorRef)
  case object Processing
}
