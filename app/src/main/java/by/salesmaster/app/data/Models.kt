package by.salesmaster.app.data

import java.util.Locale

enum class Marketplace(val displayName: String, val shortName: String) {
    WILDBERRIES("Wildberries", "WB"),
    OZON("Ozon", "Ozon");

    companion object {
        fun fromStorage(value: String?): Marketplace =
            entries.firstOrNull { it.name == value } ?: WILDBERRIES
    }
}

data class ProductOffer(
    val marketplace: Marketplace,
    val title: String,
    val price: Double,
    val productUrl: String,
    val externalId: String? = null,
)

data class TrackedProduct(
    val id: String,
    val name: String,
    val query: String,
    val marketplace: Marketplace,
    val productUrl: String,
    val externalId: String? = null,
    val targetPrice: Double? = null,
    val lastPrice: Double? = null,
    val lastNotifiedPrice: Double? = null,
    val lastCheckedAt: Long? = null,
)

data class SearchFailure(
    val marketplace: Marketplace,
    val message: String,
)

data class SearchResponse(
    val offers: List<ProductOffer>,
    val errors: List<SearchFailure>,
)

fun formatRubles(value: Double): String =
    String.format(Locale("ru", "RU"), "%,.0f ₽", value)
