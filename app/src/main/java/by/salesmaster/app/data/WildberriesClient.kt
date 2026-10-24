package by.salesmaster.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class WildberriesClient {
    fun search(query: String): List<ProductOffer> {
        val productId = idFromUrl(query)
        if (productId != null) {
            return try {
                listOf(fetchById(productId) ?: throw IOException("карточка не найдена"))
            } catch (exception: Exception) {
                throw IOException("Wildberries не удалось загрузить эту карточку. Откройте ссылку на площадке.", exception)
            }
        }

        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        var lastError: Exception? = null
        for (version in listOf(18, 17, 16)) {
            try {
                val endpoint = "https://search.wb.ru/exactmatch/ru/common/v$version/search" +
                    "?ab_testing=false&appType=1&curr=rub&dest=-1257786" +
                    "&query=$encodedQuery&resultset=catalog&sort=priceup&spp=30&suppressSpellcheck=false"
                val response = JSONObject(HttpJson.get(endpoint, "https://www.wildberries.ru/"))
                val products = response.optJSONObject("data")?.optJSONArray("products")
                    ?: throw IOException("ответ не содержит списка товаров")
                val offers = parseProducts(products).sortedBy { it.price }
                if (offers.isNotEmpty()) return offers.take(30)
                lastError = IOException("по запросу не найдено товаров с распознанной ценой")
            } catch (exception: Exception) {
                lastError = exception
                if (HttpJson.isAccessLimited(exception)) break
            }
        }
        throw IOException(
            "Wildberries временно не отдал каталог. Откройте поиск на площадке или повторите позже.",
            lastError,
        )
    }

    fun latest(product: TrackedProduct): ProductOffer? {
        val id = product.externalId ?: idFromUrl(product.productUrl)
        if (id != null) {
            runCatching { fetchById(id) }.getOrNull()?.let { return it }
        }
        val offers = search(product.query)
        return MarketplaceRepository.bestMatch(product, offers)
    }

    private fun fetchById(id: String): ProductOffer? {
        val endpoint = "https://card.wb.ru/cards/v2/detail?appType=1&curr=rub" +
            "&dest=-1257786&spp=30&nm=$id"
        val response = JSONObject(HttpJson.get(endpoint, "https://www.wildberries.ru/"))
        val products = response.optJSONObject("data")?.optJSONArray("products")
            ?: response.optJSONArray("products")
        return products?.let(::parseProducts)?.firstOrNull()
    }

    private fun parseProducts(products: JSONArray): List<ProductOffer> = buildList {
        for (index in 0 until products.length()) {
            val product = products.optJSONObject(index) ?: continue
            val id = product.optString("id", product.optString("nmId", "")).takeIf { it.isNotBlank() } ?: continue
            val price = productPrice(product) ?: continue
            val name = product.optString("name", "Товар").trim()
            val brand = product.optString("brand", "").trim()
            val title = when {
                name.isBlank() -> brand.ifBlank { "Товар Wildberries" }
                brand.isBlank() || name.startsWith(brand, ignoreCase = true) -> name
                else -> "$brand $name"
            }
            add(
                ProductOffer(
                    marketplace = Marketplace.WILDBERRIES,
                    title = title,
                    price = price,
                    productUrl = "https://www.wildberries.ru/catalog/$id/detail.aspx",
                    externalId = id,
                ),
            )
        }
    }

    private fun productPrice(product: JSONObject): Double? {
        listOf("salePriceU", "priceU").forEach { key ->
            val value = product.optDouble(key, 0.0)
            if (value > 0.0) return value / 100.0
        }

        val sizes = product.optJSONArray("sizes") ?: return null
        for (index in 0 until sizes.length()) {
            val priceObject = sizes.optJSONObject(index)?.optJSONObject("price") ?: continue
            listOf("salePriceU", "product", "retail").forEach { key ->
                val value = priceObject.optDouble(key, 0.0)
                if (value > 0.0) return value / 100.0
            }
        }
        return null
    }

    private fun idFromUrl(value: String): String? {
        if (!value.contains("wildberries.ru", ignoreCase = true)) return null
        return Regex("/catalog/(\\d+)", RegexOption.IGNORE_CASE).find(value)?.groupValues?.getOrNull(1)
    }
}
