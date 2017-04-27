package org.patricknoir.platform.runtime

import akka.actor.ActorSystem
import akka.pattern.{Backoff, BackoffSupervisor}
import akka.util.Timeout
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import akka.pattern.ask
import org.patricknoir.platform.runtime.actors.ZkActor
import org.patricknoir.platform.runtime.actors.ZkActor._

import scala.concurrent.{Future, Promise}

sealed trait MessageFabric {
  def createMailbox(name: String): Future[String]
  def deleteMailbox(name: String): Future[String]
  def existsMailbox(name: String): Future[Boolean]
}

object MessageFabric {
  def create(zkHosts: String, minBackoff: FiniteDuration, maxBackoff: FiniteDuration)(implicit system: ActorSystem, timeout: Timeout) = new MessageFabricImpl(zkHosts, minBackoff, maxBackoff)
}

class MessageFabricImpl(zkHosts: String, minBackoff: FiniteDuration, maxBackoff: FiniteDuration)(implicit system: ActorSystem, timeout: Timeout) extends MessageFabric with LazyLogging {

  import system.dispatcher
  private var terminationPromise: Promise[Unit] = Promise()

  private val supervisor = BackoffSupervisor.props(
    Backoff.onStop(
      ZkActor.props(zkHosts, terminationPromise),
      childName = "zk",
      minBackoff = minBackoff,
      maxBackoff = maxBackoff,
      randomFactor = 0.2 // adds 20% "noise" to vary the intervals slightly
    ))

  val zk = system.actorOf(supervisor)

  override def createMailbox(name: String): Future[String] = {
    logger.info(s"Sending request createTopic to zkActor")
    (zk ? CreateTopic(name)).map(result => name)
  }

  override def deleteMailbox(name: String): Future[String] =
    (zk ? DeleteTopic(name)).map(_ => name)

  override def existsMailbox(name: String): Future[Boolean] =
    (zk ? ExistsTopic(name)).mapTo[Boolean]

  def close(): Future[Unit] = {
    zk ! Terminate
    terminationPromise.future
  }
}