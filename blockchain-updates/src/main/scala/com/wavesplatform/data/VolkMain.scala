package com.wavesplatform.data

import java.time._

import com.wavesplatform.account.{Address, AddressScheme, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils._
import com.wavesplatform.consensus.FairPoSCalculator
import com.wavesplatform.crypto
import com.wavesplatform.events.protobuf.{BlockchainUpdated, StateUpdate}
import com.wavesplatform.protobuf.block.PBBlocks
import com.wavesplatform.protobuf.transaction.{PBSignedTransaction, PBTransactions, VanillaTransaction}
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.transfer.TransferTransaction
import com.wavesplatform.transaction.{AuthorizedTransaction, Transaction}
import com.wavesplatform.utils.{ScorexLogging, byteStrFormat}
import okhttp3.{OkHttpClient, Request}
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.duration.{Duration => _, _}
import scala.util.Try
import scala.util.control.NonFatal

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

  val aggr              = new MicroBlockAggregator
  var lastMined         = Instant.now()
  var lastMinedNotified = 0
  var rollbacksEnabled  = false
  val db                = new BalancesDB(nodeAddress, safeAddress)

  lazy val httpClient = new OkHttpClient

  def getNextDelay(): Int = {
    val publicKey = PublicKey.fromBase58String(sys.env("VOLK_NODE_PK")).explicitGet()

    def request(url: String): JsValue = {
      val request = new Request.Builder().get().url(s"http://nodes.wavesnodes.com/$url").build()
      val call    = httpClient.newCall(request)
      val bytes   = call.execute().body().bytes()
      Json.parse(bytes)
    }

    val balance    = (request(s"addresses/balance/details/${nodeAddress.stringRepr}") \ "generating").as[Long]
    val lastBlock  = request("blocks/headers/last").as[JsObject]
    val lastBT     = (lastBlock \ "nxt-consensus" \ "base-target").as[Long]
    val lastGenSig = (lastBlock \ "nxt-consensus" \ "generation-signature").as[ByteStr]

    def generationSignature(signature: Array[Byte], publicKey: PublicKey): Array[Byte] = {
      val s = new Array[Byte](crypto.DigestLength * 2)
      System.arraycopy(signature, 0, s, 0, crypto.DigestLength)
      System.arraycopy(publicKey.arr, 0, s, crypto.DigestLength, crypto.DigestLength)
      crypto.fastHash(s)
    }

    val genSig = generationSignature(lastGenSig.arr, publicKey)
    val hit    = BigInt(1, genSig.take(8).reverse)
    val delay  = FairPoSCalculator.calculateDelay(hit, lastBT, balance)
    require(delay > 0)
    delay.millis.toSeconds.toInt
  }

  def sendNextDelay(): Unit = {
    val triedDelay = Try(getNextDelay())
    triedDelay.failed.foreach(log.error("Error calculating delay", _))
    triedDelay.foreach(seconds => channels.foreach(DiscordSender.sendMessage(_, s"Next allowed mining attempt in $seconds sec")))
  }

  channels.foreach(DiscordSender.sendMessage(_, s"Node monitor started, last checked height is ${db.lastHeight}"))
  sendNextDelay()

  val pa = new PollingAgent(if (db.lastHeight == 0) startHeight else 0)

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
      rollbacksEnabled = true // Arrived to the correct offset
      val height                  = nextHeight - 1
      val stateUpdate             = stateUpdateOpt.getOrElse(StateUpdate.defaultInstance)
      val (updates, transactions) = aggr.finishBlock(nextHeight, id, stateUpdate, txUpdates, block.transactions)

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
        val ts    = LocalDateTime.ofInstant(Instant.ofEpochMilli(block.getHeader.timestamp), ZoneId.of("UTC")).toLocalDate
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

      val isNodeGenerated = PBBlocks.vanilla(block.getHeader).generator.toAddress == nodeAddress
      // log.info(s"New block: $height, generated $isNodeGenerated")
      val rewards = db.saveBalances(height, updates, transactions.flatMap(parseTransaction))

      if (isToday) {
        if (isNodeGenerated) {
          if (lastMinedNotified > 0)
            channels.foreach(
              DiscordSender
                .sendMessage(_, s"New block generated at ${Instant.ofEpochMilli(block.getHeader.timestamp).atZone(ZoneId.of("Europe/Moscow"))}")
            )

          lastMined = Instant.now()
          lastMinedNotified = 0
        } else if (lastMined.plus(Duration.ofHours(4)).compareTo(Instant.now()) < 0 && lastMinedNotified < 2) {
          channels.foreach(DiscordSender.sendMessage(_, "@everyone **Warning**: Last block generated more than 4 hours ago"))
          sendNextDelay()
          lastMinedNotified = 2
        } else if (lastMined.plus(Duration.ofHours(3)).compareTo(Instant.now()) < 0 && lastMinedNotified < 1) {
          channels.foreach(DiscordSender.sendMessage(_, "**Warning**: Last block generated more than 3 hours ago"))
          sendNextDelay()
          lastMinedNotified = 1
        }
      }

      if (rewards != 0 && isToday) Stats.update(rewards)
      Stats.publish(channels)

    case e @ BlockchainUpdated(_, height, BlockchainUpdated.Update.Rollback(rollback))
        if rollback.isBlock && db.lastHeight >= height && rollbacksEnabled =>
      if (height < db.lastHeight - 100) log.warn(s"Rollback to $height: $e")
      if (height < db.lastHeight - 10000) {
        channels.foreach(DiscordSender.sendMessage(_, s"Unable to rollback to $height, stopping"))
        sys.error(s"Unable to rollback to $height")
      }
      db.rollback(height)
      aggr.rollback(height, aggr.keyBlockId)

    case BlockchainUpdated(id, height, BlockchainUpdated.Update.Rollback(rollback)) if rollback.isMicroblock && height > startHeight =>
      // log.info(s"Rollback to ${ByteStr(id.toByteArray)}")
      aggr.rollback(height, id)

    case e => // log.warn(s"Event ignored at ${e.height}: ${e.id} ${e.update.getClass.toString}")
  })

  object Stats {
    val zoneId: ZoneId = ZoneId.of("Europe/Moscow")
    var sendTime       = LocalDate.now().atTime(18, 0).atZone(zoneId)
    var balance        = 0L

    def update(rewards: Long): Unit = {
      this.balance += rewards
      log.info(s"Today rewards is ${balance.toDouble / 100000000} waves")
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
           |Daily profit: ${balance.toDouble / 100000000} waves
           |Total profit: ${db.totalProfit.toDouble / 100000000} waves""".stripMargin

      DiscordSender.sendMessage(channel, msg)
    }
  }
}
