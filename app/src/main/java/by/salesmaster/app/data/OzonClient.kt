package by.salesmaster.app.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Ozon's consumer web composer is not a published API; the parser is intentionally tolerant. */
internal class OzonClient {
    fun search(query: String): List<ProductOffer> {
        val path = productPath(query) ?: "/search/?text=$query"
        val encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8.name())
        val endpoint = "https://www.ozon.ru/api/composer-api.bx/page/json/v2?url=$encodedPath"
        val response = JSONObject(HttpJson.get(endpoint, "https://www.ozon.ru/"))
        val offers = OzonOfferParser.parse(response)
        if (offers.isEmpty()) throw IOException("Ozon не вернул товары с распознанными ценами")
        return offers.sortedBy { it.price }.take(30)
    }

    fun latest(product: TrackedProduct): ProductOffer? {
        val input = if (product.productUrl.contains("ozon.ru", ignoreCase = true)) {
            product.productUrl
        } else {
            product.query
        }
        return MarketplaceRepository.bestMatch(product, search(input))
    }

    private fun productPath(value: String): String? {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        if (!uri.host.orEmpty().contains("ozon.ru", ignoreCase = true)) return null
        val path = uri.path.orEmpty().ifBlank { return null }
        val query = uri.encodedQuery?.let { "?$it" }.orEmpty()
        return path + query
    }
}

private object OzonOfferParser {
    private const val MAX_DEPTH = 24
    private const val MAX_OFFERS = 100

    fun parse(root: JSONObject): List<ProductOffer> {
        val offers = LinkedHashMap<String, ProductOffer>()
        visit(root, offers, 0)
        return offers.values.toList()
    }

    private fun visit(node: Any?, offers: LinkedHashMap<String, ProductOffer>, depth: Int) {
        if (depth > MAX_DEPTH || offers.size >= MAX_OFFERS) return
        when (node) {
            is JSONObject -> {
                candidate(node)?.let { offer ->
                    offers.putIfAbsent(offer.productUrl, offer)
                }
                val keys = node.keys()
                while (keys.hasNext() && offers.size < MAX_OFFERS) {
                    val child = node.opt(keys.next())
                    if (child is String) {
                        parseEmbeddedJson(child)?.let { visit(it, offers, depth + 1) }
                    } else {
                        visit(child, offers, depth + 1)
                    }
                }
            }
            is JSONArray -> for (index in 0 until node.length()) visit(node.opt(index), offers, depth + 1)
        }
    }

    private fun parseEmbeddedJson(value: String): Any? {
        val trimmed = value.trim()
        if (trimmed.length !in 2..2_000_000) return null
        return try {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> null
            }
        } catch (_: JSONException) {
            null
        }
    }

    private fun candidate(node: JSONObject): ProductOffer? {
        val url = findProductUrl(node, 0) ?: return null
        val title = findTitle(node) ?: return null
        val price = findPrice(node, 0) ?: return null
        val canonicalUrl = when {
            url.startsWith("https://", ignoreCase = true) -> url
            url.startsWith("http://", ignoreCase = true) -> url.replaceFirst("http://", "https://", ignoreCase = true)
            url.startsWith("/") -> "https://www.ozon.ru$url"
            else -> return null
        }
        if (!canonicalUrl.contains("ozon.ru", ignoreCase = true)) return null
        val id = Regex("(?:-|/)(\\d{5,})(?:/|$|\\?)")
            .find(canonicalUrl)?.groupValues?.getOrNull(1)
        return ProductOffer(
            marketplace = Marketplace.OZON,
            title = title,
            price = price,
            productUrl = canonicalUrl,
            externalId = id,
        )
    }

    private fun findProductUrl(node: JSONObject, depth: Int): String? {
        if (depth > 3) return null
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = node.opt(key)
            if (value is String && looksLikeProductUrl(value)) return value.trim()
            if (value is JSONObject) findProductUrl(value, depth + 1)?.let { return it }
        }
        return null
    }

    private fun looksLikeProductUrl(value: String): Boolean =
        value.contains("/product/", ignoreCase = true) &&
            (value.contains("ozon.ru", ignoreCase = true) || value.trim().startsWith("/"))

    private fun findTitle(node: JSONObject): String? {
        val titleKeys = listOf("title", "name", "productname", "producttitle", "skuName", "text")
        for (wanted in titleKeys) {
            val keys = node.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!key.equals(wanted, ignoreCase = true)) continue
                val value = node.opt(key) as? String ?: continue
                val cleaned = value.replace(Regex("\\s+"), " ").trim()
                if (cleaned.length >= 3 && !cleaned.contains('₽') && !cleaned.startsWith("/product/")) return cleaned
            }
        }
        return null
    }

    private fun findPrice(node: JSONObject, depth: Int): Double? {
        if (depth > 4) return null
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = node.opt(key)
            if (key.contains("price", ignoreCase = true)) {
                when (value) {
                    is String -> parsePrice(value)?.let { return it }
                    is Number -> value.toDouble().takeIf { it > 0.0 }?.let { return it }
                    is JSONObject -> findPrice(value, depth + 1)?.let { return it }
                }
            }
            if (value is String && value.contains('₽')) parsePrice(value)?.let { return it }
        }
        val children = node.keys()
        while (children.hasNext()) {
            val child = node.opt(children.next())
            if (child is JSONObject) findPrice(child, depth + 1)?.let { return it }
            if (child is JSONArray) {
                for (index in 0 until child.length()) {
                    val item = child.opt(index)
                    if (item is JSONObject) findPrice(item, depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    private fun parsePrice(value: String): Double? {
        val match = Regex("[0-9][0-9\\s\\u00A0\\u202F.,]*").find(value) ?: return null
        var number = match.value
            .replace(" ", "")
            .replace("\u00A0", "")
            .replace("\u202F", "")
        val comma = number.lastIndexOf(',')
        val dot = number.lastIndexOf('.')
        val separator = maxOf(comma, dot)
        if (separator >= 0) {
            val fractionalLength = number.length - separator - 1
            number = when {
                comma >= 0 && dot >= 0 -> {
                    val decimal = if (comma > dot) ',' else '.'
                    number.filterIndexed { index, char -> char != '.' && char != ',' || index == number.lastIndexOf(decimal) }
                        .replace(decimal, '.')
                }
                fractionalLength in 1..2 -> number.replace(',', '.')
                else -> number.replace(",", "").replace(".", "")
            }
        }
        return number.toDoubleOrNull()?.takeIf { it > 0.0 && it < 100_000_000.0 }
    }
}
