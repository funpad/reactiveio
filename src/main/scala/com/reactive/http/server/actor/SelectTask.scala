package com.reactive.http.server.actor

import java.nio.channels.SelectionKey._
import java.nio.channels.{CancelledKeyException, Selector}

import akka.actor.ActorRef
import com.reactive.http.server.actor.SelectionHandler.{ChannelAcceptable, ChannelConnectable, ChannelReadable, ChannelWritable}

import scala.concurrent.ExecutionContext


class SelectTask(val executionContext: ExecutionContext, selector: Selector) extends Runnable {
  final val OP_READ_AND_WRITE = OP_READ | OP_WRITE // compile-time constant

  def tryRun(): Unit = {
    println(s"running selector loop ${selector.keys()}")
    if (selector.select() > 0) { // This assumes select return value == selectedKeys.size
      val keys = selector.selectedKeys
      val iterator = keys.iterator()
      while (iterator.hasNext) {
        val key = iterator.next()
        if (key.isValid) {
          try {
            // Cache because the performance implications of calling this on different platforms are not clear
            val readyOps = key.readyOps()
            key.interestOps(key.interestOps & ~readyOps) // prevent immediate reselection by always clearing
            val connection = key.attachment.asInstanceOf[ActorRef]
            readyOps match {
              case OP_READ ⇒ connection ! ChannelReadable
              case OP_WRITE ⇒ connection ! ChannelWritable
              case OP_READ_AND_WRITE ⇒ {
                connection ! ChannelWritable;
                connection ! ChannelReadable
              }
              case x if (x & OP_ACCEPT) > 0 ⇒ connection ! ChannelAcceptable
              case x if (x & OP_CONNECT) > 0 ⇒ connection ! ChannelConnectable
              case x ⇒ println(s"Invalid readyOps: ${x}")
            }
          } catch {
            case _: CancelledKeyException ⇒
            // can be ignored because this exception is triggered when the key becomes invalid
            // because `channel.close()` in `TcpConnection.postStop` is called from another thread
          }
        }
      }
      keys.clear() // we need to remove the selected keys from the set, otherwise they remain selected
    }
  }

  override def run(): Unit = {
    println("Running Selector Event Loop")

    if (selector.isOpen) {
      try {
        tryRun()
        println("scheduling task again")
      } finally {
        executionContext.execute(this)
      } // re-schedule select behind all currently queued tasks
    }
  }
}
