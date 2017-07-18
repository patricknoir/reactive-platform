package org.patricknoir.platform.runtime

import java.net.URI
import java.util.Optional

import akka.actor.ActorSystem
import com.google.common.base
import com.google.common.net.HostAndPort
import com.orbitz.consul.Consul
import com.typesafe.config.Config
import io.circe.{Decoder, Printer}
import mousio.etcd4j.EtcdClient
import mousio.etcd4j.responses.EtcdKeysResponse
import org.patricknoir.platform._
import org.patricknoir.platform.dsl.PlatformConfig

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try


case class BoundedContextInfo(
  id: String,
  version: Version,
  commandMailbox: String,
  inputEventMailbox: String,
  outputEventMailbox: String,
  requestMailbox: String,
  failureMailbox: String,
  loggingMailbox: String,
  inputCommands: Set[String],
  inputEvents: Set[String],
  requests: Set[String],
  services: Set[(String, Version)]
)

/**
  * Created by patrick on 18/04/2017.
  */
trait Registry {

  def get(bcId: String, version: Version): Option[BoundedContextInfo]
  def getAll(): Set[BoundedContextInfo]

  def register(bc: BoundedContext): Try[BoundedContextInfo]
  def unregister(bcId: String, version: Version): Unit

}

class EtcdRegistryImpl(config: PlatformConfig) extends Registry {

  import io.circe.generic.auto._
  import io.circe.syntax._
  import scala.collection.JavaConverters._

  val host = config.registryHost
  val port = config.registryPort

  val client = new EtcdClient(URI.create(s"http://$host:$port"))

  override def get(bcId: String, version: Version): Option[BoundedContextInfo] = parse[BoundedContextInfo](s"$bcId/$version")

  override def getAll(): Set[BoundedContextInfo] = {
    val response = client.getAll().send().get()
    response.node.getNodes.asScala
      .map(n => io.circe.parser.decode[BoundedContextInfo](n.value).toOption.get)
      .toSet
  }

  override def register(bc: BoundedContext): Try[BoundedContextInfo] = Try {
    val key = s"${bc.id}/${bc.version}"
    val bcInfo = toBoundedContextInfo(bc)
    client.put(key, Printer.noSpaces.pretty(bcInfo.asJson)).send().get()
    bcInfo
  }

  override def unregister(bcId: String, version: Version): Unit = client.delete(s"$bcId/$version").send().get()

  private def extractPropNames(component: Component[_]): (Set[String], Set[String]) = component match {
    case p: Processor[_] =>
      (
        p.commandModifiers.map(_.service.id),
        p.queries.map(_.service.id)
      )
  }

  private def toBoundedContextInfo(bc: BoundedContext): BoundedContextInfo = {
    val id = bc.id
    val version = bc.version
    val services: Set[(String, Version)] = bc.components.map(c => (c.id, c.version))
    val (commands, requests) = bc.components.map(extractPropNames).foldLeft((Set.empty[String], Set.empty[String])) { case ((accCmds, accReqs), (cmds, reqs)) =>
      (accCmds ++ cmds, accReqs ++ reqs)
    }

    //FIXME: add input events
    BoundedContextInfo(
      id,
      version,
      bc.fullCommandMailboxName,
      "TBD",
      bc.fullEventMailboxName,
      bc.fullRequestMailboxName,
      bc.fullFailureMailboxName,
      bc.fullLogMailboxName,
      commands,
      Set.empty,
      requests,
      services
    )
  }

  private def parse[T: Decoder](key: String) : Option[T] = for {
    content <- Try(client.get(key).send.get().node.value).toOption
    result <- io.circe.parser.decode[T](content).toOption
  } yield result

}

class DefaultRegistryImpl(config: PlatformConfig) extends Registry {

  import io.circe.generic.auto._
  import io.circe.syntax._

  private val consul = Consul.builder().build()
  private val kvStore = consul.keyValueClient()

  private def toBoundedContextInfo(bc: BoundedContext): BoundedContextInfo = {
    val id = bc.id
    val version = bc.version
    val services: Set[(String, Version)] = bc.components.map(c => (c.id, c.version))
    val (commands, requests) = bc.components.map(extractPropNames).foldLeft((Set.empty[String], Set.empty[String])) { case ((accCmds, accReqs), (cmds, reqs)) =>
      (accCmds ++ cmds, accReqs ++ reqs)
    }

    //FIXME: add input events
    BoundedContextInfo(
      id,
      version,
      bc.fullCommandMailboxName,
      "TBD",
      bc.fullEventMailboxName,
      bc.fullRequestMailboxName,
      bc.fullFailureMailboxName,
      bc.fullLogMailboxName,
      commands,
      Set.empty,
      requests,
      services
    )
  }

  private def extractPropNames(component: Component[_]): (Set[String], Set[String]) = component match {
    case p: Processor[_] =>
      (
        p.commandModifiers.map(_.service.id),
        p.queries.map(_.service.id)
      )
  }

  override def get(bcId: String, version: Version): Option[BoundedContextInfo] = {
    val optional: base.Optional[String] = kvStore.getValueAsString(s"$bcId/$version")
    optionalToOption(optional)
  }


  override def getAll(): Set[BoundedContextInfo] = {
    kvStore.getKeys("/").asScala.map { key =>
      val parts = key.split("/")
      val bcId = parts(0)
      val tryVersion = Version.fromString(parts(1))

      //FIXME
      get(bcId, tryVersion.get).get
    }.toSet
  }

  override def register(bc: BoundedContext): Try[BoundedContextInfo] = Try {
    val key = s"${bc.id}/${bc.version}"
    if(kvStore.getValue(key).isPresent) {
      throw new RuntimeException(s"Bounded Context already exists: $key")
    } else {
      val bcInfo = toBoundedContextInfo(bc)
      if(kvStore.putValue(key, Printer.noSpaces.pretty(bcInfo.asJson)))
        bcInfo
      else
        throw new RuntimeException(s"Failed to store in Consul: $key")
    }
  }

  override def unregister(bcId: String, version: Version): Unit = kvStore.deleteKey(s"$bcId/$version")

  private def optionalToOption(optional: base.Optional[String]): Option[BoundedContextInfo] = if(optional.isPresent) {
    io.circe.parser.decode[BoundedContextInfo](optional.get()).toOption
  } else None
}
