package com.wavesplatform.settings

import java.nio.charset.StandardCharsets

import cats.instances.long.catsKernelStdGroupForLong
import cats.instances.map.catsKernelStdMonoidForMap
import cats.instances.tuple._
import cats.kernel.Monoid
import com.wavesplatform.NoShrink
import com.wavesplatform.account.{Address, KeyPair}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.settings.TrackingAddressAssetsSettings.{ReceiverItemSettings, ReceiversSettings, trackingDiff}
import com.wavesplatform.state.Portfolio
import com.wavesplatform.state.Portfolio.monoid
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import org.scalacheck.Gen
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FreeSpec, Matchers}
import org.scalatestplus.scalacheck.{ScalaCheckPropertyChecks => PropertyChecks}

import scala.jdk.CollectionConverters._
import scala.util.Random

class TrackingAddressAssetsSettingsSpec extends FreeSpec with PropertyChecks with Matchers with MockFactory with NoShrink {
  private val goodAsset = IssuedAsset(ByteStr("good asset".getBytes(StandardCharsets.UTF_8)))
  private val badAsset  = IssuedAsset(ByteStr("bad asset".getBytes(StandardCharsets.UTF_8)))

  private val badGuy1  = KeyPair(ByteStr("bad guy 1 seed".getBytes(StandardCharsets.UTF_8)))
  private val badGuy2  = KeyPair(ByteStr("bad guy 2 seed".getBytes(StandardCharsets.UTF_8)))
  private val badGuy3  = KeyPair(ByteStr("bad guy 3 seed".getBytes(StandardCharsets.UTF_8)))
  private val badGuy4  = KeyPair(ByteStr("bad guy 4 seed".getBytes(StandardCharsets.UTF_8)))
  private val goodGuy1 = KeyPair(ByteStr("good guy 1 seed".getBytes(StandardCharsets.UTF_8)))
  private val goodGuy2 = KeyPair(ByteStr("good guy 2 seed".getBytes(StandardCharsets.UTF_8)))
  private val allGuys  = List(badGuy1, badGuy2, badGuy3, badGuy4, goodGuy1, goodGuy2)

  // Useful for debugging
  //  println(s"""Guys addresses:
  //       |badGuy1:  ${badGuy1.toAddress}
  //       |badGuy2:  ${badGuy2.toAddress}
  //       |badGuy3:  ${badGuy3.toAddress}
  //       |badGuy4:  ${badGuy4.toAddress}
  //       |goodGuy1: ${goodGuy1.toAddress}
  //       |goodGuy2: ${goodGuy2.toAddress}
  //       |""".stripMargin)

  "trackingDiff" - {
    "invariants" - {
      "issued asset" - invariantsTests(badAsset)
      "waves" - invariantsTests(Waves)
    }

    "ignore" - {
      "no settings, no tracked, no balance" in {
        trackingDiff(
          settings = mkSettings(),
          txId = mkTxId,
          txDiff = mkTxDiff(goodGuy1, goodGuy2, goodAsset, 100),
          balance = mkBalanceFn(),
          badAddressAssetAmount = mkBalanceFn()
        ) shouldBe empty
      }

      "no settings, no tracked, balance" in {
        trackingDiff(
          settings = mkSettings(),
          txId = mkTxId,
          txDiff = mkTxDiff(goodGuy1, goodGuy2, goodAsset, 100),
          balance = mkBalanceFn(goodGuy1, goodAsset, 100),
          badAddressAssetAmount = mkBalanceFn()
        ) shouldBe empty
      }

      "issued asset" - ignoreTests(badAsset)
      "waves" - ignoreTests(Waves)
    }

    "track" - {
      "issued asset" - trackTests(badAsset)
      "waves" - trackTests(Waves)
    }

    "whitelists" - whitelistsTests()
  }

  private def whitelistsTests(): Unit = {
    "ignore whitelisted tx" in {
      val txId = mkTxId
      trackingDiff(
        settings = mkSettings(txId, badGuy1, badAsset).copy(whitelistedTxs = Set(txId)),
        txId = txId,
        txDiff = mkTxDiff(goodGuy1, badGuy1, badAsset, 100),
        balance = mkBalanceFn(goodGuy1, badAsset, 100),
        badAddressAssetAmount = mkBalanceFn()
      ) shouldBe empty
    }

    "not ignore non-whitelisted tx" in {
      val txId = mkTxId
      trackingDiff(
        settings = mkSettings(txId, badGuy1, badAsset).copy(whitelistedTxs = Set(mkTxId, mkTxId)),
        txId = txId,
        txDiff = mkTxDiff(goodGuy1, badGuy1, badAsset, 100),
        balance = mkBalanceFn(goodGuy1, badAsset, 100),
        badAddressAssetAmount = mkBalanceFn()
      ) shouldBe mkResult(badGuy1, badAsset, 100)
    }
  }

  private def invariantsTests(badAsset: Asset): Unit = {
    val settings = mkTrackBadAssetSettings(badAsset)
    val txId     = mkTxId

    "quantity isn't changed - amount of bad remains" in forAll(constQuantityGen(badAsset)) {
      case (transfers, total, bad) =>
        val diff = trackingDiff(
          settings = settings,
          txId = txId,
          txDiff = transfers,
          balance = Function.untupled(total),
          badAddressAssetAmount = Function.untupled(bad)
        )

        val changedBad = diff.flatMap { case (_, xs) => xs.values }.sum
        changedBad shouldBe 0
    }

    "reissue - amount of bad assets remains" in forAll(reissueTestGen(badAsset)) {
      case (transfers, total, bad) =>
        val diff = trackingDiff(
          settings = settings,
          txId = txId,
          txDiff = transfers,
          balance = Function.untupled(total),
          badAddressAssetAmount = Function.untupled(bad)
        )

        val changedBad = diff.flatMap { case (_, xs) => xs.values }.sum
        changedBad shouldBe 0
    }

    "burn - amount of bad assets remains or becomes less" in forAll(burnTestGen(badAsset)) {
      case (transfers, total, bad) =>
        val diff = trackingDiff(
          settings = settings,
          txId = txId,
          txDiff = transfers,
          balance = Function.untupled(total),
          badAddressAssetAmount = Function.untupled(bad)
        )

        val changedBad = diff.flatMap { case (_, xs) => xs.values }.sum
        changedBad should be <= 0L
    }
  }

  private def ignoreTests(badAsset: Asset): Unit = {
    "has other asset settings, no tracked" in {
      trackingDiff(
        settings = mkSettings(mkTxId, badGuy1, badAsset),
        txId = mkTxId,
        txDiff = mkTxDiff(goodGuy1, goodGuy2, goodAsset, 100),
        balance = mkBalanceFn(goodGuy1, goodAsset, 100),
        badAddressAssetAmount = mkBalanceFn()
      ) shouldBe empty
    }

    "goodGuy1 sends to badGuy1 bad asset, badGuy1 has bad coins" in {
      val txId = mkTxId
      trackingDiff(
        settings = mkTrackBadAssetSettings(badAsset),
        txId = txId,
        txDiff = mkTxDiff(goodGuy1, badGuy1, badAsset, 100),
        balance = mkBalanceFn(goodGuy1, badAsset, 100),
        badAddressAssetAmount = mkBalanceFn(badGuy1, badAsset, 100)
      ) shouldBe empty
    }

    "badGuy1 sends to goodGuy1 bad asset (a good part), badGuy1 has both good and bad coins" in {
      trackingDiff(
        settings = mkTrackBadAssetSettings(badAsset),
        txId = mkTxId,
        txDiff = mkTxDiff(badGuy1, goodGuy1, badAsset, 100),
        balance = mkBalanceFn(badGuy1, badAsset, 200),
        badAddressAssetAmount = mkBalanceFn(badGuy1, badAsset, 100)
      ) shouldBe empty
    }

    "badGuy1 sends to badGuy2, badGuy1 is tracked" - {
      "has no settings (tracking for badGuy1 was enabled before, but now is disabled)" in {
        trackingDiff(
          settings = mkSettings(),
          txId = mkTxId,
          txDiff = mkTxDiff(badGuy1, badGuy2, badAsset, 100),
          balance = mkBalanceFn(badGuy1, badAsset, 250),
          badAddressAssetAmount = mkBalanceFn(badGuy1, badAsset, 250)
        ) shouldBe empty // performance filter
      }

      "wrong sender in settings" - {
        def test(receiver: KeyPair): Unit = {
          val txId = mkTxId
          trackingDiff(
            settings = mkSettings(txId, receiver, badAsset),
            txId = txId,
            txDiff = mkTxDiff(badGuy1, badGuy2, badAsset, 100),
            balance = mkBalanceFn(badGuy1, badAsset, 250),
            badAddressAssetAmount = mkBalanceFn(badGuy2, badAsset, 250)
          ) shouldBe empty
        }

        "sender guy" in test(badGuy1)
        "other guy" in test(badGuy3)
      }
    }

    "badGuy1 issues bad asset" in {
      trackingDiff(
        settings = mkTrackBadAssetSettings(badAsset),
        txId = mkTxId,
        txDiff = Map(badGuy1.publicKey.toAddress -> {
          badAsset match {
            case asset: IssuedAsset => Portfolio.empty.copy(assets = Map(asset -> 100))
            case Waves              => Portfolio.empty.copy(balance = 100)
          }
        }),
        balance = mkBalanceFn(badGuy1, badAsset, 100),
        badAddressAssetAmount = mkBalanceFn(badGuy1, badAsset, 100)
      ) shouldBe empty
    }

    "badGuy1 sends bad assets to himself" - {
      "only bad" in {
        trackingDiff(
          settings = mkTrackBadAssetSettings(badAsset),
          txId = mkTxId,
          txDiff = mkTxDiff(badGuy1, badGuy1, badAsset, 100),
          balance = mkBalanceFn(badGuy1, badAsset, 100),
          badAddressAssetAmount = mkBalanceFn(badGuy1, badAsset, 100)
        ) shouldBe empty
      }

      "both" in {
        trackingDiff(
          settings = mkTrackBadAssetSettings(badAsset),
          txId = mkTxId,
          txDiff = mkTxDiff(badGuy1, badGuy1, badAsset, 100),
          balance = mkBalanceFn(badGuy1, badAsset, 100),
          badAddressAssetAmount = mkBalanceFn(badGuy1, badAsset, 70)
        ) shouldBe empty
      }
    }

    "badGuy1 sends both bad asset coins and good asset coins - ignore good coins" in {
      trackingDiff(
        settings = mkTrackBadAssetSettings(badAsset),
        txId = mkTxId,
        txDiff = mkTxDiff(
          (badGuy1, badGuy2, badAsset, 100),
          (badGuy1, badGuy2, goodAsset, 50)
        ),
        balance = mkBalanceFn(
          (badGuy1, badAsset, 100),
          (badGuy1, goodAsset, 100)
        ),
        badAddressAssetAmount = mkBalanceFn(badGuy1, badAsset, 100)
      ) shouldBe mkResultMany(
        (badGuy1, badAsset, -100),
        (badGuy2, badAsset, 100)
      )
    }
  }

  private def trackTests(badAsset: Asset): Unit = {
    "badGuy1 burns bad coins" in {
      trackingDiff(
        settings = mkTrackBadAssetSettings(badAsset),
        txId = mkTxId,
        txDiff = Map(badGuy1.publicKey.toAddress -> {
          badAsset match {
            case asset: IssuedAsset => Portfolio.empty.copy(assets = Map(asset -> -100))
            case Waves              => Portfolio.empty.copy(balance = -100)
          }
        }),
        balance = mkBalanceFn(badGuy1, badAsset, 100),
        badAddressAssetAmount = mkBalanceFn(badGuy1, badAsset, 100)
      ) shouldBe mkResult(
        badGuy1,
        badAsset,
        -100
      )
    }

    "badGuy1 sends to badGuy2 and badGuy3, badGuy1 is tracked" in {
      val txId = mkTxId
      trackingDiff(
        settings = mkTrackBadAssetSettings(badAsset),
        txId = txId,
        txDiff = mkTxDiff(
          (badGuy1, badGuy2, badAsset, 80),
          (badGuy1, badGuy3, badAsset, 70)
        ),
        balance = mkBalanceFn(badGuy1, badAsset, 150),
        badAddressAssetAmount = mkBalanceFn(badGuy1, badAsset, 100)
      ) shouldBe mkResultMany(
        (badGuy1, badAsset, -100),
        (badGuy2, badAsset, 30),
        (badGuy3, badAsset, 70)
      )
    }

    "badGuy1 sends to badGuy2, badGuy2 is not tracked, but in settings" in {
      val txId = mkTxId
      trackingDiff(
        settings = mkSettings(txId, badGuy2, badAsset),
        txId = txId,
        txDiff = mkTxDiff(badGuy1, badGuy2, badAsset, 100),
        balance = mkBalanceFn(badGuy1, badAsset, 100),
        badAddressAssetAmount = mkBalanceFn()
      ) shouldBe mkResult(
        badGuy2,
        badAsset,
        100
      )
    }

    "badGuy1 sends to badGuy2, badGuy1 - tracked & !settings, badGuy2 - !tracked & settings" in {
      val txId = mkTxId
      trackingDiff(
        settings = mkSettings(txId, badGuy2, badAsset),
        txId = txId,
        txDiff = mkTxDiff(badGuy1, badGuy2, badAsset, 100),
        balance = mkBalanceFn(badGuy1, badAsset, 100),
        badAddressAssetAmount = mkBalanceFn(badGuy1, badAsset, 50)
      ) shouldBe mkResultMany(
        (badGuy1, badAsset, -50),
        (badGuy2, badAsset, 100)
      )
    }

    "badGuy1 sends to badGuy2, badGuy1 is tracked, and not in settings" - {
      "a part of blocked assets" in {
        val txId = mkTxId
        trackingDiff(
          settings = mkTrackBadAssetSettings(badAsset),
          txId = txId,
          txDiff = mkTxDiff(badGuy1, badGuy2, badAsset, 50),
          balance = mkBalanceFn(badGuy1, badAsset, 100),
          badAddressAssetAmount = mkBalanceFn(badGuy1, badAsset, 100)
        ) shouldBe mkResultMany(
          (badGuy1, badAsset, -50),
          (badGuy2, badAsset, 50)
        )
      }

      "all bad coins" in {
        val txId = mkTxId
        trackingDiff(
          settings = mkTrackBadAssetSettings(badAsset),
          txId = txId,
          txDiff = mkTxDiff(badGuy1, badGuy2, badAsset, 100),
          balance = mkBalanceFn(badGuy1, badAsset, 100),
          badAddressAssetAmount = mkBalanceFn((badGuy1, badAsset, 100))
        ) shouldBe mkResultMany(
          (badGuy1, badAsset, -100),
          (badGuy2, badAsset, 100)
        )
      }

      "both good and bad coins" in {
        val txId = mkTxId
        trackingDiff(
          settings = mkTrackBadAssetSettings(badAsset),
          txId = txId,
          txDiff = mkTxDiff(badGuy1, badGuy2, badAsset, 100),
          balance = mkBalanceFn(badGuy1, badAsset, 100),
          badAddressAssetAmount = mkBalanceFn((badGuy1, badAsset, 50))
        ) shouldBe mkResultMany(
          (badGuy1, badAsset, -50),
          (badGuy2, badAsset, 50)
        )
      }
    }
  }

  private def constQuantityGen(badAsset: Asset): Gen[(Map[Address, Portfolio], Map[(Address, Asset), Long], Map[(Address, Asset), Long])] = {
    val allGuysAddress = allGuys.map(_.toAddress)
    for {
      transfers    <- constDiffsGen(badAsset, allGuysAddress)
      (total, bad) <- balancesGen(requiredCoinsGen(transfers))
    } yield (transfers, total, bad)
  }

  private def reissueTestGen(badAsset: Asset): Gen[(Map[Address, Portfolio], Map[(Address, Asset), Long], Map[(Address, Asset), Long])] = {
    val allGuysAddress = allGuys.map(_.toAddress)
    for {
      transfers    <- reissueDiffsGen(badAsset, allGuysAddress)
      (total, bad) <- balancesGen(requiredCoinsGen(transfers))
    } yield (transfers, total, bad)
  }

  private def burnTestGen(badAsset: Asset): Gen[(Map[Address, Portfolio], Map[(Address, Asset), Long], Map[(Address, Asset), Long])] = {
    val allGuysAddress = allGuys.map(_.toAddress)
    for {
      transfers    <- burnDiffsGen(badAsset, allGuysAddress)
      (total, bad) <- balancesGen(requiredCoinsGen(transfers))
    } yield (transfers, total, bad)
  }

  private def mkTrackBadAssetSettings(badAsset: Asset): TrackingAddressAssetsSettings =
    mkSettings(mkTxId, KeyPair(mkTxId.arr), badAsset)

  private def mkSettings(txId: ByteStr, receiver: KeyPair, receivedAsset: Asset): TrackingAddressAssetsSettings =
    mkSettings((txId, receiver, receivedAsset))

  private def mkSettings(settings: (ByteStr, KeyPair, Asset)*): TrackingAddressAssetsSettings =
    TrackingAddressAssetsSettings(
      settings.groupBy(_._1).map {
        case (txId, xs) =>
          txId -> ReceiversSettings(xs.map {
            case (_, receiverAddress, receivedAsset) => ReceiverItemSettings(receiverAddress.toAddress.toString, receivedAsset)
          }.toSet)
      },
      Set.empty
    )

  private def mkTxDiff(sender: KeyPair, receiver: KeyPair, asset: Asset, amount: Long): Map[Address, Portfolio] =
    mkTxDiff((sender, receiver, asset, amount))

  private def mkTxDiff(transfers: (KeyPair, KeyPair, Asset, Long)*): Map[Address, Portfolio] =
    Monoid.combineAll {
      transfers.flatMap {
        case (sender, receiver, asset, sendingAmount) =>
          asset match {
            case Waves =>
              List(
                Map(sender.toAddress   -> Portfolio.empty.copy(balance = -sendingAmount)),
                Map(receiver.toAddress -> Portfolio.empty.copy(balance = sendingAmount))
              )

            case x: IssuedAsset =>
              List(
                Map(sender.toAddress   -> Portfolio.empty.copy(assets = Map(x -> -sendingAmount))),
                Map(receiver.toAddress -> Portfolio.empty.copy(assets = Map(x -> sendingAmount)))
              )
          }
      }
    }

  private def mkTxId: ByteStr = {
    val xs = new Array[Byte](32)
    Random.nextBytes(xs)
    ByteStr(xs)
  }

  private def mkBalanceFn(owner: KeyPair, asset: Asset, amount: Long): (Address, Asset) => Long =
    mkBalanceFn((owner, asset, amount))

  private def mkBalanceFn(tracked: (KeyPair, Asset, Long)*): (Address, Asset) => Long =
    Function.untupled(mkBalance(tracked: _*))

  private def mkBalance(tracked: (KeyPair, Asset, Long)*): Map[(Address, Asset), Long] = {
    val xs = Monoid.combineAll(tracked.map {
      case (owner, asset, amount) => Map((owner.toAddress, asset) -> amount)
    })

    xs.withDefaultValue(0L)
  }

  private def mkResult(owner: KeyPair, asset: Asset, amount: Long): Map[Address, Map[Asset, Long]] =
    mkResultMany((owner, asset, amount))

  private def mkResultMany(diffs: (KeyPair, Asset, Long)*): Map[Address, Map[Asset, Long]] =
    diffs
      .groupBy(_._1)
      .map {
        case (owner, xs) =>
          owner.toAddress -> Monoid.combineAll(xs.map { case (_, asset, amount) => Map(asset -> amount) })
      }

  private def requiredCoinsGen(transfers: Map[Address, Portfolio]): Map[Address, Map[Asset, Long]] =
    transfers.map {
      case (address, portfolio) =>
        val assets = portfolio.assets.toList.map {
          case (asset, v) => (asset: Asset) -> v
        }
        val r = ((Waves -> portfolio.balance) :: assets).collect {
          case (asset, v) if v < 0 => asset -> -v
        }
        address -> r.toMap
    }

  private def distribute(total: Long, participants: List[Address]): Gen[Map[Address, Long]] = {
    val size = participants.size
    for {
      rawDistribution <- Gen.containerOfN[Vector, Long](size, Gen.chooseNum(0L, total / size))
      distribution <- {
        val s = rawDistribution.sum
        if (s == total) Gen.const(rawDistribution)
        else
          Gen.chooseNum(0, size - 1).map { i =>
            rawDistribution.updated(i, rawDistribution(i) + total - s)
          }
      }
    } yield participants.zip(distribution).toMap
  }

  private def toPortfolio(asset: Asset, xs: Map[Address, Long]): Map[Address, Portfolio] =
    asset match {
      case Waves          => xs.map { case (address, v) => address -> Portfolio.empty.copy(balance = v) }
      case x: IssuedAsset => xs.map { case (address, v) => address -> Portfolio.empty.copy(assets = Map(x -> v)) }
    }

  private def constDiffsGen(badAsset: Asset, participants: List[Address]): Gen[Map[Address, Portfolio]] =
    for {
      total     <- Gen.chooseNum(1, 1000)
      senders   <- distribute(total, participants)
      receivers <- distribute(total, participants)
    } yield {
      val spend = toPortfolio(badAsset, senders).map {
        case (address, p) => address -> p.copy(balance = -p.balance, assets = p.assets.view.mapValues(x => -x).toMap)
      }

      Monoid.combine(toPortfolio(badAsset, receivers), spend)
    }

  private def balanceGen(atLeast: Long): Gen[(Long, Long)] =
    for {
      total <- Gen.chooseNum(atLeast, atLeast + 100)
      bad   <- Gen.chooseNum(0, total)
    } yield (total, bad)

  private def balanceGen(atLeast: Map[Asset, Long]): Gen[(Map[Asset, Long], Map[Asset, Long])] =
    Gen
      .sequence {
        atLeast.toList.map {
          case (asset, v) =>
            balanceGen(v).map {
              case (total, bad) => (Map(asset -> total), Map(asset -> bad))
            }
        }
      }
      .map(xs => Monoid.combineAll(xs.asScala.toList))

  private def injectKey(address: Address, m: Map[Asset, Long]): Map[(Address, Asset), Long] = m.map {
    case (asset, v) => (address, asset) -> v
  }

  private def balancesGen(atLeast: Map[Address, Map[Asset, Long]]): Gen[(Map[(Address, Asset), Long], Map[(Address, Asset), Long])] =
    Gen
      .sequence {
        atLeast.toList.map {
          case (address, atLeastForAddress) =>
            balanceGen(atLeastForAddress).map {
              case (total, bad) => (injectKey(address, total), injectKey(address, bad))
            }
        }
      }
      .map(xs => Monoid.combineAll(xs.asScala.toList))

  private def reissueDiffsGen(badAsset: Asset, participants: List[Address]): Gen[Map[Address, Portfolio]] =
    for {
      total     <- Gen.chooseNum(1, 1000)
      reissued  <- Gen.chooseNum(1, 100)
      senders   <- distribute(total, participants)
      receivers <- distribute(total + reissued, participants)
    } yield {
      val spend = toPortfolio(badAsset, senders).map {
        case (address, p) => address -> p.copy(balance = -p.balance, assets = p.assets.view.mapValues(x => -x).toMap)
      }

      Monoid.combine(toPortfolio(badAsset, receivers), spend)
    }

  private def burnDiffsGen(badAsset: Asset, participants: List[Address]): Gen[Map[Address, Portfolio]] =
    for {
      total     <- Gen.chooseNum(1, 1000)
      burnt     <- Gen.chooseNum(1, total)
      senders   <- distribute(total, participants)
      receivers <- distribute(total - burnt, participants)
    } yield {
      val spend = toPortfolio(badAsset, senders).map {
        case (address, p) => address -> p.copy(balance = -p.balance, assets = p.assets.view.mapValues(x => -x).toMap)
      }

      Monoid.combine(toPortfolio(badAsset, receivers), spend)
    }
}
