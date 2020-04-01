package com.wavesplatform.settings

import cats.instances.long.catsKernelStdGroupForLong
import cats.instances.map.catsKernelStdCommutativeMonoidForMap
import cats.kernel.Monoid
import com.typesafe.config.Config
import com.wavesplatform.account.Address
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.settings.TrackingAddressAssetsSettings.ReceiversSettings
import com.wavesplatform.state.Portfolio
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.utils.ScorexLogging
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.EnumerationReader._
import net.ceedubs.ficus.readers.{CollectionReaders, ValueReader}

import scala.collection.immutable.TreeMap
import scala.concurrent.duration._

case class RewardsSettings(
    term: Int,
    initial: Long,
    minIncrement: Long,
    votingInterval: Int
) {
  require(initial >= 0, "initial must be greater than or equal to 0")
  require(minIncrement > 0, "minIncrement must be greater than 0")
  require(term > 0, "term must be greater than 0")
  require(votingInterval > 0, "votingInterval must be greater than 0")
  require(votingInterval <= term, s"votingInterval must be less than or equal to term($term)")

  def nearestTermEnd(activatedAt: Int, height: Int): Int = {
    require(height >= activatedAt)
    val diff = height - activatedAt + 1
    val mul  = math.ceil(diff.toDouble / term).toInt
    activatedAt + mul * term - 1
  }

  def votingWindow(activatedAt: Int, height: Int): Range = {
    val end   = nearestTermEnd(activatedAt, height)
    val start = end - votingInterval + 1
    if (height >= start) Range.inclusive(start, height)
    else Range(0, 0)
  }
}

object RewardsSettings {
  val MAINNET = apply(
    100000,
    6 * Constants.UnitsInWave,
    50000000,
    10000
  )

  val TESTNET = apply(
    100000,
    6 * Constants.UnitsInWave,
    50000000,
    10000
  )

  val STAGENET = apply(
    100000,
    6 * Constants.UnitsInWave,
    50000000,
    10000
  )
}

case class FunctionalitySettings(
    featureCheckBlocksPeriod: Int,
    blocksForFeatureActivation: Int,
    generationBalanceDepthFrom50To1000AfterHeight: Int = 0,
    blockVersion3AfterHeight: Int = 0,
    preActivatedFeatures: Map[Short, Int] = Map.empty,
    doubleFeaturesPeriodsAfterHeight: Int,
    maxTransactionTimeBackOffset: FiniteDuration = 120.minutes,
    maxTransactionTimeForwardOffset: FiniteDuration = 90.minutes,
    lastTimeBasedForkParameter: Long = 0L,
    leaseExpiration: Int = 1000000,
    estimatorPreCheckHeight: Int = 0,
    minAssetInfoUpdateInterval: Int = 100000,
    minBlockTime: FiniteDuration = 15.seconds,
    delayDelta: Int = 8,
    trackingAddressAssets: TrackingAddressAssetsSettings
) {
  val allowLeasedBalanceTransferUntilHeight: Int        = blockVersion3AfterHeight
  val allowTemporaryNegativeUntil                       = lastTimeBasedForkParameter
  val minimalGeneratingBalanceAfter                     = lastTimeBasedForkParameter
  val allowTransactionsFromFutureUntil                  = lastTimeBasedForkParameter
  val allowUnissuedAssetsUntil                          = lastTimeBasedForkParameter
  val allowInvalidReissueInSameBlockUntilTimestamp      = lastTimeBasedForkParameter
  val allowMultipleLeaseCancelTransactionUntilTimestamp = lastTimeBasedForkParameter

  require(featureCheckBlocksPeriod > 0, "featureCheckBlocksPeriod must be greater than 0")
  require(
    (blocksForFeatureActivation > 0) && (blocksForFeatureActivation <= featureCheckBlocksPeriod),
    s"blocksForFeatureActivation must be in range 1 to $featureCheckBlocksPeriod"
  )
  require(minAssetInfoUpdateInterval >= 0, "minAssetInfoUpdateInterval must be greater than or equal to 0")

  def activationWindowSize(height: Int): Int =
    featureCheckBlocksPeriod * (if (height <= doubleFeaturesPeriodsAfterHeight) 1 else 2)

  def activationWindow(height: Int): Range =
    if (height < 1) Range(0, 0)
    else {
      val ws = activationWindowSize(height)
      Range.inclusive((height - 1) / ws * ws + 1, ((height - 1) / ws + 1) * ws)
    }

  def blocksForFeatureActivation(height: Int): Int =
    blocksForFeatureActivation * (if (height <= doubleFeaturesPeriodsAfterHeight) 1 else 2)

  def generatingBalanceDepth(height: Int): Int =
    if (height >= generationBalanceDepthFrom50To1000AfterHeight) 1000 else 50
}

object FunctionalitySettings {
  val MAINNET = apply(
    featureCheckBlocksPeriod = 5000,
    blocksForFeatureActivation = 4000,
    generationBalanceDepthFrom50To1000AfterHeight = 232000,
    lastTimeBasedForkParameter = 1530161445559L,
    blockVersion3AfterHeight = 795000,
    doubleFeaturesPeriodsAfterHeight = 810000,
    estimatorPreCheckHeight = 1847610,
    trackingAddressAssets = TrackingAddressAssetsSettings.empty
  )

  val TESTNET = apply(
    featureCheckBlocksPeriod = 3000,
    blocksForFeatureActivation = 2700,
    blockVersion3AfterHeight = 161700,
    doubleFeaturesPeriodsAfterHeight = Int.MaxValue,
    lastTimeBasedForkParameter = 1492560000000L,
    estimatorPreCheckHeight = 817380,
    trackingAddressAssets = TrackingAddressAssetsSettings.empty
  )

  val STAGENET = apply(
    featureCheckBlocksPeriod = 100,
    blocksForFeatureActivation = 40,
    doubleFeaturesPeriodsAfterHeight = 1000000000,
    preActivatedFeatures = (1 to 13).map(_.toShort -> 0).toMap,
    minAssetInfoUpdateInterval = 10,
    trackingAddressAssets = TrackingAddressAssetsSettings.empty
  )

  val configPath = "waves.blockchain.custom.functionality"
}

case class GenesisTransactionSettings(recipient: String, amount: Long)

case class GenesisSettings(
    blockTimestamp: Long,
    timestamp: Long,
    initialBalance: Long,
    signature: Option[ByteStr],
    transactions: Seq[GenesisTransactionSettings],
    initialBaseTarget: Long,
    averageBlockDelay: FiniteDuration
)

object GenesisSettings {
  val MAINNET = GenesisSettings(
    1460678400000L,
    1465742577614L,
    Constants.UnitsInWave * Constants.TotalWaves,
    ByteStr.decodeBase58("FSH8eAAzZNqnG8xgTZtz5xuLqXySsXgAjmFEC25hXMbEufiGjqWPnGCZFt6gLiVLJny16ipxRNAkkzjjhqTjBE2").toOption,
    List(
      GenesisTransactionSettings("3PAWwWa6GbwcJaFzwqXQN5KQm7H96Y7SHTQ", Constants.UnitsInWave * Constants.TotalWaves - 5 * Constants.UnitsInWave),
      GenesisTransactionSettings("3P8JdJGYc7vaLu4UXUZc1iRLdzrkGtdCyJM", Constants.UnitsInWave),
      GenesisTransactionSettings("3PAGPDPqnGkyhcihyjMHe9v36Y4hkAh9yDy", Constants.UnitsInWave),
      GenesisTransactionSettings("3P9o3ZYwtHkaU1KxsKkFjJqJKS3dLHLC9oF", Constants.UnitsInWave),
      GenesisTransactionSettings("3PJaDyprvekvPXPuAtxrapacuDJopgJRaU3", Constants.UnitsInWave),
      GenesisTransactionSettings("3PBWXDFUc86N2EQxKJmW8eFco65xTyMZx6J", Constants.UnitsInWave)
    ),
    153722867L,
    60.seconds
  )

  val TESTNET = GenesisSettings(
    1460678400000L,
    1478000000000L,
    Constants.UnitsInWave * Constants.TotalWaves,
    ByteStr.decodeBase58("5uqnLK3Z9eiot6FyYBfwUnbyid3abicQbAZjz38GQ1Q8XigQMxTK4C1zNkqS1SVw7FqSidbZKxWAKLVoEsp4nNqa").toOption,
    List(
      GenesisTransactionSettings("3My3KZgFQ3CrVHgz6vGRt8687sH4oAA1qp8", (Constants.UnitsInWave * Constants.TotalWaves * 0.04).toLong),
      GenesisTransactionSettings("3NBVqYXrapgJP9atQccdBPAgJPwHDKkh6A8", (Constants.UnitsInWave * Constants.TotalWaves * 0.02).toLong),
      GenesisTransactionSettings("3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh", (Constants.UnitsInWave * Constants.TotalWaves * 0.02).toLong),
      GenesisTransactionSettings("3NCBMxgdghg4tUhEEffSXy11L6hUi6fcBpd", (Constants.UnitsInWave * Constants.TotalWaves * 0.02).toLong),
      GenesisTransactionSettings(
        "3N18z4B8kyyQ96PhN5eyhCAbg4j49CgwZJx",
        (Constants.UnitsInWave * Constants.TotalWaves - Constants.UnitsInWave * Constants.TotalWaves * 0.1).toLong
      )
    ),
    153722867L,
    60.seconds
  )

  val STAGENET = GenesisSettings(
    1561705836768L,
    1561705836768L,
    Constants.UnitsInWave * Constants.TotalWaves,
    ByteStr.decodeBase58("2EaaguFPgrJ1bbMAFrPw2bi6i7kqjgvxsFj8YGqrKR7hT54ZvwmzZ3LHMm4qR7i7QB5cacp8XdkLMJyvjFkt8VgN").toOption,
    List(
      GenesisTransactionSettings("3Mi63XiwniEj6mTC557pxdRDddtpj7fZMMw", Constants.UnitsInWave * Constants.TotalWaves)
    ),
    5000,
    1.minute
  )
}

case class BlockchainSettings(
    addressSchemeCharacter: Char,
    functionalitySettings: FunctionalitySettings,
    genesisSettings: GenesisSettings,
    rewardsSettings: RewardsSettings
)

object BlockchainType extends Enumeration {
  val STAGENET = Value("STAGENET")
  val TESTNET  = Value("TESTNET")
  val MAINNET  = Value("MAINNET")
  val CUSTOM   = Value("CUSTOM")
}

object BlockchainSettings {
  implicit val assetReader: ValueReader[Asset] = Asset.assetReader // IDEA removes import

  implicit val addressReader: ValueReader[Address] = (cfg: Config, path: String) => Address.fromString(cfg.getString(path)).explicitGet()

  implicit val addressAssetReader: ValueReader[(Address, Asset)] = { (cfg: Config, path: String) =>
    val address = addressReader.read(cfg, s"$path.0")
    val asset   = assetReader.read(cfg, s"$path.1")
    (address, asset)
  }

  implicit def setReader[T](itemReader: ValueReader[T]): ValueReader[Set[T]] =
    CollectionReaders.traversableReader[List, T](itemReader, List.iterableFactory[T]).map(_.toSet)

  implicit def byteStrKeyMapReader[T](itemReader: ValueReader[T]): ValueReader[Map[ByteStr, T]] =
    CollectionReaders.mapValueReader[T](itemReader).map { xs =>
      xs.map {
        case (k, v) => ByteStr.decodeBase58(k).getOrElse(throw new IllegalArgumentException(s"Can't decode key: '$k'")) -> v
      }
    }

  implicit val byteStrAddressAssetMapReader: ValueReader[Map[ByteStr, ReceiversSettings]] =
    byteStrKeyMapReader[ReceiversSettings](implicitly[ValueReader[ReceiversSettings]]).read _

  implicit val valueReader: ValueReader[BlockchainSettings] =
    (cfg: Config, path: String) => fromConfig(cfg.getConfig(path))

  // @deprecated("Use config.as[BlockchainSettings]", "0.17.0")
  def fromRootConfig(config: Config): BlockchainSettings = config.as[BlockchainSettings]("waves.blockchain")

  private[this] def fromConfig(config: Config): BlockchainSettings = {
    def trackingAddressAssets = config.as[TrackingAddressAssetsSettings]("custom.functionality.tracking-address-assets")

    val blockchainType = config.as[BlockchainType.Value]("type")
    val addressSchemeCharacter = blockchainType match {
      case BlockchainType.STAGENET => 'S'
      case BlockchainType.TESTNET  => 'T'
      case BlockchainType.MAINNET  => 'W'
      case BlockchainType.CUSTOM   => config.as[String](s"custom.address-scheme-character").charAt(0)
    }

    val (functionalitySettings, genesisSettings, rewardsSettings) = blockchainType match {
      case BlockchainType.STAGENET =>
        (FunctionalitySettings.STAGENET.copy(trackingAddressAssets = trackingAddressAssets), GenesisSettings.STAGENET, RewardsSettings.STAGENET)
      case BlockchainType.TESTNET =>
        (FunctionalitySettings.TESTNET.copy(trackingAddressAssets = trackingAddressAssets), GenesisSettings.TESTNET, RewardsSettings.TESTNET)
      case BlockchainType.MAINNET =>
        (FunctionalitySettings.MAINNET.copy(trackingAddressAssets = trackingAddressAssets), GenesisSettings.MAINNET, RewardsSettings.MAINNET)
      case BlockchainType.CUSTOM =>
        val functionality = config.as[FunctionalitySettings](s"custom.functionality")
        val genesis       = config.as[GenesisSettings](s"custom.genesis")
        val rewards       = config.as[RewardsSettings](s"custom.rewards")
        require(functionality.minBlockTime <= genesis.averageBlockDelay, "minBlockTime should be <= averageBlockDelay")
        (functionality, genesis, rewards)
    }

    BlockchainSettings(
      addressSchemeCharacter = addressSchemeCharacter,
      functionalitySettings = functionalitySettings,
      genesisSettings = genesisSettings,
      rewardsSettings = rewardsSettings
    )
  }
}

/**
  * @param transfers txId -> Set[(receiver, asset)]
  * @note (txId, receivers: Set[(Address, Asset)]) is better, than (address, assets: Map[Asset, Long], startHeight), because
  *       we can have multiple blacklisted by settings transactions on same height.
  */
case class TrackingAddressAssetsSettings(transfers: Map[ByteStr, ReceiversSettings], whitelistedTxs: Set[ByteStr]) {
  val filteredTransfers = transfers.filter {
    case (txId, _) => !whitelistedTxs.contains(txId)
  }

  val blacklistedTxs: Set[ByteStr] = filteredTransfers.keySet

  val trackedAssets: Set[Asset] = {
    val r = for {
      (_, receivers) <- filteredTransfers
      x              <- receivers.receivers
    } yield x.asset
    r.toSet
  }

  lazy val rawTransfers: Map[ByteStr, Set[(Address, Asset)]] = filteredTransfers.map {
    case (k, xs) => k -> xs.receivers.map(x => (Address.fromString(x.address).explicitGet(), x.asset))
  }
}

object TrackingAddressAssetsSettings extends ScorexLogging {
  case class ReceiversSettings(receivers: Set[ReceiverItemSettings])
  case class ReceiverItemSettings(address: String, asset: Asset)

  val empty: TrackingAddressAssetsSettings = TrackingAddressAssetsSettings(Map.empty, Set.empty)

  private implicit val addressOrdering: Ordering[Address] = Ordering.by[Address, ByteStr](x => ByteStr(x.bytes))

  /**
    * @return Tracking diff
    * @note Can't be applied to the block diff:
    *       1. Alice burns her black assets
    *       2. Bob reissue same assets
    *       3. The portfolio will show, that Alice spent assets and Bob receives.
    *          But this doesn't mean that alice transfers assets to Bob.
    *       Fortunately, there is no such transaction that burns and reissues at same time.
    *       So, we can process both transactions independently.
    */
  def trackingDiff(
      settings: TrackingAddressAssetsSettings,
      txId: ByteStr,
      txDiff: Map[Address, Portfolio],
      balance: (Address, Asset) => Long,
      badAddressAssetAmount: (Address, Asset) => Long
  ): Map[Address, Map[Asset, Long]] =
    if (settings.whitelistedTxs.contains(txId)) Map.empty
    else {
      type Transfers = Map[Asset, Map[Address, Long]]

      def twist(valueCond: Long => Boolean, mapValue: Long => Long): Transfers =
        txDiff.foldLeft(Map.empty: Transfers) {
          case (r, (address, portfolio)) =>
            val xs = ((Waves -> portfolio.balance) :: portfolio.assets.toList).collect {
              case (asset, v) if valueCond(v) && settings.trackedAssets.contains(asset) => Map((asset: Asset) -> TreeMap(address -> mapValue(v)))
            }
            Monoid.combineAll(r :: xs)
        }

      val sent: Transfers        = twist(_ < 0, identity)
      val receivedAll: Transfers = twist(_ > 0, identity)

      val blacklistedTxSettings = settings.rawTransfers.getOrElse(txId, Set.empty)

      val (received, blacklistedTxDiff) = blacklistedTxSettings.foldLeft((receivedAll, Map.empty[Address, Map[Asset, Long]])) {
        case ((received, blacklistedDiff), (address, asset)) =>
          received.get(asset) match {
            case None => (received, blacklistedDiff)
            case Some(rx) =>
              rx.get(address) match {
                case None    => (received, blacklistedDiff)
                case Some(v) => (received.updated(asset, rx - address), Monoid.combine(Map(address -> Map(asset -> v)), blacklistedDiff))
              }
          }
      }

      @scala.annotation.tailrec
      def removeGood(from: Map[Address, Long], amount: Long): Map[Address, Long] = from.headOption match {
        case Some((address, v)) if amount > 0 =>
          val updatedV = v - amount
          if (updatedV > 0) from.updated(address, updatedV)
          else if (updatedV == 0) from - address
          else removeGood(from - address, -updatedV)

        case _ => from // Could be a burn case
      }

      @scala.annotation.tailrec
      def removeBad(
          from: Map[Address, Long],
          amount: Long,
          accDiff: Map[Address, Long] = Map.empty,
          accBad: Long = 0
      ): (Map[Address, Long], Map[Address, Long], Long) =
        from.headOption match {
          case Some((address, v)) if amount > 0 =>
            val updatedV = v - amount

            def updatedAcc(plus: Long) = accDiff.updated(address, accDiff.getOrElse(address, 0L) + plus)
            if (updatedV > 0) (from.updated(address, updatedV), updatedAcc(amount), accBad + amount)
            else if (updatedV == 0) (from - address, updatedAcc(amount), accBad + amount)
            else removeBad(from - address, -updatedV, updatedAcc(v), accBad + v)

          case _ => (from, accDiff, amount + accBad) // Could be a burn case
        }

      def remove(from: Map[Address, Long], goodAmount: Long, badAmount: Long) =
        removeBad(removeGood(from, goodAmount), badAmount)

      def joinLeft(a: Transfers, b: Transfers): Map[Asset, (Map[Address, Long], Map[Address, Long])] = a.map {
        case (k, v1) => (k, (v1, b.getOrElse(k, Map.empty)))
      }

      val r = Monoid.combineAll {
        // Remove good
        val r = joinLeft(sent, received).toList.map {
          case (asset, (ss, rs)) =>
            val (_, diff) = ss.foldLeft((rs, Map.empty[Address, Long])) {
              case ((rs, diff), (sender, sentAmountNegative)) =>
                val sentAmount     = -sentAmountNegative
                val senderHasTotal = balance(sender, asset)
                val senderHasBad   = badAddressAssetAmount(sender, asset)
                val senderHasGood  = senderHasTotal - senderHasBad

                val (sentGood, sentBad) = {
                  val x = sentAmount - senderHasGood
                  if (x <= 0) (sentAmount, 0L)
                  else (senderHasGood, math.min(senderHasBad, x))
                }

                val (restRs, newDiff, reallySpentBad) = remove(rs, sentGood, sentBad)

                val senderDiff  = if (reallySpentBad > 0) Map(sender -> -reallySpentBad) else Map.empty[Address, Long]
                val updatedDiff = Monoid.combineAll(List(diff, newDiff, senderDiff))

                (restRs, updatedDiff)
            }

            Monoid.combineAll {
              diff.map {
                case (address, v) => Map(address -> Map(asset -> v))
              }
            }
        }
        blacklistedTxDiff :: r
      }

      if (r.nonEmpty) log.info(s"Tracked changes. txId=$txId, diff=$r")

      r
    }
}
