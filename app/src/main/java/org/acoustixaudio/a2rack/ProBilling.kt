package org.acoustixaudio.a2rack

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class ProState(
    val owned: Boolean = false,
    val pending: Boolean = false,
    val price: String? = null,
    val message: String? = null,
    val purchasing: Boolean = false
)

internal class ProBilling(context: Context) : PurchasesUpdatedListener {
    private val preferences = context.getSharedPreferences("play_pro", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(ProState(owned = preferences.getBoolean("owned", false)))
    val state = mutableState.asStateFlow()
    private var closed = false
    private var connecting = false
    private var connectedOnce = false
    private var offer: BillingFlowParams.ProductDetailsParams? = null
    private val acknowledgments = mutableSetOf<String>()
    private var purchaseRevision = 0
    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private fun dispatch(block: () -> Unit) {
        main.post { if (!closed) block() }
    }

    fun refresh() {
        if (connecting || closed) return
        if (connectedOnce) {
            query()
            return
        }
        connecting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingServiceDisconnected() {}
            override fun onBillingSetupFinished(result: BillingResult) = dispatch {
                connecting = false
                connectedOnce = result.responseCode == BillingClient.BillingResponseCode.OK
                if (connectedOnce) query()
                else mutableState.update { it.copy(message = "Google Play purchases are unavailable.") }
            }
        })
    }

    private fun query() {
        val revision = ++purchaseRevision
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            dispatch {
                if (revision == purchaseRevision && result.responseCode == BillingClient.BillingResponseCode.OK) {
                    applyPurchases(purchases)
                }
            }
        }
        val product = QueryProductDetailsParams.Product.newBuilder().setProductId("pro")
            .setProductType(BillingClient.ProductType.INAPP).build()
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product)).build()) { result, details ->
            dispatch {
                val available = result.responseCode == BillingClient.BillingResponseCode.OK
                val item = details.productDetailsList.firstOrNull { it.productId == "pro" }.takeIf { available }
                val buy = item?.oneTimePurchaseOfferDetailsList?.firstOrNull { it.rentalDetails == null }
                offer = if (item != null && buy != null) BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(item).apply { buy.offerToken?.let { setOfferToken(it) } }.build() else null
                mutableState.update {
                    it.copy(price = buy?.formattedPrice,
                        message = if (buy == null) "Pro is unavailable from Google Play right now." else null)
                }
            }
        }
    }

    fun buy(activity: Activity) {
        val item = offer ?: return
        val current = state.value
        if (current.owned || current.pending || current.purchasing) return
        mutableState.update { it.copy(purchasing = true, message = null) }
        val result = client.launchBillingFlow(activity, BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(item)).build())
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            mutableState.update { it.copy(purchasing = false, message = "Could not open Google Play checkout.") }
            refresh()
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) = dispatch {
        mutableState.update { it.copy(purchasing = false) }
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> if (!purchases.isNullOrEmpty()) {
                purchaseRevision++
                applyPurchases(purchases, authoritative = false)
            } else refresh()
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> mutableState.update { it.copy(message = "Purchase could not be completed. Please try again.") }
        }
    }

    private fun applyPurchases(purchases: List<Purchase>, authoritative: Boolean = true) {
        val pro = purchases.filter { "pro" in it.products }
        val purchased = pro.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (authoritative || purchased.isNotEmpty()) {
            preferences.edit().putBoolean("owned", purchased.isNotEmpty()).apply()
        }
        mutableState.update {
            it.copy(owned = if (authoritative) purchased.isNotEmpty() else it.owned || purchased.isNotEmpty(),
                pending = pro.any { p -> p.purchaseState == Purchase.PurchaseState.PENDING })
        }
        purchased.filterNot { it.isAcknowledged }.forEach { purchase ->
            val token = purchase.purchaseToken
            if (acknowledgments.add(token)) {
                client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) {
                    dispatch { acknowledgments.remove(token) }
                }
            }
        }
    }

    fun close() {
        closed = true
        client.endConnection()
    }
}
