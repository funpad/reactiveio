package com.reactive.http.server.stream

import java.net.InetSocketAddress

import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, Props}
import akka.dispatch.ExecutionContexts
import akka.stream._
import akka.stream.scaladsl.{BidiFlow, Flow, Keep, RunnableGraph, Sink, Source}
import akka.util.ByteString
import com.reactive.http.model.{HttpRequest, HttpResponse}
import com.reactive.http.server.actor.TcpManager

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal


object Server {

  def parsing(): Flow[ByteString, HttpRequest, NotUsed] = {
    val rootParser = new HttpRequestParser()
    val requestParsingFlow: Flow[ByteString, HttpRequest, NotUsed] = Flow[ByteString].via(rootParser)
    requestParsingFlow
  }

  def rendering(): Flow[ByteString, ByteString, NotUsed] = {
    val renderer = HttpResponseRenderer
    val renderingFlow = Flow[ByteString].via(renderer)
    renderingFlow
  }

  def bindAndHandle(handler:   HttpRequest ⇒ ByteString, endpoint:InetSocketAddress)(implicit materializer:Materializer): Unit = {
    val handlerFlow: Flow[HttpRequest, ByteString, NotUsed] = Flow[HttpRequest].map(handler)
    val parsingRendering: BidiFlow[ByteString, ByteString, ByteString, HttpRequest, NotUsed] = BidiFlow.fromFlows(rendering(), parsing())

    val resultFlow: Flow[ByteString, ByteString, Future[Done]] = fuzeServerFlow(handlerFlow, parsingRendering)

    val system = ActorSystem("Server")
    val tcpManager = system.actorOf(Props(new TcpManager), "tcpManager") //TODO: this should be moved to actorsystem extension
    val source: Source[TcpStream.IncomingConnection, Future[TcpStream.ServerBinding]] = Source.fromGraph(new TcpHandlingGraphStage(tcpManager, new InetSocketAddress("localhost", 5555)))
    source.mapAsyncUnordered(10) { incoming ⇒

      println(s"Running ${incoming}")
      try {
        resultFlow.joinMat(incoming.flow)(Keep.left)
          .run().recover {
          // Ignore incoming errors from the connection as they will cancel the binding.
          // As far as it is known currently, these errors can only happen if a TCP error bubbles up
          // from the TCP layer through the HTTP layer to the Http.IncomingConnection.flow.
          // See https://github.com/akka/akka/issues/17992
          case NonFatal(ex) ⇒ Done
        }(ExecutionContext.global)
      } catch {
          case e⇒ {
            println(s"${e}")
            throw e
          }
      }
    }.to(Sink.ignore).run()
  }

  def fuzeServerFlow(handlerFlow: Flow[HttpRequest, ByteString, NotUsed], parsingRendering: BidiFlow[ByteString, ByteString, ByteString, HttpRequest, NotUsed]) = {
    val resultFlow: Flow[ByteString, ByteString, Future[Done]] = Flow[HttpRequest]
      .watchTermination()(Keep.right)
      .viaMat(handlerFlow)(Keep.left)
//      .watchTermination() { (termWatchBefore, termWatchAfter) ⇒
//        // flag termination when the user handler has gotten (or has emitted) termination
//        // signals in both directions
//        termWatchBefore.flatMap(_ ⇒ termWatchAfter)
//      }
      .joinMat(parsingRendering)(Keep.left)

    resultFlow
  }
}
