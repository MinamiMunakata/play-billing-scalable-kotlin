package com.example.playbilling.trivialdrive.kotlin.billingrepo.localdb

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CachedPurchase::class, GasTank::class, PremiumCar::class, GoldStatus::class, AugmentedSkuDetails::class],
        version = 1, exportSchema = false)
abstract class LocalBillingDb : RoomDatabase() {
    abstract fun purchaseDao(): PurchaseDao
    abstract fun entitlementsDao(): EntitlementsDao
    abstract fun skuDetailsDao(): AugmentedSkuDetailsDao

    companion object {
        @Volatile
        private var INSTANCE: LocalBillingDb? = null

        fun getInstance(context: Context): LocalBillingDb = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocalBillingDb::class.java,
                    "purchase_db")
                    .fallbackToDestructiveMigration()//remote sources more reliable
                    .build().also { INSTANCE = it }
        }
    }
}
