package com.sunny.notificationlog.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Pro entitlement, modeled as a sealed type so the UI can't render an
 * impossible combination (e.g. a price on an already-Pro account).
 */
sealed interface ProStatus {
    /** Play hasn't answered yet (or isn't reachable). Start from cached state. */
    data object Loading : ProStatus

    /** Free user; [priceText] is Play's formatted local price ("US$2.99"), or null. */
    data class Available(val priceText: String?) : ProStatus

    /** Purchase started but payment is processing (cash payment at a store, etc.). */
    data object Pending : ProStatus

    /** Entitled. Verified against Play this session (or via the cached fallback). */
    data object Pro : ProStatus
}

/**
 * Thin wrapper around Google Play Billing for ONE non-consumable product
 * ("Pro upgrade", phase 7).
 *
 * Design notes:
 * - Source of truth is always PLAY, not local storage: on every successful
 *   connect we re-query purchases and overwrite our cached flag. The flag in
 *   SharedPreferences exists only so a user who already bought Pro still gets
 *   their entitlement when Play services are unreachable (offline device,
 *   alternative stores) — graceful degradation, never a lock-out.
 * - `enableAutoServiceReconnection()` (PBL 8+) handles transient disconnects;
 *   we deliberately do NOT hand-roll startConnection retries.
 * - Pending purchases must be enabled explicitly since PBL 8 — without
 *   `enableOneTimeProducts()` the client crashes on init.
 * - Purchases must be ACKNOWLEDGED within 3 days or Play refunds them
 *   automatically. That's what [acknowledge] is for; it's fire-and-forget with
 *   logged failures because there's no meaningful in-app recovery anyway.
 */
class BillingRepository(
    context: Context,
    // Application scope: billing callbacks can outlive any screen.
    private val scope: CoroutineScope
) {
    companion object {
        const val TAG = "NotificationLog"
        const val PRODUCT_ID = "pro_upgrade"
        private const val PREFS_NAME = "billing"
        private const val KEY_PRO_CACHED = "pro_cached"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _status = MutableStateFlow<ProStatus>(
        if (prefs.getBoolean(KEY_PRO_CACHED, false)) ProStatus.Pro else ProStatus.Loading
    )
    val status: StateFlow<ProStatus> = _status.asStateFlow()

    /** Latest known ProductDetails; needed to build the purchase flow params. */
    private var productDetails: ProductDetails? = null

    private var connected = false

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        // Called on the main thread after the Play purchase sheet closes.
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> handlePurchases(purchases.orEmpty())
            BillingClient.BillingResponseCode.USER_CANCELED ->
                Log.d(TAG, "Purchase canceled by user")
            else -> Log.w(TAG, "Purchase flow ended: ${result.debugMessage}")
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(purchasesListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .enableAutoServiceReconnection()
        .build()

    /**
     * Idempotent connection kick. Safe to call on every ViewModel init; does
     * nothing once connected (auto-reconnection covers later dropouts).
     */
    fun connect() {
        if (connected) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "Billing setup failed: ${result.debugMessage}")
                    // Leave status as-is (cached Pro stays honored; free users
                    // see a generic dialog without price).
                    if (_status.value == ProStatus.Loading) {
                        _status.value = ProStatus.Available(priceText = null)
                    }
                    return
                }
                connected = true
                Log.i(TAG, "Billing connected")
                syncEntitlementFromPlay()
                fetchProductDetails()
            }

            override fun onBillingServiceDisconnected() {
                // Auto-reconnection owns recovery now; just note it.
                connected = false
                Log.d(TAG, "Billing service disconnected")
            }
        })
    }

    /** Launches the Play purchase sheet. No-op (with a log) before details load. */
    fun launchPurchase(activity: Activity): Boolean {
        if (!connected || _status.value is ProStatus.Pro) return false
        val details = productDetails ?: run {
            Log.w(TAG, "Purchase requested before product details were fetched")
            return false
        }
        val offerToken = details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
        if (offerToken == null) {
            Log.e(TAG, "No one-time purchase offer available for $PRODUCT_ID")
            return false
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "launchBillingFlow failed: ${result.debugMessage}")
        }
        return true
    }

    /** Re-checks owned purchases; call after connect (and Play calls us back live). */
    private fun syncEntitlementFromPlay() {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryPurchasesAsync failed: ${result.debugMessage}")
                return@queryPurchasesAsync
            }
            handlePurchases(purchases)
            // A refunded/revoked non-consumable disappears from this list —
            // that's the signal to drop the cached entitlement.
            val ownsPro = purchases.any { isOurPro(it) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
            if (!ownsPro && _status.value !is ProStatus.Pending) {
                setEntitled(false)
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        val ours = purchases.filter { isOurPro(it) }
        if (ours.isEmpty()) return
        for (purchase in ours) {
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> {
                    setEntitled(true)
                    acknowledge(purchase.purchaseToken)
                }
                Purchase.PurchaseState.PENDING -> {
                    Log.i(TAG, "Purchase pending payment")
                    _status.value = ProStatus.Pending
                }
                else -> Log.d(TAG, "Purchase in unspecified state")
            }
        }
    }

    private fun acknowledge(token: String) {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
        client.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "Purchase acknowledged")
            } else {
                Log.w(TAG, "Acknowledge failed: ${result.debugMessage}")
            }
        }
    }

    private fun fetchProductDetails() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        // Listener form of the query (PBL 9): result arrives on the main
        // thread with a QueryProductDetailsResult wrapper, which also carries
        // an unfetchedProductList when Play couldn't load some entries.
        client.queryProductDetailsAsync(params) { billingResult, detailsResult ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "Product query failed: ${billingResult.debugMessage}")
                if (_status.value is ProStatus.Loading) {
                    _status.value = ProStatus.Available(priceText = null)
                }
                return@queryProductDetailsAsync
            }
            productDetails = detailsResult.productDetailsList.firstOrNull()
            val price = productDetails
                ?.oneTimePurchaseOfferDetailsList
                ?.firstOrNull()
                ?.formattedPrice
            Log.i(TAG, "Pro product loaded, price=$price")
            // Only overwrite a free-user status; never clobber Pro/Pending.
            if (_status.value is ProStatus.Loading ||
                _status.value is ProStatus.Available
            ) {
                _status.value = ProStatus.Available(priceText = price)
            }
        }
    }

    private fun isOurPro(purchase: Purchase): Boolean =
        purchase.products.contains(PRODUCT_ID)

    private fun setEntitled(entitled: Boolean) {
        prefs.edit().putBoolean(KEY_PRO_CACHED, entitled).apply()
        if (entitled) {
            if (_status.value !is ProStatus.Pro) Log.i(TAG, "Pro unlocked")
            _status.value = ProStatus.Pro
        } else if (_status.value is ProStatus.Pro) {
            Log.i(TAG, "Pro entitlement no longer active — reverting to free")
            _status.value = ProStatus.Available(priceText = null)
        }
    }
}
