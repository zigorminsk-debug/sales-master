package by.salesmaster.app.data

import java.util.Locale

class MarketplaceRepository {
    private val wildberries = WildberriesClient()
    private val ozon = OzonClient()

    fun search(query: String, marketplaces: Set<Marketplace>): SearchResponse {
        val offers = mutableListOf<ProductOffer>()
        val errors = mutableListOf<String>()
        marketplaces.forEach { marketplace ->
            try {
                val found = when (marketplace) {
                    Marketplace.WILDBERRIES -> wildberries.search(query)
                    Marketplace.OZON -> ozon.search(query)
                }
                offers += found
            } catch (exception: Exception) {
                errors += "${marketplace.displayName}: ${exception.message ?: "не удалось получить цены"}"
            }
        }
        return SearchResponse(
            offers = offers.distinctBy { it.marketplace to (it.externalId ?: it.productUrl) }.sortedBy { it.price },
            errors = errors,
        )
    }

    fun latest(product: TrackedProduct): ProductOffer? = when (product.marketplace) {
        Marketplace.WILDBERRIES -> wildberries.latest(product)
        Marketplace.OZON -> ozon.latest(product)
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
