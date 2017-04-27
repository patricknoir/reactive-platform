package org.patricknoir.platform.runtime.actors

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status}
import kafka.admin.AdminUtils
import kafka.utils.ZkUtils
import org.patricknoir.platform.runtime.actors.ZkActor._

import scala.concurrent.Promise
import scala.util.{Failure, Success, Try}

/**
  * Created by patrick on 08/04/2017.
  */
class ZkActor(zkHosts: String, terminationPromise: Promise[Unit]) extends Actor with ActorLogging {

  log.info("creating ZkActor")
  val zkUtils = ZkUtils(zkHosts, 60000, 3000, false)
  log.info("ZkUtils created")

  private var terminating = false

  def receive = {
    case GetAllTopics =>
      sender ! zkUtils.getAllTopics()
    case ExistsTopic(topic) =>
      respondAsk(sender, topicExists(topic))
    case msg @ CreateTopic(topic, partitions, replications) =>
      respondAsk(sender, createTopic(topic, partitions, replications))
    case DeleteTopic(topic) =>
      respondAsk(sender, deleteTopic(topic))
    case Terminate =>
      terminationPromise.tryComplete(Try(zkUtils.close()))
      terminating = true
      context stop self
  }

  def respondAsk[A](origin: ActorRef, resp: Try[A]) = resp match {
    case Success(a)   => origin ! a
    case Failure(thr) => origin ! Status.Failure(thr)
  }

  def topicExists(topic: String): Try[Boolean] = Try(AdminUtils.topicExists(zkUtils, topic))

  def createTopic(topic: String, partitions: Int, replications: Int): Try[Unit] = Try {
    log.info("creating topic: " + topic)
    AdminUtils.createTopic(zkUtils, topic, partitions, replications)
    log.info(s"topic $topic created")
  }
  def deleteTopic(topic: String): Try[Unit] = Try(AdminUtils.deleteTopic(zkUtils, topic))

  override def postStop() = {
    log.info("Terminating ZkActor")
    if(!terminating) zkUtils.close() //close connection here if not properly terminated
  }

}

object ZkActor {
  def props(zkHosts: String, terminatePromise: Promise[Unit] = Promise()): Props = Props(new ZkActor(zkHosts, terminatePromise))

  case object GetAllTopics
  case class ExistsTopic(name: String)
  case class CreateTopic(name: String, partitions: Int = 1, replications: Int = 1)
  case class DeleteTopic(name: String)

  case object Terminate

}

