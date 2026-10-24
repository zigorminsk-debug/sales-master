package by.salesmaster.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

internal class WildberriesClient {
    fun search(query: String): List<ProductOffer> {
        val productId = extractProductId(query)
        if (productId != null) {
            val byId = runCatching { fetchById(productId) }.getOrNull()
            if (byId != null) return listOf(byId)
            if (isExplicitWildberriesUrl(query)) {
                throw IOException("Wildberries не удалось загрузить эту карточку. Пройдите проверку площадки или откройте ссылку.")
            }
        }

        val encodedQuery = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val searchEndpoints = listOf(
            "https://www.wildberries.ru/__internal/u-search/exactmatch/ru/common/v18/search" +
                "?ab_testing=false&appType=1&curr=rub&dest=-1257786&lang=ru&page=1" +
                "&query=$encodedQuery&resultset=catalog&sort=priceup&spp=30&suppressSpellcheck=false",
            "https://search.wb.ru/exactmatch/ru/common/v18/search" +
                "?ab_testing=false&appType=1&curr=rub&dest=-1257786&lang=ru&page=1" +
                "&query=$encodedQuery&resultset=catalog&sort=priceup&spp=30&suppressSpellcheck=false",
            "https://search.wb.ru/exactmatch/ru/common/v9/search" +
                "?ab_testing=false&appType=1&curr=rub&dest=-1257786&lang=ru&page=1" +
                "&query=$encodedQuery&resultset=catalog&sort=priceup&spp=30&suppressSpellcheck=false",
            "https://search.wb.ru/exactmatch/ru/common/v4/search" +
                "?appType=1&curr=rub&dest=-1257786&lang=ru&page=1" +
                "&query=$encodedQuery&resultset=catalog&sort=priceup&spp=30",
        )

        var lastError: Exception? = null
        var accessBlockedCount = 0

        for (endpoint in searchEndpoints) {
            try {
                val headers = if ("/__internal/" in endpoint) INTERNAL_API_HEADERS else emptyMap()
                val body = HttpJson.get(
                    url = endpoint,
                    referer = "https://www.wildberries.ru/",
                    extraHeaders = headers,
                    expectJson = true,
                )
                val offers = parseProductsFromJson(body).sortedBy { it.price }
                if (offers.isNotEmpty()) return offers.take(30)
                lastError = IOException("по запросу не найдено товаров с распознанной ценой")
            } catch (exception: Exception) {
                lastError = exception
                if (HttpJson.isAccessLimited(exception)) {
                    accessBlockedCount++
                    if (accessBlockedCount >= 2) break
                }
            }
        }

        // Fallback to Android WebView (solves wbaas JS PoW challenge & reads internal API / DOM)
        val webOffers = runCatching { MarketplaceWebBridge.fetchWildberriesSearch(query) }.getOrDefault(emptyList())
        if (webOffers.isNotEmpty()) {
            return webOffers.sortedBy { it.price }.take(30)
        }

        // If WebView refreshed the x_wbaas_token cookie in CookieManager, try internal API once more
        val retryInternal = runCatching {
            val body = HttpJson.get(
                url = searchEndpoints.first(),
                referer = "https://www.wildberries.ru/",
                extraHeaders = INTERNAL_API_HEADERS,
                expectJson = true,
            )
            parseProductsFromJson(body).sortedBy { it.price }
        }.getOrDefault(emptyList())
        if (retryInternal.isNotEmpty()) {
            return retryInternal.take(30)
        }

        throw IOException(
            "Wildberries временно не отдал каталог (HTTP 403 антибот). Нажмите «Пройти проверку и загрузить цены» ниже.",
            lastError,
        )
    }

    fun latest(product: TrackedProduct): ProductOffer? {
        val id = product.externalId ?: extractProductId(product.productUrl) ?: extractProductId(product.query)
        if (id != null) {
            runCatching { fetchById(id) }.getOrNull()?.let { return it }
        }
        val offers = search(product.query)
        return MarketplaceRepository.bestMatch(product, offers)
    }

    internal fun fetchById(id: String): ProductOffer? {
        val cleanId = id.trim()
        // 1. Try v4 Card APIs (internal and public)
        val cardEndpoints = listOf(
            "https://www.wildberries.ru/__internal/u-card/cards/v4/detail?appType=1&curr=rub&dest=-1257786&spp=30&lang=ru&nm=$cleanId",
            "https://card.wb.ru/cards/v4/detail?appType=1&curr=rub&dest=-1257786&spp=30&locale=ru&nm=$cleanId",
            "https://card.wb.ru/cards/v4/detail?dest=-1257786&nm=$cleanId",
        )
        for (endpoint in cardEndpoints) {
            val offer = runCatching {
                val headers = if ("/__internal/" in endpoint) INTERNAL_API_HEADERS else emptyMap()
                val body = HttpJson.get(
                    url = endpoint,
                    referer = "https://www.wildberries.ru/catalog/$cleanId/detail.aspx",
                    extraHeaders = headers,
                    expectJson = true,
                )
                parseProductsFromJson(body).firstOrNull()
            }.getOrNull()
            if (offer != null && offer.price > 0.0) {
                return offer
            }
        }

        // 2. Fetch static card metadata & price history from Basket CDN (wbbasket.ru - unblocked!)
        val basketCard = runCatching { fetchFromBasketCdn(cleanId) }.getOrNull()

        // 3. Try headless WebView to get live real-time price and unlock x_wbaas_token cookie
        val webOffer = runCatching { MarketplaceWebBridge.fetchWildberriesCard(cleanId) }.getOrNull()
        if (webOffer != null && webOffer.price > 0.0) {
            val bestTitle = if (
                basketCard != null &&
                basketCard.title.isNotBlank() &&
                webOffer.title.startsWith("Товар Wildberries")
            ) {
                basketCard.title
            } else {
                webOffer.title
            }
            return webOffer.copy(title = bestTitle)
        }

        // 4. If Basket CDN returned both title and price from price-history.json, return it
        if (basketCard != null && basketCard.price > 0.0) {
            return basketCard
        }

        return null
    }

    companion object {
        private val INTERNAL_API_HEADERS = mapOf(
            "x-requested-with" to "XMLHttpRequest",
            "x-spa-version" to "14.28.4",
        )

        private val volHostCache = ConcurrentHashMap<Long, String>()

        @Volatile
        private var cachedRouteHosts: List<RouteRange> = emptyList()

        private data class RouteRange(
            val from: Long,
            val to: Long,
            val host: String,
        )

        internal fun extractProductId(value: String): String? {
            val trimmed = value.trim()
            if (trimmed.isBlank()) return null

            if (trimmed.contains("wildberries.", ignoreCase = true) ||
                trimmed.contains("wb.ru", ignoreCase = true) ||
                trimmed.contains("wbbasket.ru", ignoreCase = true)
            ) {
                Regex("/catalog/(\\d{5,12})", RegexOption.IGNORE_CASE)
                    .find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
                Regex("[?&]nm=(\\d{5,12})", RegexOption.IGNORE_CASE)
                    .find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
                Regex("/part\\d+/(\\d{5,12})/", RegexOption.IGNORE_CASE)
                    .find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
            }

            Regex("^(?:(?:wb|вб|wildberries|артикул|арт\\.?)\\s*:?\\s*)?(\\d{6,12})$", RegexOption.IGNORE_CASE)
                .find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }

            return null
        }

        internal fun isExplicitWildberriesUrl(value: String): Boolean =
            value.contains("wildberries.", ignoreCase = true) ||
                value.contains("wb.ru", ignoreCase = true) ||
                value.contains("wbbasket.ru", ignoreCase = true)

        internal fun parseProductsFromJson(rawJson: String): List<ProductOffer> {
            val response = JSONObject(rawJson)
            val products = response.optJSONObject("data")?.optJSONArray("products")
                ?: response.optJSONArray("products")
                ?: response.optJSONObject("payload")?.optJSONArray("products")
                ?: return emptyList()
            return parseProducts(products)
        }

        internal fun parseProducts(products: JSONArray): List<ProductOffer> = buildList {
            for (index in 0 until products.length()) {
                val product = products.optJSONObject(index) ?: continue
                val id = product.optString("id", product.optString("nmId", ""))
                    .takeIf { it.isNotBlank() } ?: continue
                val price = productPrice(product) ?: continue
                val name = product.optString("name", "Товар").trim()
                val brand = product.optString("brand", "").trim()
                val title = formatTitle(brand, name, id)
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

        internal fun productPrice(product: JSONObject): Double? {
            // 1. Top-level price object (v18 / internal format)
            product.optJSONObject("price")?.let { priceObj ->
                for (key in listOf("product", "total", "salePriceU", "basic")) {
                    val value = priceObj.optDouble(key, 0.0)
                    if (value > 0.0) return value / 100.0
                }
            }

            // 2. sizes[].price object (v4 card and v9/v18 search format)
            val sizes = product.optJSONArray("sizes")
            if (sizes != null) {
                for (index in 0 until sizes.length()) {
                    val priceObject = sizes.optJSONObject(index)?.optJSONObject("price") ?: continue
                    for (key in listOf("product", "total", "salePriceU", "retail", "basic")) {
                        val value = priceObject.optDouble(key, 0.0)
                        if (value > 0.0) return value / 100.0
                    }
                }
            }

            // 3. Legacy top-level kopeck fields
            for (key in listOf("salePriceU", "priceU")) {
                val value = product.optDouble(key, 0.0)
                if (value > 0.0) return value / 100.0
            }

            // 4. Direct ruble field if present
            val rub = product.optDouble("priceRub", 0.0)
            if (rub > 0.0) return rub

            return null
        }

        internal fun parseBasketCardAndPrice(
            nmId: String,
            cardJson: String,
            priceHistoryJson: String?,
        ): ProductOffer? {
            val root = JSONObject(cardJson)
            val dataObj = root.optJSONObject("data") ?: root
            val name = dataObj.optString("imt_name", "")
                .ifBlank { dataObj.optString("subj_name", "") }
                .trim()
            val brand = dataObj.optJSONObject("selling")?.optString("brand_name", "")
                ?.ifBlank { dataObj.optString("brand_name", "") }
                .orEmpty()
                .trim()

            val title = formatTitle(brand, name, nmId)
            val price = priceHistoryJson?.let(::extractLatestPriceFromHistory) ?: return null
            if (price <= 0.0) return null

            return ProductOffer(
                marketplace = Marketplace.WILDBERRIES,
                title = title,
                price = price,
                productUrl = "https://www.wildberries.ru/catalog/$nmId/detail.aspx",
                externalId = nmId,
            )
        }

        internal fun extractLatestPriceFromHistory(priceHistoryJson: String): Double? {
            val array = runCatching { JSONArray(priceHistoryJson) }.getOrNull() ?: return null
            for (index in array.length() - 1 downTo 0) {
                val point = array.optJSONObject(index) ?: continue
                val priceObj = point.optJSONObject("price") ?: continue
                for (key in listOf("RUB", "rub", "BYN", "KZT")) {
                    val kopecks = priceObj.optDouble(key, 0.0)
                    if (kopecks > 0.0 && (key == "RUB" || key == "rub")) {
                        return kopecks / 100.0
                    }
                }
            }
            return null
        }

        internal fun basketHostForVol(vol: Long): String {
            cachedRouteHosts.firstOrNull { vol in it.from..it.to }?.let { return it.host }
            val number = when {
                vol <= 143 -> 1
                vol <= 287 -> 2
                vol <= 431 -> 3
                vol <= 719 -> 4
                vol <= 1007 -> 5
                vol <= 1061 -> 6
                vol <= 1115 -> 7
                vol <= 1169 -> 8
                vol <= 1313 -> 9
                vol <= 1601 -> 10
                vol <= 1655 -> 11
                vol <= 1919 -> 12
                vol <= 2045 -> 13
                vol <= 2189 -> 14
                vol <= 2405 -> 15
                vol <= 2621 -> 16
                vol <= 2837 -> 17
                vol <= 3053 -> 18
                vol <= 3269 -> 19
                vol <= 3485 -> 20
                vol <= 3701 -> 21
                vol <= 3917 -> 22
                vol <= 4133 -> 23
                vol <= 4349 -> 24
                vol <= 4565 -> 25
                vol <= 4781 -> 26
                vol <= 4997 -> 27
                vol <= 5213 -> 28
                vol <= 5429 -> 29
                vol <= 5645 -> 30
                vol <= 5861 -> 31
                vol <= 6077 -> 32
                vol <= 6293 -> 33
                vol <= 6509 -> 34
                vol <= 6725 -> 35
                vol <= 6941 -> 36
                vol <= 7157 -> 37
                vol <= 7373 -> 38
                vol <= 8899 -> 39
                vol <= 9599 -> 40
                vol <= 10299 -> 41
                vol <= 11099 -> 42
                vol <= 11899 -> 43
                vol <= 12699 -> 44
                else -> 45
            }
            return String.format("basket-%02d.wbbasket.ru", number)
        }

        private fun fetchFromBasketCdn(nmId: String): ProductOffer? {
            val nm = nmId.toLongOrNull() ?: return null
            val vol = nm / 100_000L
            val part = nm / 1_000L
            val pathSuffix = "/vol$vol/part$part/$nm/info"

            ensureUpstreamsLoaded()

            val candidates = linkedSetOf<String>()
            volHostCache[vol]?.let { candidates.add(it) }
            candidates.add(basketHostForVol(vol))
            // Add neighbor shards in case WB expanded shard boundaries
            val predictedNum = Regex("basket-(\\d+)").find(basketHostForVol(vol))
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 28
            for (delta in listOf(1, -1, 2, 3, 4)) {
                val idx = predictedNum + delta
                if (idx in 1..60) {
                    candidates.add(String.format("basket-%02d.wbbasket.ru", idx))
                }
            }

            for (host in candidates) {
                val cardUrl = "https://$host$pathSuffix/ru/card.json"
                val cardJson = runCatching {
                    HttpJson.get(cardUrl, referer = "https://www.wildberries.ru/", expectJson = true)
                }.getOrNull() ?: continue

                volHostCache[vol] = host
                val priceHistoryUrl = "https://$host$pathSuffix/price-history.json"
                val priceHistoryJson = runCatching {
                    HttpJson.get(priceHistoryUrl, referer = "https://www.wildberries.ru/", expectJson = true)
                }.getOrNull()

                parseBasketCardAndPrice(nmId, cardJson, priceHistoryJson)?.let { return it }
                break
            }
            return null
        }

        private fun ensureUpstreamsLoaded() {
            if (cachedRouteHosts.isNotEmpty()) return
            runCatching {
                val body = HttpJson.get(
                    url = "https://cdn.wbbasket.ru/api/v3/upstreams",
                    referer = "https://www.wildberries.ru/",
                    expectJson = true,
                )
                val root = JSONObject(body)
                val mapArray = root.optJSONObject("recommend")?.optJSONArray("mediabasket_route_map") ?: return
                val firstMap = mapArray.optJSONObject(0) ?: return
                val hosts = firstMap.optJSONArray("hosts") ?: return
                val parsed = buildList {
                    for (i in 0 until hosts.length()) {
                        val h = hosts.optJSONObject(i) ?: continue
                        val from = h.optLong("vol_range_from", -1L)
                        val to = h.optLong("vol_range_to", -1L)
                        val host = h.optString("host", "").trim()
                        if (from >= 0L && to >= from && host.isNotBlank()) {
                            add(RouteRange(from, to, host))
                        }
                    }
                }
                if (parsed.isNotEmpty()) {
                    cachedRouteHosts = parsed
                }
            }
        }

        private fun formatTitle(brand: String, name: String, id: String): String = when {
            name.isBlank() -> brand.ifBlank { "Товар Wildberries $id" }
            brand.isBlank() || name.startsWith(brand, ignoreCase = true) -> name
            else -> "$brand $name"
        }
    }
}
