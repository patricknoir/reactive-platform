package org.patricknoir.platform

import org.patricknoir.platform.dsl.{command, request}
import org.patricknoir.platform.protocol.{Command, Event, Request, Response}

object Util {

  case class IncrementCounterCmd(id: String, step: Int) extends Command
  case class IncrementCounterIfCmd(id: String, step: Int, ifValue: Int) extends Command
  case class CounterIncrementedEvt(id: String, step: Int) extends Event
  case class DecrementCounterCmd(id: String, step: Int) extends Command
  case class CounterDecrementedEvt(id: String, step: Int) extends Event

  case class CounterValueReq(id: String) extends Request
  case class CounterValueResp(id: String, value: Int) extends Response

}
