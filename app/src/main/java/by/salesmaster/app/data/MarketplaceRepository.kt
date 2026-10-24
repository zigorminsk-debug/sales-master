package by.salesmaster.app.data

import android.content.Context
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CompletableFuture

class MarketplaceRepository(context: Context? = null) {
    private val wildberries = WildberriesClient()
    private val ozon = OzonClient()

    init {
        if (context != null) {
            MarketplaceWebBridge.init(context)
        }
    }

    fun search(query: String, marketplaces: Set<Marketplace>): SearchResponse {
        val trimmed = query.trim()
        val isWbUrl = WildberriesClient.isExplicitWildberriesUrl(trimmed)
        val isOzonUrl = trimmed.contains("ozon.", ignoreCase = true) || trimmed.contains("ozone.ru", ignoreCase = true)

        if (isWbUrl) {
            return searchFromExplicitUrl(
                primary = Marketplace.WILDBERRIES,
                secondary = Marketplace.OZON.takeIf { it in marketplaces },
                urlQuery = trimmed,
            )
        }
        if (isOzonUrl) {
            return searchFromExplicitUrl(
                primary = Marketplace.OZON,
                secondary = Marketplace.WILDBERRIES.takeIf { it in marketplaces },
                urlQuery = trimmed,
            )
        }

        val offers = Collections.synchronizedList(mutableListOf<ProductOffer>())
        val errors = Collections.synchronizedList(mutableListOf<SearchFailure>())

        val futures = marketplaces.map { marketplace ->
            CompletableFuture.runAsync {
                try {
                    val found = searchSingle(marketplace, trimmed)
                    offers.addAll(found)
                } catch (exception: Exception) {
                    errors.add(buildFailure(marketplace, exception))
                }
            }
        }
        futures.forEach { runCatching { it.join() } }

        return SearchResponse(
            offers = offers.distinctBy { it.marketplace to (it.externalId ?: it.productUrl) }.sortedBy { it.price },
            errors = errors.sortedBy { it.marketplace.ordinal },
        )
    }

    fun latest(product: TrackedProduct): ProductOffer? = when (product.marketplace) {
        Marketplace.WILDBERRIES -> wildberries.latest(product)
        Marketplace.OZON -> ozon.latest(product)
    }

    private fun searchFromExplicitUrl(
        primary: Marketplace,
        secondary: Marketplace?,
        urlQuery: String,
    ): SearchResponse {
        val offers = mutableListOf<ProductOffer>()
        val errors = mutableListOf<SearchFailure>()

        val primaryOffers = try {
            searchSingle(primary, urlQuery)
        } catch (exception: Exception) {
            errors += buildFailure(primary, exception)
            emptyList()
        }
        offers += primaryOffers

        val primaryTitle = primaryOffers.firstOrNull()?.title
            ?.takeIf { !it.startsWith("Товар ") && it.length >= 4 }

        if (secondary != null && primaryTitle != null) {
            try {
                offers += searchSingle(secondary, primaryTitle).take(10)
            } catch (exception: Exception) {
                errors += buildFailure(secondary, exception)
            }
        }

        return SearchResponse(
            offers = offers.distinctBy { it.marketplace to (it.externalId ?: it.productUrl) }.sortedBy { it.price },
            errors = errors,
        )
    }

    private fun searchSingle(marketplace: Marketplace, query: String): List<ProductOffer> =
        when (marketplace) {
            Marketplace.WILDBERRIES -> wildberries.search(query)
            Marketplace.OZON -> ozon.search(query)
        }

    private fun buildFailure(marketplace: Marketplace, exception: Exception): SearchFailure {
        val challenge = HttpJson.isAccessLimited(exception) || MarketplaceWebBridge.wasChallengeRequired(marketplace)
        return SearchFailure(
            marketplace = marketplace,
            message = readableFailure(exception),
            challengeRequired = challenge,
        )
    }

    private fun readableFailure(exception: Exception): String {
        val messages = generateSequence<Throwable>(exception) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        return when {
            "http 403" in messages || "http 498" in messages || "антибот" in messages ->
                "площадка запросила проверку браузера (антибот) — нажмите кнопку проверки ниже"
            "http 429" in messages ->
                "площадка просит снизить частоту запросов (HTTP 429) — можно загрузить через встроенную проверку"
            "ограничил" in messages ->
                "площадка временно ограничила автоматический поиск — используйте встроенную проверку ниже"
            "redirect" in messages || "перенаправ" in messages || "follow-up" in messages ->
                "площадка перенаправляет запрос на проверку — нажмите кнопку проверки ниже"
            "html" in messages || "страницу защиты" in messages || "status line" in messages ->
                "площадка вернула страницу защиты вместо каталога — пройдите быструю проверку ниже"
            else -> "каталог временно недоступен или требует подтверждения в браузере"
        }
    }

    companion object {
        fun bestMatch(product: TrackedProduct, offers: List<ProductOffer>): ProductOffer? {
            if (offers.isEmpty()) return null
            product.externalId?.let { id -> offers.firstOrNull { it.externalId == id }?.let { return it } }
            offers.firstOrNull { it.productUrl == product.productUrl }?.let { return it }

            val wanted = tokens(product.name.ifBlank { product.query })
            if (wanted.isEmpty()) return offers.minByOrNull { it.price }
            val ranked = offers.map { offer ->
                val candidate = tokens(offer.title)
                val score = wanted.count { it in candidate }.toDouble() / wanted.size.toDouble()
                offer to score
            }
            val best = ranked.maxWithOrNull(compareBy<Pair<ProductOffer, Double>> { it.second }.thenBy { -it.first.price })
            return if (best != null && best.second >= 0.18) best.first else offers.minByOrNull { it.price }
        }

        private fun tokens(value: String): Set<String> =
            value.lowercase(Locale.ROOT)
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .split(Regex("\\s+"))
                .filter { it.length >= 3 }
                .toSet()
    }
}
