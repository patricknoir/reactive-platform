package org.patricknoir.account

import org.patricknoir.account.domain.AccountSession
import org.patricknoir.platform.{ComponentContext, CtxCmdInfo}
import org.patricknoir.platform.dsl._

package object processor {

  val registerAccountCmd: CtxCmdInfo[AccountSession] = command("registerAccountCmd") {
    (_: ComponentContext, ???) =>

  }

}
