package org.patricknoir.platform.runtime

import java.util.Optional

import akka.actor.ActorSystem
import com.google.common.base
import com.orbitz.consul.Consul
import io.circe.Printer
import org.patricknoir.platform._

import scala.collection.JavaConverters._
import scala.util.Try


case class BoundedContextInfo(
  id: String,
  version: Version,
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

class DefaultRegistryImpl(context: ComponentContext) extends Registry {

  import io.circe.generic.auto._
  import io.circe.syntax._

  private val consul = Consul.builder().build()
  private val kvStore = consul.keyValueClient()

  private def toBoundedContextInfo(bc: BoundedContext): BoundedContextInfo = {
    val id = bc.id
    val version = bc.version
    val services: Set[(String, Version)] = bc.componentDefs.map(c => (c.id, c.version))
    val (commands, requests) = bc.componentDefs.map(extractPropNames).foldLeft((Set.empty[String], Set.empty[String])) { case ((accCmds, accReqs), (cmds, reqs)) =>
      (accCmds ++ cmds, accReqs ++ reqs)
    }

    //FIXME: add input events
    BoundedContextInfo(id, version, commands, Set.empty, requests, services)
  }

  private def extractPropNames(component: ComponentDef[_]): (Set[String], Set[String]) = component match {
    case p: ProcessorDef[_] =>
      (
        p.propsFactory(context).commandModifiers.map(_.service.id),
        p.propsFactory(context).queries.map(_.service.id)
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
