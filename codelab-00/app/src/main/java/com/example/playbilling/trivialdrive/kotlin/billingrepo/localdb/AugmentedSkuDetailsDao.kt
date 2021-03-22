package com.example.playbilling.trivialdrive.kotlin.billingrepo.localdb

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.SkuDetails

@Dao
interface AugmentedSkuDetailsDao {

    @Query("SELECT * FROM AugmentedSkuDetails WHERE type = '${BillingClient.SkuType.SUBS}'")
    fun getSubscriptionSkuDetails(): LiveData<List<AugmentedSkuDetails>>

    @Query("SELECT * FROM AugmentedSkuDetails WHERE type = '${BillingClient.SkuType.INAPP}'")
    fun getInappSkuDetails(): LiveData<List<AugmentedSkuDetails>>

    @Transaction
    fun insertOrUpdate(skuDetails: SkuDetails) = skuDetails.apply {
        val result = getById(sku)
        val canPurchase = if (result == null) true else result.canPurchase
        val originalJson = toString().substring("SkuDetails: ".length)
        val skuDetails = AugmentedSkuDetails(canPurchase, sku, type, price, title, description, originalJson)
        insert(skuDetails)
    }

    @Transaction
    fun insertOrUpdate(sku: String, canPurchase: Boolean) {
        val result = getById(sku)
        if (result != null) {
            update(sku, canPurchase)
        } else {
            insert(AugmentedSkuDetails(canPurchase, sku, null, null, null, null, null))
        }
    }

    @Query("SELECT * FROM AugmentedSkuDetails WHERE sku = :sku")
    fun getById(sku: String): AugmentedSkuDetails

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(augmentedSkuDetails: AugmentedSkuDetails)

    @Query("UPDATE AugmentedSkuDetails SET canPurchase = :canPurchase WHERE sku = :sku")
    fun update(sku: String, canPurchase: Boolean)
}
