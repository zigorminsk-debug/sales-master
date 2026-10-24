package by.salesmaster.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Small local store: watch lists survive app restarts and are available to WorkManager. */
class ProductStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun all(): List<TrackedProduct> = synchronized(STORE_LOCK) { readUnsafe() }

    fun addFromOffer(offer: ProductOffer, targetPrice: Double?): Boolean = synchronized(STORE_LOCK) {
        val current = readUnsafe()
        val isDuplicate = current.any { saved ->
            if (offer.externalId != null) {
                saved.marketplace == offer.marketplace && saved.externalId == offer.externalId
            } else {
                saved.marketplace == offer.marketplace && saved.productUrl == offer.productUrl
            }
        }
        if (isDuplicate) return@synchronized false

        val product = TrackedProduct(
            id = UUID.randomUUID().toString(),
            name = offer.title,
            query = offer.title,
            marketplace = offer.marketplace,
            productUrl = offer.productUrl,
            externalId = offer.externalId,
            targetPrice = targetPrice,
        )
        writeUnsafe(current + product)
        true
    }

    fun update(product: TrackedProduct) = synchronized(STORE_LOCK) {
        val updated = readUnsafe().map { if (it.id == product.id) product else it }
        writeUnsafe(updated)
    }

    fun remove(id: String) = synchronized(STORE_LOCK) {
        writeUnsafe(readUnsafe().filterNot { it.id == id })
    }

    private fun readUnsafe(): List<TrackedProduct> {
        val raw = preferences.getString(KEY_PRODUCTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val market = Marketplace.fromStorage(item.optString("marketplace", null))
                    val url = item.optString("productUrl", "")
                    if (url.isBlank()) continue
                    add(
                        TrackedProduct(
                            id = item.optString("id", UUID.randomUUID().toString()),
                            name = item.optString("name", "Товар"),
                            query = item.optString("query", item.optString("name", "")),
                            marketplace = market,
                            productUrl = url,
                            externalId = item.optString("externalId", "").takeIf { it.isNotBlank() },
                            targetPrice = item.optNullableDouble("targetPrice"),
                            lastPrice = item.optNullableDouble("lastPrice"),
                            lastNotifiedPrice = item.optNullableDouble("lastNotifiedPrice"),
                            lastCheckedAt = item.optNullableLong("lastCheckedAt"),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeUnsafe(products: List<TrackedProduct>) {
        val array = JSONArray()
        products.forEach { product ->
            array.put(
                JSONObject()
                    .put("id", product.id)
                    .put("name", product.name)
                    .put("query", product.query)
                    .put("marketplace", product.marketplace.name)
                    .put("productUrl", product.productUrl)
                    .put("externalId", product.externalId ?: JSONObject.NULL)
                    .put("targetPrice", product.targetPrice ?: JSONObject.NULL)
                    .put("lastPrice", product.lastPrice ?: JSONObject.NULL)
                    .put("lastNotifiedPrice", product.lastNotifiedPrice ?: JSONObject.NULL)
                    .put("lastCheckedAt", product.lastCheckedAt ?: JSONObject.NULL),
            )
        }
        preferences.edit().putString(KEY_PRODUCTS, array.toString()).apply()
    }

    private fun JSONObject.optNullableDouble(name: String): Double? =
        if (!has(name) || isNull(name)) null else optDouble(name).takeIf { it > 0.0 }

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (!has(name) || isNull(name)) null else optLong(name).takeIf { it > 0L }

    private companion object {
        const val PREFERENCES_NAME = "price_radar_store"
        const val KEY_PRODUCTS = "tracked_products"
        val STORE_LOCK = Any()
    }
}
