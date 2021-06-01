package com.wavesplatform.data

import java.time._

import scala.concurrent.duration.{Duration => _, _}
import scala.util.Try
import scala.util.control.NonFatal

import com.wavesplatform.account.{Address, AddressScheme}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.events.protobuf.{BlockchainUpdated, StateUpdate}
import com.wavesplatform.protobuf.block.PBBlocks
import com.wavesplatform.protobuf.transaction.{PBSignedTransaction, PBTransactions, VanillaTransaction}
import com.wavesplatform.transaction.{AuthorizedTransaction, Transaction}
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.transfer.TransferTransaction
import com.wavesplatform.utils.ScorexLogging
import okhttp3.{OkHttpClient, Request}
import play.api.libs.json.Json

object VolkMain extends App with ScorexLogging {
  def parseTransaction(tx: PBSignedTransaction): Option[VanillaTransaction] = Try(PBTransactions.vanillaUnsafe(tx)).toOption

  def sendTxNotification(channel: String, txId: ByteStr, typeStr: String, alarm: Boolean): Unit = {
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
  val initialBalance: Long = (sys.env.getOrElse("VOLK_BALANCE", "0").toDouble * 1e8).toLong

  val aggr              = new MicroBlockAggregator
  var lastBlockTime     = 0L
  var lastMined         = Instant.now()
  var lastMinedNotified = 0
  var rollbacksEnabled  = false
  val db                = new BalancesDB(nodeAddress, safeAddress)

  lazy val httpClient = new OkHttpClient

  class FaucetChecker(network: String, address: String) {
    private[this] var nextNotification = LocalDate
      .now()
      .atTime(12, 0)
      .atZone(ZoneId.of("Europe/Moscow"))

    private[this] var lastFaucetCheck = 0L

    def checkFaucetBalance(): Unit = {
      val timeToNotify = Instant.now().compareTo(nextNotification.toInstant) > 0
      val timeToCheck = {
        val interval = 2 hours
        val elapsed  = (System.nanoTime() - lastFaucetCheck).nanos
        elapsed > interval
      }

      if (timeToNotify || timeToCheck) {
        val request = new Request.Builder()
          .get()
          .url(s"http://nodes-${network.toLowerCase}.wavesnodes.com/addresses/balance/$address")
          .header("Accept", "application/json")
          .build()
        val result  = Json.parse(httpClient.newCall(request).execute().body().bytes())
        val balance = (result \ "balance").as[Long] * 1e-8

        val message = f"Faucet balance on $network: $balance%.2f Waves"
        log.info(message)

        if (timeToNotify || balance < 5000) {
          val faucetChannels = Seq("faucet_mon")
          faucetChannels.foreach(DiscordSender.sendMessage(_, message))
          if (timeToNotify) nextNotification = nextNotification.plusDays(1)
        }

        lastFaucetCheck = System.nanoTime()
      }
    }
  }

  val faucetThread = new Thread({ () =>
    val checkers = Seq(
      new FaucetChecker("Testnet", "3Myqjf1D44wR8Vko4Tr5CwSzRNo2Vg9S7u7"),
      new FaucetChecker("Stagenet", "3MgSuT5FfeMrwwZCbztqLhQpcJNxySaFEiT")
    )

    while (!Thread.interrupted()) {
      checkers.foreach { checker =>
        Try(checker.checkFaucetBalance()).failed.foreach(log.error("Error checking faucet balance", _))
      }
      Thread.sleep(10000)
    }
  })

  faucetThread.setDaemon(true)
  faucetThread.start()

  channels.foreach(DiscordSender.sendMessage(_, s"Node monitor started, last checked height is ${db.lastHeight}"))

  val pa = new PollingAgent(if (db.lastHeight == 0) startHeight else 0)

  pa.start(_.foreach {
    case BlockchainUpdated(
        id,
        height,
        _,
        BlockchainUpdated.Update
          .Append(BlockchainUpdated.Append(_, _, Some(stateUpdate), txUpdates, BlockchainUpdated.Append.Body.MicroBlock(microBlock)))
        ) if height > startHeight =>
      aggr.addMicroBlock(height, id, stateUpdate, txUpdates.toVector, microBlock.getMicroBlock.getMicroBlock.transactions)

    case BlockchainUpdated(
        id,
        nextHeight,
        _,
        BlockchainUpdated.Update.Append(BlockchainUpdated.Append(_, _, stateUpdateOpt, txUpdates, BlockchainUpdated.Append.Body.Block(block)))
        ) if nextHeight > db.lastHeight && nextHeight > startHeight =>
      // if (nextHeight != aggr.height + 1) log.warn(s"Height skip: ${db.lastHeight} -> $nextHeight")
      rollbacksEnabled = true // Arrived to the correct offset
      val height                  = nextHeight - 1
      val stateUpdate             = stateUpdateOpt.getOrElse(StateUpdate.defaultInstance)
      val (updates, transactions) = aggr.finishBlock(nextHeight, id, stateUpdate, txUpdates.toVector, block.getBlock.transactions)

      def simpleNotify(tx: Transaction, typeStr: String): Unit = {
        log.info(s"Simple alert ($typeStr): $tx")
        channels.foreach(ch => sendTxNotification(ch, tx.id(), typeStr, alarm = false))
      }

      def alert(tx: Transaction, typeStr: String): Unit = {
        log.info(s"Alarm alert ($typeStr): $tx")
        channels.foreach(ch => sendTxNotification(ch, tx.id(), typeStr, alarm = true))
      }

      val isToday = {
        val today = LocalDate.now(ZoneId.of("UTC"))
        val ts    = LocalDateTime.ofInstant(Instant.ofEpochMilli(block.getBlock.getHeader.timestamp), ZoneId.of("UTC")).toLocalDate
        today.compareTo(ts) == 0
      }

      val watchedAddrs = Set(nodeAddress, safeAddress)
      if (isToday) transactions.flatMap(parseTransaction) foreach {
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

      val isNodeGenerated = PBBlocks.vanilla(block.getBlock.getHeader).generator.toAddress == nodeAddress
      if (height % 10000 == 0) log.info(s"New block: $height")
      val rewards = db.saveBalances(height, updates, transactions.flatMap(parseTransaction))

      val currentBlockTime = block.getBlock.getHeader.timestamp
      if (isToday && currentBlockTime > lastBlockTime) {
        lastBlockTime = currentBlockTime

        if (isNodeGenerated) {
          if (lastMinedNotified > 0)
            channels.foreach(
              DiscordSender
                .sendMessage(
                  _,
                  s"New block generated at ${Instant.ofEpochMilli(block.getBlock.getHeader.timestamp).atZone(ZoneId.of("Europe/Moscow"))}"
                )
            )

          lastMined = Instant.now()
          lastMinedNotified = 0
        } else if (lastMined.plus(Duration.ofHours(7)).compareTo(Instant.now()) < 0 && lastMinedNotified < 2) {
          channels.foreach(DiscordSender.sendMessage(_, "@everyone **Warning**: Last block generated more than 7 hours ago"))
          lastMinedNotified = 2
        } else if (lastMined.plus(Duration.ofHours(6)).compareTo(Instant.now()) < 0 && lastMinedNotified < 1) {
          channels.foreach(DiscordSender.sendMessage(_, "**Warning**: Last block generated more than 6 hours ago"))
          lastMinedNotified = 1
        }
      }

      if (rewards != 0 && isToday) Stats.update(rewards)
      Stats.publish(channels)

    case e @ BlockchainUpdated(_, height, _, BlockchainUpdated.Update.Rollback(rollback))
        if rollback.`type`.isBlock && db.lastHeight >= height && rollbacksEnabled =>
      if (height < db.lastHeight - 100) log.warn(s"Rollback to $height: $e")
      if (height < db.lastHeight - 10000) {
        channels.foreach(DiscordSender.sendMessage(_, s"Unable to rollback to $height, stopping"))
        sys.error(s"Unable to rollback to $height")
      }
      db.rollback(height)
      aggr.rollback(height, aggr.keyBlockId)

    case BlockchainUpdated(id, height, _, BlockchainUpdated.Update.Rollback(rollback)) if rollback.`type`.isMicroblock && height > startHeight =>
      // log.info(s"Rollback to ${ByteStr(id.toByteArray)}")
      aggr.rollback(height, id)

    case e => log.warn(s"Event ignored at ${e.height}: ${e.id} ${e.update.getClass.toString}")
  })

  object Stats {
    val zoneId: ZoneId = ZoneId.of("Europe/Moscow")
    var sendTime       = LocalDate.now().atTime(18, 0).atZone(zoneId)
    var balance        = 0L

    def update(rewards: Long): Unit = {
      this.balance += rewards
      log.info(s"Today rewards is ${balance / 1e8} waves")
    }

    def publish(channels: Seq[String]): Unit = {
      val now = ZonedDateTime.now(zoneId)
      if (now.compareTo(this.sendTime) >= 0) {
        try {
          channels.foreach(sendStats)
          this.balance = 0
          this.sendTime = LocalDate.now().atTime(18, 0).atZone(zoneId).plusDays(1)
          log.info("Daily stats sent")
        } catch { case NonFatal(e) => log.error("Error sending stats", e) }
      }
    }

    def sendStats(channel: String): Unit = {
      val msg =
        s"""**Daily stats**
           |Daily profit: ${balance / 1e8} waves
           |Total profit: ${(db.totalProfit + initialBalance) / 1e8} waves""".stripMargin

      DiscordSender.sendMessage(channel, msg)
    }
  }
}