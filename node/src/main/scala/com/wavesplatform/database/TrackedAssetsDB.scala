package com.wavesplatform.database

import com.wavesplatform.account.Address
import com.wavesplatform.transaction.Asset
import org.iq80.leveldb.DB

trait TrackedAssetsDB {
  def allTrackedAssetsByAssetId(asset: Asset): Map[Address, Long]
}

object TrackedAssetsDB {
  def apply(db: DB): TrackedAssetsDB = new TrackedAssetsDB {
    override def allTrackedAssetsByAssetId(asset: Asset): Map[Address, Long] = db.readOnly { ro =>
      val mkResult = Map.newBuilder[Address, Long]
      ro.iterateOver(KeyTags.TrackedAssets) { entry =>
        val addressId = AddressId.fromByteArray(entry.getKey.drop(2))

        val trackedAssetsKey = Keys.trackedAssets(addressId)
        val assets           = trackedAssetsKey.parse(entry.getValue)

        if (assets.contains(asset)) {
          val trackedAssetsHistoryKey = Keys.trackedAssetsHistory(addressId, asset)
          ro.get(trackedAssetsHistoryKey).headOption.foreach { height =>
            val volumeKey = Keys.badAssets(addressId, asset)(height)
            val volume    = ro.get(volumeKey)
            if (volume != 0) {
              val address = ro.get(Keys.idToAddress(addressId))
              mkResult += address -> volume
            }
          }
        }
      }

      mkResult.result()
    }
  }

  val empty: TrackedAssetsDB = new TrackedAssetsDB {
    override def allTrackedAssetsByAssetId(asset: Asset): Map[Address, Long] = Map.empty
  }
}
