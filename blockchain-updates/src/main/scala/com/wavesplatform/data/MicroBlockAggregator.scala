package com.wavesplatform.data

import com.google.protobuf.ByteString
import com.wavesplatform.events.protobuf.StateUpdate
import com.wavesplatform.protobuf.transaction.PBSignedTransaction

class MicroBlockAggregator {
  var height = 0
  type Transactions = Seq[PBSignedTransaction]
  final case class MicroBlockDiff(id: ByteString, blockUpdate: StateUpdate, txUpdates: Seq[StateUpdate], transactions: Transactions)
  var microBlocks = Seq.empty[MicroBlockDiff]

  def addMicroBlock(height: Int, id: ByteString, stateUpdate: StateUpdate, txUpdates: Seq[StateUpdate], transactions: Transactions): Unit = {
    if (this.height != height) {
      this.height = height
      microBlocks = Nil
    }
    microBlocks = microBlocks.filterNot(_.id == id) :+ MicroBlockDiff(id, stateUpdate, txUpdates, transactions)
  }

  def rollback(height: Int, id: ByteString): Unit = {
    if (this.height == height && microBlocks.exists(_.id == id))
      microBlocks = microBlocks.takeWhile(_.id != id) ++ microBlocks.filter(_.id == id)
    else if (this.height != height) {
      microBlocks = Nil
      this.height = height
    }
  }

  def finishBlock(
      height: Int,
      id: ByteString,
      blockUpdate: StateUpdate,
      txUpdates: Seq[StateUpdate],
      blockTransactions: Transactions
  ): (Seq[(StateUpdate, Seq[StateUpdate])], Transactions) = {
    val updates      = this.microBlocks.map(mb => (mb.blockUpdate, mb.txUpdates))
    val transactions = this.microBlocks.flatMap(_.transactions)
    this.microBlocks = Seq(MicroBlockDiff(id, blockUpdate, txUpdates, blockTransactions))
    this.height = height
    (updates, transactions)
  }
}
