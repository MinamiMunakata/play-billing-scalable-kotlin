package com.example.playbilling.trivialdrive.kotlin.billingrepo.localdb

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.android.billingclient.api.Purchase

@Dao
interface PurchaseDao {
    @Query("SELECT * FROM purchase_table")
    fun getPurchases(): List<CachedPurchase>

    @Insert
    fun insert(purchase: CachedPurchase)

    @Transaction
    fun insert(vararg purchases: Purchase) {
        purchases.forEach {
            insert(CachedPurchase(data = it))
        }
    }

    @Delete
    fun delete(vararg purchases: CachedPurchase)

    @Query("DELETE FROM purchase_table")
    fun deleteAll()
}
