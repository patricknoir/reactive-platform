package org.patricknoir.platform

/**
  * Created by patrick on 14/03/2017.
  */
package object protocol {

  trait Message
  trait Command extends Message
  trait Query extends Message
  trait Request extends Query
  trait Response extends Query
  trait Notification extends Message
  trait Event extends Notification
  trait Failure extends Notification
  trait Log extends Notification
  trait Audit extends Notification

  trait Idempotence

}

