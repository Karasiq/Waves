package com.wavesplatform.data

import scala.util.Try

import com.wavesplatform.events.api.grpc.protobuf.{BlockchainUpdatesApiGrpc, SubscribeEvent, SubscribeRequest}
import com.wavesplatform.events.protobuf.BlockchainUpdated
import com.wavesplatform.utils.ScorexLogging
import io.grpc.ManagedChannelBuilder

class PollingAgent(height: Int) extends ScorexLogging {
  def start(f: Iterator[BlockchainUpdated] => Unit): Unit = {
    def createIterator() = {
      val channel = ManagedChannelBuilder.forAddress("blockchain-updates.waves.exchange", 443).usePlaintext().build()
      val grpc    = BlockchainUpdatesApiGrpc.blockingStub(channel)
      grpc.subscribe(SubscribeRequest.of(height max 1, Int.MaxValue))
    }

    while (!Thread.currentThread().isInterrupted) {
      Try {
        val iterator = createIterator().collect {
          case SubscribeEvent(Some(update)) =>
            update
        }
        f(iterator)
      }.failed.foreach(log.error("Poll error", _))
      Thread.sleep(5000)
    }
  }
}
