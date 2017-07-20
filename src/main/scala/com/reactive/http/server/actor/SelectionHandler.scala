package com.reactive.http.server.actor

import java.nio.channels.SelectableChannel
import java.nio.channels.spi.{AbstractSelector, SelectorProvider}

import akka.actor.{Actor, ActorRef, DeadLetterSuppression, NoSerializationVerificationNeeded, Props}
import com.reactive.http.server.actor.TcpManager.{Bind, RegisterIncomingConnection}

import scala.concurrent.ExecutionContext

/**
  * Interface behind which we hide our selector management logic from the connection actors
  */
trait ChannelRegistry {
  /**
    * Registers the given channel with the selector, creates a ChannelRegistration instance for it
    * and dispatches it back to the channelActor calling this `register`
    */
  def register(channel: SelectableChannel, initialOps: Int)(implicit channelActor: ActorRef)
}


object SelectionHandler {

  case object ChannelConnectable

  case object ChannelAcceptable

  case object ChannelReadable extends DeadLetterSuppression

  case object ChannelWritable extends DeadLetterSuppression

}

class SelectionHandler extends Actor {
  private[this] val registry = {
    val SelectorDispatcher: String = context.system.settings.config.getConfig("com-reactive-tcp").getString("selector-dispatcher")
    val dispatcher = context.system.dispatchers.lookup(SelectorDispatcher)
    new ChannelRegistryImpl(context.dispatcher)
  }

  // It uses SerializedSuspendableExecutionContext with PinnedDispatcher (This dispatcher dedicates a unique thread for each
  // actor using it; i.e. each actor will have its own thread pool with only one thread in the pool)
  class ChannelRegistryImpl(executionContext: ExecutionContext) extends ChannelRegistry {

    private[this] val selector: AbstractSelector = SelectorProvider.provider.openSelector

    private[this] val select = new SelectTask(executionContext, selector)
    executionContext.execute(select) // start selection "loop"


    def register(channel: SelectableChannel, initialOps: Int)(implicit channelActor: ActorRef): Unit = {
      val register = new RegisterTask(executionContext, selector, channel, initialOps, channelActor)
      execute(register)
    }


    private def execute(task: Runnable): Unit = {
      executionContext.execute(task)
    }
  }

  override def receive: Receive = {
    case b:Bind ⇒
      val bindCommander = sender()
      context.actorOf(Props(new TcpListner(self, registry, bindCommander, b)))
    //in case of akka http these are sent as worker commands ⇒
      //create tcplistner
    //      println("in SelectionHandler")
    case RegisterIncomingConnection(channel, props) ⇒ context.actorOf(props(registry)) //creation of incoming connection
  }
}

trait ChannelRegistration extends NoSerializationVerificationNeeded {
  def enableInterest(op: Int): Unit

  def disableInterest(op: Int): Unit

  /**
    * Explicitly cancel the registration
    *
    * This wakes up the selector to make sure the cancellation takes effect immediately.
    */
  def cancel(): Unit
}
