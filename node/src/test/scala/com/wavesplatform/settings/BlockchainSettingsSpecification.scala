package com.wavesplatform.settings

import com.typesafe.config.ConfigFactory
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._

class BlockchainSettingsSpecification extends FlatSpec with Matchers {
  "BlockchainSettings" should "read custom values" in {
    val config   = loadConfig(ConfigFactory.parseString("""waves {
        |  directory = "/waves"
        |  data-directory = "/waves/data"
        |  blockchain {
        |    type = CUSTOM
        |    custom {
        |      address-scheme-character = "C"
        |      functionality {
        |        feature-check-blocks-period = 10000
        |        blocks-for-feature-activation = 9000
        |        generation-balance-depth-from-50-to-1000-after-height = 4
        |        block-version-3-after-height = 18
        |        pre-activated-features {
        |          19 = 100
        |          20 = 200
        |        }
        |        double-features-periods-after-height = 21
        |        max-transaction-time-back-offset = 55s
        |        max-transaction-time-forward-offset = 12d
        |        lease-expiration = 1000000
        |        tracking-address-assets {
        |           transfers {
        |             6avuEsqGcV1ESYUCfQAU2ZSjko5zqDQfniGfL4UK1X6w {
        |               receivers: [
        |                 {
        |                   address: 3MpjqDseB2P9KJekaFZnvUEyWU3XZaBMXUD
        |                   asset:   2fiCMUZgBZHs7DtwjyTET8gyToNNX1X5r8zUaouqoztQ
        |                 },
        |                 {
        |                   address: 3FD1Wr64awGdkg2kTzR4fHtng9sU2wZENQc
        |                   asset:   WAVES
        |                 }
        |               ]
        |             }
        |             7M4pB9DLfBdmGAyoQL2R5hUga9woJatk5WiHNgU6jEFg {
        |               receivers: [
        |                 {
        |                   address: 3FFhLVuFxy7Aes25wNHydccn4TSPuuZvNoG
        |                   asset:   WAVES
        |                 }
        |               ]
        |             }
        |           }
        |
        |           whitelisted-txs: [
        |             3F4RB9DLfBdmGAyaQL2R5hUga9woJatk5WiHNgU6jEFe
        |           ]
        |        }
        |      }
        |      rewards {
        |        term = 100000
        |        initial = 600000000
        |        min-increment = 50000000
        |        voting-interval = 10000
        |      }
        |      genesis {
        |        timestamp = 1460678400000
        |        block-timestamp = 1460678400000
        |        signature = "BASE58BLKSGNATURE"
        |        initial-balance = 100000000000000
        |        initial-base-target = 153722867
        |        average-block-delay = 60s
        |        transactions = [
        |          {recipient = "BASE58ADDRESS1", amount = 50000000000001},
        |          {recipient = "BASE58ADDRESS2", amount = 49999999999999}
        |        ]
        |      }
        |    }
        |  }
        |}""".stripMargin))
    val settings = BlockchainSettings.fromRootConfig(config)

    settings.addressSchemeCharacter should be('C')
    settings.functionalitySettings.featureCheckBlocksPeriod should be(10000)
    settings.functionalitySettings.blocksForFeatureActivation should be(9000)
    settings.functionalitySettings.generationBalanceDepthFrom50To1000AfterHeight should be(4)
    settings.functionalitySettings.blockVersion3AfterHeight should be(18)
    settings.functionalitySettings.preActivatedFeatures should be(Map(19 -> 100, 20 -> 200))
    settings.functionalitySettings.doubleFeaturesPeriodsAfterHeight should be(21)
    settings.functionalitySettings.maxTransactionTimeBackOffset should be(55.seconds)
    settings.functionalitySettings.maxTransactionTimeForwardOffset should be(12.days)
    settings.rewardsSettings.initial should be(600000000)
    settings.rewardsSettings.minIncrement should be(50000000)
    settings.rewardsSettings.term should be(100000)
    settings.rewardsSettings.votingInterval should be(10000)
    settings.functionalitySettings.trackingAddressAssets.transfers should be(
      Map(
        ByteStr.decodeBase58("6avuEsqGcV1ESYUCfQAU2ZSjko5zqDQfniGfL4UK1X6w").get -> TrackingAddressAssetsSettings.ReceiversSettings(
          Set(
            TrackingAddressAssetsSettings.ReceiverItemSettings(
              address = "3MpjqDseB2P9KJekaFZnvUEyWU3XZaBMXUD",
              asset = IssuedAsset(ByteStr.decodeBase58("2fiCMUZgBZHs7DtwjyTET8gyToNNX1X5r8zUaouqoztQ").get)
            ),
            TrackingAddressAssetsSettings.ReceiverItemSettings(
              address = "3FD1Wr64awGdkg2kTzR4fHtng9sU2wZENQc",
              asset = Waves
            )
          )
        ),
        ByteStr.decodeBase58("7M4pB9DLfBdmGAyoQL2R5hUga9woJatk5WiHNgU6jEFg").get -> TrackingAddressAssetsSettings.ReceiversSettings(
          Set(
            TrackingAddressAssetsSettings.ReceiverItemSettings(
              address = "3FFhLVuFxy7Aes25wNHydccn4TSPuuZvNoG",
              asset = Waves
            )
          )
        )
      )
    )
    settings.functionalitySettings.trackingAddressAssets.whitelistedTxs should be(
      Set(ByteStr.decodeBase58("3F4RB9DLfBdmGAyaQL2R5hUga9woJatk5WiHNgU6jEFe").get)
    )
    settings.genesisSettings.blockTimestamp should be(1460678400000L)
    settings.genesisSettings.timestamp should be(1460678400000L)
    settings.genesisSettings.signature should be(ByteStr.decodeBase58("BASE58BLKSGNATURE").toOption)
    settings.genesisSettings.initialBalance should be(100000000000000L)
    settings.genesisSettings.initialBaseTarget should be(153722867)
    settings.genesisSettings.averageBlockDelay should be(60.seconds)
    settings.genesisSettings.transactions should be(
      Seq(GenesisTransactionSettings("BASE58ADDRESS1", 50000000000001L), GenesisTransactionSettings("BASE58ADDRESS2", 49999999999999L))
    )
  }

  it should "read testnet settings" in {
    val config   = loadConfig(ConfigFactory.parseString("""waves {
        |  directory = "/waves"
        |  data-directory = "/waves/data"
        |  blockchain {
        |    type = TESTNET
        |  }
        |}""".stripMargin))
    val settings = BlockchainSettings.fromRootConfig(config)

    settings.addressSchemeCharacter should be('T')
    settings.functionalitySettings.generationBalanceDepthFrom50To1000AfterHeight should be(0)
    settings.functionalitySettings.blockVersion3AfterHeight should be(161700)
    settings.functionalitySettings.maxTransactionTimeBackOffset should be(120.minutes)
    settings.functionalitySettings.maxTransactionTimeForwardOffset should be(90.minutes)
    settings.rewardsSettings.initial should be(600000000)
    settings.rewardsSettings.minIncrement should be(50000000)
    settings.rewardsSettings.term should be(100000)
    settings.rewardsSettings.votingInterval should be(10000)
    settings.genesisSettings.blockTimestamp should be(1460678400000L)
    settings.genesisSettings.timestamp should be(1478000000000L)
    settings.genesisSettings.signature should be(
      ByteStr.decodeBase58("5uqnLK3Z9eiot6FyYBfwUnbyid3abicQbAZjz38GQ1Q8XigQMxTK4C1zNkqS1SVw7FqSidbZKxWAKLVoEsp4nNqa").toOption
    )
    settings.genesisSettings.initialBalance should be(10000000000000000L)

    settings.genesisSettings.transactions should be(
      Seq(
        GenesisTransactionSettings("3My3KZgFQ3CrVHgz6vGRt8687sH4oAA1qp8", 400000000000000L),
        GenesisTransactionSettings("3NBVqYXrapgJP9atQccdBPAgJPwHDKkh6A8", 200000000000000L),
        GenesisTransactionSettings("3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh", 200000000000000L),
        GenesisTransactionSettings("3NCBMxgdghg4tUhEEffSXy11L6hUi6fcBpd", 200000000000000L),
        GenesisTransactionSettings("3N18z4B8kyyQ96PhN5eyhCAbg4j49CgwZJx", 9000000000000000L)
      )
    )
  }

  it should "read mainnet settings" in {
    val config   = loadConfig(ConfigFactory.parseString("""waves {
        |  directory = "/waves"
        |  data-directory = "/waves/data"
        |  blockchain {
        |    type = MAINNET
        |  }
        |}""".stripMargin))
    val settings = BlockchainSettings.fromRootConfig(config)

    settings.addressSchemeCharacter should be('W')
    settings.functionalitySettings.generationBalanceDepthFrom50To1000AfterHeight should be(232000L)
    settings.functionalitySettings.maxTransactionTimeBackOffset should be(120.minutes)
    settings.functionalitySettings.maxTransactionTimeForwardOffset should be(90.minutes)
    settings.rewardsSettings.initial should be(600000000)
    settings.rewardsSettings.minIncrement should be(50000000)
    settings.rewardsSettings.term should be(100000)
    settings.rewardsSettings.votingInterval should be(10000)
    settings.genesisSettings.blockTimestamp should be(1460678400000L)
    settings.genesisSettings.timestamp should be(1465742577614L)
    settings.genesisSettings.signature should be(
      ByteStr.decodeBase58("FSH8eAAzZNqnG8xgTZtz5xuLqXySsXgAjmFEC25hXMbEufiGjqWPnGCZFt6gLiVLJny16ipxRNAkkzjjhqTjBE2").toOption
    )
    settings.genesisSettings.initialBalance should be(10000000000000000L)
    settings.genesisSettings.transactions should be(
      Seq(
        GenesisTransactionSettings("3PAWwWa6GbwcJaFzwqXQN5KQm7H96Y7SHTQ", 9999999500000000L),
        GenesisTransactionSettings("3P8JdJGYc7vaLu4UXUZc1iRLdzrkGtdCyJM", 100000000L),
        GenesisTransactionSettings("3PAGPDPqnGkyhcihyjMHe9v36Y4hkAh9yDy", 100000000L),
        GenesisTransactionSettings("3P9o3ZYwtHkaU1KxsKkFjJqJKS3dLHLC9oF", 100000000L),
        GenesisTransactionSettings("3PJaDyprvekvPXPuAtxrapacuDJopgJRaU3", 100000000L),
        GenesisTransactionSettings("3PBWXDFUc86N2EQxKJmW8eFco65xTyMZx6J", 100000000L)
      )
    )
  }
}
