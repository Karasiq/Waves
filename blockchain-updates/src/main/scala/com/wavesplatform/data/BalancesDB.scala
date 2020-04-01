package com.wavesplatform.data

import com.google.protobuf.ByteString
import com.wavesplatform.account.{Address, AddressScheme}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.database._
import com.wavesplatform.events.protobuf.StateUpdate
import com.wavesplatform.events.protobuf.StateUpdate.BalanceUpdate
import com.wavesplatform.protobuf.transaction.PBRecipients
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.Transaction
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.transfer.TransferTransaction
import com.wavesplatform.utils.ScorexLogging

import scala.util.Try

class BalancesDB(nodeAddress: Address, safeAddress: Address) extends ScorexLogging {
  val db = openDB(sys.env.getOrElse("VOLK_DB", "volk_balances"))

  var lastHeight = Try(db.get(Keys.volkHeight)).getOrElse(0)
  var leases     = db.readOnly(db => Try(db.get(Keys.volkLeases())).getOrElse(Set.empty))

  def totalProfit: Long = Try(db.get(Keys.volkTotal())).getOrElse(0L)

  def saveBalances(height: Int, stateUpdates: Seq[(StateUpdate, Seq[StateUpdate])], transactions: Seq[Transaction]): Long = {
    def updateHistory(rw: RW, history: Seq[Int], key: Key[Seq[Int]], threshold: Int, kf: Int => Key[_]): Seq[Array[Byte]] = {
      val (c1, c2) = history.partition(_ > threshold)
      rw.put(key, (height +: c1) ++ c2.headOption)
      c2.drop(1).map(kf(_).keyBytes)
    }

    def toAddress(address: ByteString): Address =
      PBRecipients.toAddress(address.toByteArray, AddressScheme.current.chainId).right.get

    def isNodeWaves(upd: BalanceUpdate) = toAddress(upd.address) == nodeAddress && upd.getAmount.assetId.isEmpty
    val relevant = stateUpdates.flatMap(ss => ss._1 +: ss._2).flatMap(_.balances).exists(isNodeWaves)

    if (relevant) db.readWrite { rw =>
      val history = rw.get(Keys.volkBalanceHistory(nodeAddress))
      val initialBalance = history.headOption.map(h => rw.get(Keys.volkBalance(nodeAddress, h))).getOrElse(0L)
      val (balance, rewards) = stateUpdates.foldLeft(initialBalance -> 0L) {
        case ((current, reward), (blockUpd, txUpdates)) =>
          val rewardDiff = blockUpd.balances
            .filter(isNodeWaves)
            .foldLeft((current, 0L)) { case ((value, diff), bu) => (bu.getAmount.amount, diff + (bu.getAmount.amount - value)) }._2

          val newBalance = (blockUpd +: txUpdates)
            .flatMap(_.balances)
            .filter(isNodeWaves)
            .lastOption
            .map(_.getAmount.amount)
            .getOrElse(current)
          (newBalance, reward + rewardDiff)
      }
      val fees = transactions.collect {
        case lt: LeaseTransaction if lt.sender.toAddress == safeAddress && lt.recipient == nodeAddress => lt.fee
        case tt: TransferTransaction if tt.sender.toAddress == nodeAddress && tt.recipient == safeAddress && tt.feeAssetId == Waves => tt.fee
        case lc: LeaseCancelTransaction if lc.sender.toAddress == safeAddress => lc.fee
        case _ => 0L
      }.sum

      val finalReward = rewards - fees
      if (balance != initialBalance) {
        log.info(s"New $nodeAddress balance is ${balance.toDouble / 100000000} waves")
        val expired = updateHistory(rw, history, Keys.volkBalanceHistory(nodeAddress), height - 10000, Keys.volkBalance(nodeAddress, _))
        rw.put(Keys.volkBalance(nodeAddress, height), balance)
        expired.foreach(rw.delete(_, "volk-balance"))
      }

      if (finalReward != 0) {
        rw.put(Keys.volkHeight, height)
        lastHeight = height
        val oldAtHeight = db.get(Keys.volkRewards(height)).getOrElse(0L)
        rw.put(Keys.volkRewards(height), Some(finalReward))
        val total = Try(rw.get(Keys.volkTotal())).getOrElse(0L)
        val newTotal = total - oldAtHeight + finalReward
        rw.put(Keys.volkTotal(), newTotal)
        log.info(s"Writing reward change at $height: ${finalReward.toDouble / 100000000} waves, total is ${newTotal.toDouble / 100000000} waves")
      }
      finalReward
    } else 0L
  }

  def rollback(toHeight: Int): Unit = db.readWrite { rw =>
    val lastHeight  = rw.get(Keys.volkHeight)
    val history = rw.get(Keys.volkBalanceHistory(nodeAddress))
    rw.put(Keys.volkBalanceHistory(nodeAddress), history.filterNot(_ > toHeight))
    var lostRewards = 0L
    (lastHeight until toHeight by -1).foreach { h =>
      val reward = rw.get(Keys.volkRewards(h)).getOrElse(0L)
      rw.delete(Keys.volkRewards(h))
      lostRewards += reward
    }

    if (lostRewards != 0) {
      log.info(s"Rolling back to $toHeight, diff is ${-lostRewards.toDouble / 100000000} waves")
      val total = Try(rw.get(Keys.volkTotal())).getOrElse(0L)
      rw.put(Keys.volkTotal(), total - lostRewards)
    }
    this.lastHeight = toHeight
    rw.put(Keys.volkHeight, toHeight)
  }

  def addLeases(leaseIds: Set[ByteStr], removed: Set[ByteStr]): Unit = db.readWrite { rw =>
    val leases    = Try(rw.get(Keys.volkLeases())).getOrElse(Set.empty)
    val newLeases = leases ++ leaseIds -- removed
    rw.put(Keys.volkLeases(), newLeases)
    this.leases = newLeases
  }
}
