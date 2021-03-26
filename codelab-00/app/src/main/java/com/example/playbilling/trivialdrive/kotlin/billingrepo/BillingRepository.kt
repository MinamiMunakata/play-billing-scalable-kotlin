package com.example.playbilling.trivialdrive.kotlin.billingrepo

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.SkuDetails
import com.android.billingclient.api.SkuDetailsParams
import com.android.billingclient.api.SkuDetailsResponseListener
import com.example.playbilling.trivialdrive.kotlin.billingrepo.BillingRepository.GameSku.CONSUMABLE_SKUS
import com.example.playbilling.trivialdrive.kotlin.billingrepo.BillingRepository.GameSku.GOLD_STATUS_SKUS
import com.example.playbilling.trivialdrive.kotlin.billingrepo.BillingRepository.GameSku.INAPP_SKUS
import com.example.playbilling.trivialdrive.kotlin.billingrepo.BillingRepository.GameSku.SUBS_SKUS
import com.example.playbilling.trivialdrive.kotlin.billingrepo.BillingRepository.RetryPolicies.connectionRetryPolicy
import com.example.playbilling.trivialdrive.kotlin.billingrepo.BillingRepository.RetryPolicies.resetConnectionRetryPolicyCounter
import com.example.playbilling.trivialdrive.kotlin.billingrepo.BillingRepository.RetryPolicies.taskExecutionRetryPolicy
import com.example.playbilling.trivialdrive.kotlin.billingrepo.BillingRepository.Throttle.isLastInvocationTimeStale
import com.example.playbilling.trivialdrive.kotlin.billingrepo.BillingRepository.Throttle.refreshLastInvocationTime
import com.example.playbilling.trivialdrive.kotlin.billingrepo.localdb.AugmentedSkuDetails
import com.example.playbilling.trivialdrive.kotlin.billingrepo.localdb.Entitlement
import com.example.playbilling.trivialdrive.kotlin.billingrepo.localdb.GAS_PURCHASE
import com.example.playbilling.trivialdrive.kotlin.billingrepo.localdb.GasTank
import com.example.playbilling.trivialdrive.kotlin.billingrepo.localdb.GoldStatus
import com.example.playbilling.trivialdrive.kotlin.billingrepo.localdb.LocalBillingDb
import com.example.playbilling.trivialdrive.kotlin.billingrepo.localdb.PremiumCar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow

class BillingRepository private constructor(private val application: Application) :
        PurchasesUpdatedListener, BillingClientStateListener,
        ConsumeResponseListener, SkuDetailsResponseListener {

    companion object {
        private const val LOG_TAG = "BillingRepository"

        @Volatile
        private var INSTANCE: BillingRepository? = null

        fun getInstance(application: Application): BillingRepository =
                INSTANCE ?: synchronized(this) {
                    INSTANCE
                            ?: BillingRepository(application)
                                    .also { INSTANCE = it }
                }
    }

    lateinit private var playStoreBillingClient: BillingClient

    lateinit private var secureServerBillingClient: BillingWebservice

    lateinit private var localCacheBillingClient: LocalBillingDb

    val subsSkuDetailsListLiveData: LiveData<List<AugmentedSkuDetails>> by lazy {
        if (::localCacheBillingClient.isInitialized == false) {
            localCacheBillingClient = LocalBillingDb.getInstance(application)
        }
        localCacheBillingClient.skuDetailsDao().getSubscriptionSkuDetails()
    }

    val inappSkuDetailsListLiveData: LiveData<List<AugmentedSkuDetails>> by lazy {
        if (::localCacheBillingClient.isInitialized == false) {
            localCacheBillingClient = LocalBillingDb.getInstance(application)
        }
        localCacheBillingClient.skuDetailsDao().getInappSkuDetails()
    }

    val gasTankLiveData: LiveData<GasTank> by lazy {
        if (::localCacheBillingClient.isInitialized == false) {
            localCacheBillingClient = LocalBillingDb.getInstance(application)
        }
        localCacheBillingClient.entitlementsDao().getGasTank()
    }

    val premiumCarLiveData: LiveData<PremiumCar> by lazy {
        if (::localCacheBillingClient.isInitialized == false) {
            localCacheBillingClient = LocalBillingDb.getInstance(application)
        }
        localCacheBillingClient.entitlementsDao().getPremiumCar()
    }

    val goldStatusLiveData: LiveData<GoldStatus> by lazy {
        if (::localCacheBillingClient.isInitialized == false) {
            localCacheBillingClient = LocalBillingDb.getInstance(application)
        }
        localCacheBillingClient.entitlementsDao().getGoldStatus()
    }

    fun startDataSourceConnections() {
        Log.d(LOG_TAG, "startDataSourceConnections")
        instantiateAndConnectToPlayBillingService()
        secureServerBillingClient = BillingWebservice.create()
        localCacheBillingClient = LocalBillingDb.getInstance(application)
    }

    fun endDataSourceConnections() {
        playStoreBillingClient.endConnection()
        // normally you don't worry about closing a DB connection unless you have
        //more than one open. so no need to call 'localCacheBillingClient.close()'
        Log.d(LOG_TAG, "startDataSourceConnections")
    }

    private fun instantiateAndConnectToPlayBillingService() {
        playStoreBillingClient = BillingClient.newBuilder(application.applicationContext)
                .setListener(this).build()
        connectToPlayBillingService()
    }

    private fun connectToPlayBillingService(): Boolean {
        Log.d(LOG_TAG, "connectToPlayBillingService")
        if (!playStoreBillingClient.isReady) {
            playStoreBillingClient.startConnection(this)
            return true
        }
        return false
    }

    override fun onPurchasesUpdated(responseCode: Int, purchases: MutableList<Purchase>?) {
        when (responseCode) {
            BillingClient.BillingResponse.OK -> {
                // will handle server verification, consumables, and updating the local cache
                purchases?.apply { processPurchases(toSet()) }
            }
            BillingClient.BillingResponse.ITEM_ALREADY_OWNED -> {
                //item already owned? call queryPurchasesAsync to verify and process all such items
                Log.d(LOG_TAG, "already owned items")
                queryPurchasesAsync()
            }
            BillingClient.BillingResponse.DEVELOPER_ERROR -> {
                Log.e(LOG_TAG, "Your app's configuration is incorrect. Review in the Google Play" +
                        "Console. Possible causes of this error include: APK is not signed with " +
                        "release key; SKU productId mismatch.")
            }
            else -> {
                Log.i(LOG_TAG, "BillingClient.BillingResponse error code: $responseCode")
            }
        }
    }

    override fun onBillingSetupFinished(responseCode: Int) {
        when (responseCode) {
            BillingClient.BillingResponse.OK -> {
                Log.d(LOG_TAG, "onBillingSetupFinished successfully")
                resetConnectionRetryPolicyCounter()//for retry policy
                querySkuDetailsAsync(BillingClient.SkuType.INAPP, GameSku.INAPP_SKUS)
                querySkuDetailsAsync(BillingClient.SkuType.SUBS, GameSku.SUBS_SKUS)
                queryPurchasesAsync()
            }
            BillingClient.BillingResponse.BILLING_UNAVAILABLE -> {
                //Some apps may choose to make decisions based on this knowledge.
                Log.d(LOG_TAG, "onBillingSetupFinished but billing is not available on this device")
            }
            else -> {
                //do nothing. Someone else will connect it through retry policy.
                //May choose to send to server though
                Log.d(LOG_TAG, "onBillingSetupFinished with failure response code: $responseCode")
            }
        }
    }

    override fun onBillingServiceDisconnected() {
        Log.d(LOG_TAG, "onBillingServiceDisconnected")
        connectionRetryPolicy { connectToPlayBillingService() }
    }

    /**
     * This is the callback for [BillingClient.consumeAsync]. It's called by [playStoreBillingClient] to notify
     *  that a consume operation has finished.
     * Appropriate action should be taken in the app, such as add fuel to user's
     * car. This information should also be saved on the secure server in case user
     * accesses the app through another device.
     */
    override fun onConsumeResponse(responseCode: Int, purchaseToken: String?) {
        Log.d(LOG_TAG, "onConsumeResponse")
        when (responseCode) {
            BillingClient.BillingResponse.OK -> {
                //give user the items s/he just bought by updating the appropriate tables/databases
                purchaseToken?.apply { saveToLocalDatabase(this) }
                secureServerBillingClient.onComsumeResponse(purchaseToken, responseCode)
            }
            else -> {
                Log.w(LOG_TAG, "Error consuming purchase with token ($purchaseToken). " +
                        "Response code: $responseCode")
            }
        }
    }

    override fun onSkuDetailsResponse(responseCode: Int, skuDetailsList: MutableList<SkuDetails>?) {
        if (responseCode != BillingClient.BillingResponse.OK) {
            Log.w(LOG_TAG, "SkuDetails query failed with response: $responseCode")
        } else {
            Log.d(LOG_TAG, "SkuDetails query responded with success. List: $skuDetailsList")
        }

        if (skuDetailsList.orEmpty().isNotEmpty()) {
            val scope = CoroutineScope(Job() + Dispatchers.IO)
            scope.launch {
                skuDetailsList?.forEach { localCacheBillingClient.skuDetailsDao().insertOrUpdate(it) }
            }
        }
    }

    private fun querySkuDetailsAsync(@BillingClient.SkuType skuType: String, skuList: List<String>) {
        val params = SkuDetailsParams.newBuilder()
        params.setSkusList(skuList).setType(skuType)
        taskExecutionRetryPolicy(playStoreBillingClient, this) {
            Log.d(LOG_TAG, "querySkuDetailsAsync for $skuType")
            playStoreBillingClient.querySkuDetailsAsync(params.build(), this)
        }
    }

    fun queryPurchasesAsync() {
        fun task() {
            Log.d(LOG_TAG, "queryPurchasesAsync called")
            val purchasesResult = HashSet<Purchase>()
            var result = playStoreBillingClient.queryPurchases(BillingClient.SkuType.INAPP)
            Log.d(LOG_TAG, "queryPurchasesAsync INAPP results: ${result?.purchasesList}")
            result?.purchasesList?.apply { purchasesResult.addAll(this) }
            if (isSubscriptionSupported()) {
                result = playStoreBillingClient.queryPurchases(BillingClient.SkuType.SUBS)
                result?.purchasesList?.apply { purchasesResult.addAll(this) }
                Log.d(LOG_TAG, "queryPurchasesAsync SUBS results: ${result?.purchasesList}")
            }
            processPurchases(purchasesResult)
        }
        taskExecutionRetryPolicy(playStoreBillingClient, this) { task() }
    }

    private fun processPurchases(purchasesResult: Set<Purchase>) = CoroutineScope(Job() + Dispatchers.IO).launch {
        val cachedPurchases = localCacheBillingClient.purchaseDao().getPurchases()
        val newBatch = HashSet<Purchase>(purchasesResult.size)
        purchasesResult.forEach { purchase ->
            if (isSignatureValid(purchase) && !cachedPurchases.any { it.data == purchase }) {//todo !cachedPurchases.contains(purchase)
                newBatch.add(purchase)
            }
        }

        if (newBatch.isNotEmpty()) {
            sendPurchasesToServer(newBatch)
            // We still care about purchasesResult in case a old purchase has not
            // yet been consumed.
            saveToLocalDatabase(newBatch, purchasesResult)
            //consumeAsync(purchasesResult): do this inside saveToLocalDatabase to
            // avoid race condition
        } else if (isLastInvocationTimeStale(application)) {
            handleConsumablePurchasesAsync(purchasesResult)
            queryPurchasesFromSecureServer()
        }
    }

    private fun isSubscriptionSupported(): Boolean {
        val responseCode = playStoreBillingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
        if (responseCode != BillingClient.BillingResponse.OK) {
            Log.w(LOG_TAG, "isSubscriptionSupported() got an error response: $responseCode")
        }
        return responseCode == BillingClient.BillingResponse.OK
    }

    private fun isSignatureValid(purchase: Purchase): Boolean {
        return Security.verifyPurchase(Security.BASE_64_ENCODED_PUBLIC_KEY, purchase.originalJson, purchase.signature)
    }

    private fun sendPurchasesToServer(purchases: Set<Purchase>) {
        //not implemented here
    }

    private fun queryPurchasesFromSecureServer() {
        fun getPurchasesFromSecureServerToLocalDB() {//closure
            //do the actual work of getting the purchases from server
        }
        getPurchasesFromSecureServerToLocalDB()

        refreshLastInvocationTime(application)
    }

    private fun saveToLocalDatabase(newBatch: Set<Purchase>, allPurchases: Set<Purchase>) {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        scope.launch {
            newBatch.forEach { purchase ->
                when (purchase.sku) {
                    GameSku.PREMIUM_CAR -> {
                        val premiumCar = PremiumCar(true)
                        insert(premiumCar)
                        localCacheBillingClient.skuDetailsDao().insertOrUpdate(purchase.sku, premiumCar.mayPurchase())
                    }
                    GameSku.GOLD_MONTHLY, GameSku.GOLD_YEARLY -> {
                        val goldStatus = GoldStatus(true)
                        insert(goldStatus)
                        localCacheBillingClient.skuDetailsDao().insertOrUpdate(purchase.sku, goldStatus.mayPurchase())
                        GOLD_STATUS_SKUS.forEach { otherSku ->
                            if (otherSku != purchase.sku) {
                                localCacheBillingClient.skuDetailsDao().insertOrUpdate(otherSku, !goldStatus.mayPurchase())
                            }
                        }
                    }
                }
            }
            localCacheBillingClient.purchaseDao().insert(*newBatch.toTypedArray())
            handleConsumablePurchasesAsync(allPurchases)
        }
    }

    private fun saveToLocalDatabase(purchaseToken: String) {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        scope.launch {
            val cachedPurchases = localCacheBillingClient.purchaseDao().getPurchases()
            val match = cachedPurchases.find { it.purchaseToken == purchaseToken }
            if (match?.sku == GameSku.GAS) {
                updateGasTank(GasTank(GAS_PURCHASE))
                localCacheBillingClient.purchaseDao().delete(match)
            }
        }
    }

    private fun handleConsumablePurchasesAsync(purchases: Set<Purchase>) {
        purchases.forEach {
            if (GameSku.CONSUMABLE_SKUS.contains(it.sku)) {
                playStoreBillingClient.consumeAsync(it.purchaseToken, this@BillingRepository)
                //tell your server:
                Log.i(LOG_TAG, "handleConsumablePurchasesAsync: asked Play Billing to consume sku = ${it.sku}")
            }
        }
    }

    @WorkerThread
    suspend fun updateGasTank(gas: GasTank) = withContext(Dispatchers.IO) {
        Log.d(LOG_TAG, "updateGasTank")
        var update: GasTank = gas
        gasTankLiveData.value?.apply {
            synchronized(this) {
                if (this != gas) {//new purchase
                    update = GasTank(getLevel() + gas.getLevel())
                }
                Log.d(LOG_TAG, "New purchase level is ${gas.getLevel()}; existing level is ${getLevel()}; so the final result is ${update.getLevel()}")
                localCacheBillingClient.entitlementsDao().update(update)
            }
        }
        if (gasTankLiveData.value == null) {
            localCacheBillingClient.entitlementsDao().insert(update)
            Log.d(LOG_TAG, "No we just added from null gas with level: ${gas.getLevel()}")
        }
        localCacheBillingClient.skuDetailsDao().insertOrUpdate(GameSku.GAS, update.mayPurchase())
        Log.d(LOG_TAG, "updated AugmentedSkuDetails as well")
    }

    @WorkerThread
    suspend private fun insert(entitlement: Entitlement) = withContext(Dispatchers.IO) {
        localCacheBillingClient.entitlementsDao().insert(entitlement)
    }

    fun launchBillingFlow(activity: Activity, augmentedSkuDetails: AugmentedSkuDetails) =
            launchBillingFlow(activity, SkuDetails(augmentedSkuDetails.originalJson))

    fun launchBillingFlow(activity: Activity, skuDetails: SkuDetails) {
        val oldSku: String? = getOldSku(skuDetails.sku)
        val purchaseParams = BillingFlowParams.newBuilder().setSkuDetails(skuDetails)
                .setOldSku(oldSku).build()

        taskExecutionRetryPolicy(playStoreBillingClient, this) {
            playStoreBillingClient.launchBillingFlow(activity, purchaseParams)
        }
    }

    /**
     * This sample app only offers one item for subscription: GoldStatus. And there are two
     * ways a user can subscribe to GoldStatus: monthly or yearly. Hence it's easy for
     * the [BillingRepository] to get the old sku if one exists. You too should have no problem
     * knowing this fact about your app.
     *
     * We must here again reiterate. We don't want to make this sample app overwhelming. And so we
     * are introducing ideas piecewise. This sample focuses more on overall architecture.
     * So although we mention subscriptions, it is not about subscription per se. Classy Taxi is
     * the sample app that focuses on subscriptions.
     *
     */
    private fun getOldSku(sku: String?): String? {
        var result: String? = null
        if (GameSku.SUBS_SKUS.contains(sku)) {
            goldStatusLiveData.value?.apply {
                result = when (sku) {
                    GameSku.GOLD_MONTHLY -> GameSku.GOLD_YEARLY
                    else -> GameSku.GOLD_YEARLY
                }
            }
        }
        return result
    }

    /**
     * This private object class shows an example retry policies. You may choose to replace it with
     * your own policies.
     */

    private object RetryPolicies {
        private val maxRetry = 5
        private var retryCounter = AtomicInteger(1)
        private val baseDelayMillis = 500
        private val taskDelay = 2000L

        fun resetConnectionRetryPolicyCounter() {
            retryCounter.set(1)
        }

        /**
         * This works because it actually only makes one call. Then it waits for success or failure.
         * onSuccess it makes no more calls and resets the retryCounter to 1. onFailure another
         * call is made, until too many failures cause retryCounter to reach maxRetry and the
         * policy stops trying. This is a safe algorithm: the initial calls to
         * connectToPlayBillingService from instantiateAndConnectToPlayBillingService is always
         * independent of the RetryPolicies. And so the Retry Policy exists only to help and never
         * to hurt.
         */

        fun connectionRetryPolicy(block: () -> Unit) {
            Log.d(LOG_TAG, "connectionRetryPolicy")
            val scope = CoroutineScope(Job() + Dispatchers.Main)
            scope.launch {
                val counter = retryCounter.getAndIncrement()
                if (counter < maxRetry) {
                    val waitTime: Long = (2f.pow(counter) * baseDelayMillis).toLong()
                    delay(waitTime)
                    block()
                }
            }
        }

        /**
         * All this is doing is check that billingClient is connected and if it's
         * not, request connection, wait x number of seconds and then proceed with
         * the actual task.
         */
        fun taskExecutionRetryPolicy(billingClient: BillingClient, listener: BillingRepository, task: () -> Unit) {
            val scope = CoroutineScope(Job() + Dispatchers.Main)
            scope.launch {
                if (!billingClient.isReady) {
                    Log.d(LOG_TAG, "taskExecutionRetryPolicy billing not ready")
                    billingClient.startConnection(listener)
                    delay(taskDelay)
                }
                task()
            }
        }
    }

    /**
     * This is the throttling valve. It is used to modulate how often calls are
     * made to the secure server in order to save money.
     */
    private object Throttle {
        private val DEAD_BAND = 7200000//2*60*60*1000: two hours wait
        private val PREFS_NAME = "BillingRepository.Throttle"
        private val KEY = "lastInvocationTime"

        fun isLastInvocationTimeStale(context: Context): Boolean {
            val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastInvocationTime = sharedPrefs.getLong(KEY, 0)
            return lastInvocationTime + DEAD_BAND < Date().time
        }

        fun refreshLastInvocationTime(context: Context) {
            val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            with(sharedPrefs.edit()) {
                putLong(KEY, Date().time)
                apply()
            }
        }
    }

    /**
     * [INAPP_SKUS], [SUBS_SKUS], [CONSUMABLE_SKUS]:
     *
     * Where you define these lists is quite truly up to you. If you don't need
     * customization, then it makes since to define and hardcode them here, as
     * I am doing. Keep simple things simple. But there are use cases where you may
     * need customization:
     *
     * - If you don't want to update your APK (or Bundle) each time you change your
     *   SKUs, then you may want to load these lists from your secure server.
     *
     * - If your design is such that users can buy different items from different
     *   Activities or Fragments, then you may want to define a list for each of
     *   those subsets. I only have two subsets: INAPP_SKUS and SUBS_SKUS
     */

    private object GameSku {
        val GAS = "gas"
        val PREMIUM_CAR = "premium_car"
        val GOLD_MONTHLY = "gold_monthly"
        val GOLD_YEARLY = "gold_yearly"

        val INAPP_SKUS = listOf(GAS, PREMIUM_CAR)
        val SUBS_SKUS = listOf(GOLD_MONTHLY, GOLD_YEARLY)
        val CONSUMABLE_SKUS = listOf(GAS)

        //coincidence that there only gold_status is a sub
        val GOLD_STATUS_SKUS = SUBS_SKUS
    }
}
