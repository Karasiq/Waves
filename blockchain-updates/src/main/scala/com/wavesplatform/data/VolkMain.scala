package com.wavesplatform.data

import java.time._

import com.google.protobuf.ByteString
import com.wavesplatform.account.{Address, AddressScheme}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.events.protobuf.{BlockchainUpdated, StateUpdate}
import com.wavesplatform.protobuf.block.PBBlocks
import com.wavesplatform.protobuf.transaction.{PBSignedTransaction, PBTransactions, VanillaTransaction}
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.transfer.TransferTransaction
import com.wavesplatform.transaction.{AuthorizedTransaction, Transaction}
import com.wavesplatform.utils.ScorexLogging

import scala.util.Try
import scala.util.control.NonFatal

object VolkMain extends App with ScorexLogging {
  def parseTransaction(tx: PBSignedTransaction): Option[VanillaTransaction] = Try(PBTransactions.vanillaUnsafe(tx)).toOption

  def sendNotification(channel: String, height: Int, txId: ByteStr, typeStr: String, alarm: Boolean): Unit = {
    val msg =
      s"""${if (alarm) "@everyone " else ""}**${typeStr.capitalize}**: https://wavesexplorer.com/tx/$txId""".stripMargin
    DiscordSender.sendMessage(channel, msg)
  }

  val startHeight = sys.env.get("VOLK_HEIGHT").map(_.toInt).getOrElse(0)
  val channels    = sys.env.get("VOLK_CHANNELS").toSeq.flatMap(_.split(',')).filter(_.nonEmpty)
  AddressScheme.current = new AddressScheme {
    override val chainId: Byte = 'W'
  }
  val nodeAddress: Address = Address.fromString(sys.env("VOLK_NODE")).right.get
  val safeAddress: Address = Address.fromString(sys.env("VOLK_SAFE")).right.get

  val aggr      = new MicroBlockAggregator
  var lastMined = Instant.now()
  val db        = new BalancesDB(nodeAddress, safeAddress)

  channels.foreach(DiscordSender.sendMessage(_, s"Node monitor started, last checked height is ${db.lastHeight}"))
  val pa = new PollingAgent(startHeight)
  pa.start(_.foreach {
    case BlockchainUpdated(
        id,
        height,
        BlockchainUpdated.Update
          .Append(BlockchainUpdated.Append(_, Some(stateUpdate), txUpdates, BlockchainUpdated.Append.Body.MicroBlock(microBlock)))
        ) if height > startHeight =>
      aggr.addMicroBlock(height, id, stateUpdate, txUpdates, microBlock.getMicroBlock.transactions)

    case BlockchainUpdated(
        id,
        nextHeight,
        BlockchainUpdated.Update.Append(BlockchainUpdated.Append(_, stateUpdateOpt, txUpdates, BlockchainUpdated.Append.Body.Block(block)))
        ) if nextHeight > db.lastHeight && nextHeight > startHeight =>
      // if (nextHeight != aggr.height + 1) log.warn(s"Height skip: ${db.lastHeight} -> $nextHeight")
      val height                  = nextHeight - 1
      val stateUpdate             = stateUpdateOpt.getOrElse(StateUpdate.defaultInstance)
      val (updates, transactions) = aggr.finishBlock(nextHeight, id, stateUpdate, txUpdates, block.transactions)
      val (newLeases, cancelled) = transactions.flatMap(parseTransaction).foldLeft(Set.empty[ByteStr] -> Set.empty[ByteStr]) {
        case ((leases, cancels), tx) =>
          tx match {
            case lt: LeaseTransaction if lt.recipient == nodeAddress          => (leases + lt.id(), cancels)
            case lc: LeaseCancelTransaction if cancels.contains(lc.id())      => (leases - lc.leaseId, cancels)
            case lc: LeaseCancelTransaction if db.leases.contains(lc.leaseId) => (leases, cancels + lc.leaseId)
            case _                                                            => (leases, cancels)
          }
      }
      if (newLeases.nonEmpty || cancelled.nonEmpty) db.addLeases(newLeases, cancelled)

      def simpleNotify(tx: Transaction, typeStr: String): Unit = {
        log.info(s"Simple alert ($typeStr): $tx")
        channels.foreach(ch => sendNotification(ch, height, tx.id(), typeStr, alarm = false))
      }

      def alert(tx: Transaction, typeStr: String): Unit = {
        log.info(s"Alarm alert ($typeStr): $tx")
        channels.foreach(ch => sendNotification(ch, height, tx.id(), typeStr, alarm = true))
      }

      val watchedAddrs = Set(nodeAddress, safeAddress)
      if (height > startHeight) transactions.flatMap(parseTransaction) foreach {
        case transfer: TransferTransaction
            if transfer.sender.toAddress == nodeAddress && transfer.recipient == safeAddress && transfer.assetId == Waves =>
          simpleNotify(transfer, s"transfer ${transfer.amount / 100000000}W to safe")

        case lease: LeaseTransaction if lease.recipient == nodeAddress =>
          simpleNotify(lease, s"lease ${lease.amount / 100000000}W to node")

        case lc: LeaseCancelTransaction if db.leases.contains(lc.leaseId) =>
          alert(lc, "lease cancel")

        case tx: AuthorizedTransaction if watchedAddrs.contains(tx.sender.toAddress) =>
          alert(tx, "unknown transaction")

        case _ => // Ignore
      }

      val isNodeGenerated = PBBlocks.vanilla(block.getHeader).generator.toAddress == nodeAddress
      // log.info(s"New block: $height, generated $isNodeGenerated")
      val rewards = db.saveBalances(height, updates, transactions.flatMap(parseTransaction))
      val isToday = {
        val today = LocalDate.now(ZoneId.of("UTC"))
        val ts    = LocalDateTime.ofInstant(Instant.ofEpochMilli(block.getHeader.timestamp), ZoneId.of("UTC")).toLocalDate
        today.compareTo(ts) == 0
      }

      if (isToday) {
        if (isNodeGenerated) lastMined = Instant.now()
        else if (lastMined.plus(Duration.ofHours(3)).compareTo(Instant.now()) < 0) {
          channels.foreach(DiscordSender.sendMessage(_, "**Warning**: Last block generated more than 3 hours ago"))
          lastMined = Instant.now()
        }
      }

      if (rewards != 0 && isToday) Stats.update(rewards)
      Stats.publish(channels)

    case BlockchainUpdated(_, height, BlockchainUpdated.Update.Rollback(rollback)) if rollback.isBlock && height + 2000 > startHeight =>
      // log.info(s"Rollback to $height")
      db.rollback(height)
      aggr.rollback(height, ByteString.EMPTY)

    case BlockchainUpdated(id, height, BlockchainUpdated.Update.Rollback(rollback)) if rollback.isMicroblock && height > startHeight =>
      // log.info(s"Rollback to ${ByteStr(id.toByteArray)}")
      aggr.rollback(height, id)

    case e =>
    // log.warn(s"Event ignored at ${e.height}: ${e.id} ${e.update.getClass.toString}")
  })

  object Stats {
    val zoneId: ZoneId = ZoneId.of("Europe/Moscow")
    var lastTime       = LocalDate.now().atTime(18, 0).atZone(zoneId)
    var balance        = 0L

    def update(rewards: Long): Unit = {
      this.balance += rewards
      log.info(s"Today rewards is ${balance.toDouble / 100000000} waves")
    }

    def publish(channels: Seq[String]): Unit = {
      val now = ZonedDateTime.now(zoneId)
      if (now.compareTo(this.lastTime) >= 0) {
        try {
          channels.foreach(sendStats)
          this.balance = 0
          this.lastTime = now.plusDays(1)
          log.info("Daily stats sent")
        } catch { case NonFatal(e) => log.error("Error sending stats", e) }
      }
    }

    def sendStats(channel: String): Unit = {
      val msg =
        s"""**Daily stats**
           |Daily profit: ${balance.toDouble / 100000000} waves
           |Total profit: ${db.totalProfit.toDouble / 100000000} waves""".stripMargin

      DiscordSender.sendMessage(channel, msg)
    }
  }
}
