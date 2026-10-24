package by.salesmaster.app.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Ozon client with Composer API v2, JSON-LD, and Android WebView JS/DOM fallback. */
internal class OzonClient {
    fun search(query: String): List<ProductOffer> {
        val targetPath = extractTargetPath(query)
            ?: "/search/?text=${URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())}&from_global=true&sorting=price"
        val encodedPath = URLEncoder.encode(targetPath, StandardCharsets.UTF_8.name())
        val pageType = if (targetPath.startsWith("/product/")) "detail" else "search"
        val ozonHeaders = mapOf(
            "x-o3-app-name" to "dweb_client",
            "x-o3-app-version" to "release_2-7-1",
            "x-o3-page-type" to pageType,
        )
        val endpoints = listOf(
            "https://www.ozon.ru/api/entrypoint-api.bx/page/json/v2?url=$encodedPath",
            "https://www.ozon.ru/api/composer-api.bx/page/json/v2?url=$encodedPath",
            "https://api.ozon.ru/composer-api.bx/page/json/v2?url=$encodedPath",
        )
        var lastError: Exception? = null
        var accessBlockedCount = 0

        for (endpoint in endpoints) {
            try {
                val body = HttpJson.get(
                    url = endpoint,
                    referer = "https://www.ozon.ru/",
                    extraHeaders = ozonHeaders,
                    expectJson = true,
                )
                val offers = OzonOfferParser.parseResponse(body, fallbackPath = targetPath)
                if (offers.isNotEmpty()) return offers.sortedBy { it.price }.take(30)
                lastError = IOException("ответ API не содержит карточек с ценами")
            } catch (exception: Exception) {
                lastError = exception
                if (HttpJson.isAccessLimited(exception)) {
                    accessBlockedCount++
                    if (accessBlockedCount >= 2) break
                }
            }
        }

        // If API routes were not explicitly blocked, try public page HTML / JSON-LD once
        if (accessBlockedCount == 0) {
            try {
                val pageUrl = "https://www.ozon.ru$targetPath"
                val page = HttpJson.get(pageUrl, "https://www.ozon.ru/")
                if (!HttpJson.isAntiBotHtml(page)) {
                    val offers = OzonOfferParser.parseResponse(page, fallbackPath = targetPath)
                    if (offers.isNotEmpty()) return offers.sortedBy { it.price }.take(30)
                }
            } catch (exception: Exception) {
                lastError = exception
            }
        }

        // Fallback to Android WebView (solves Ozon JS challenge, runs in-page fetch & DOM extraction)
        val webOffers = runCatching { MarketplaceWebBridge.fetchOzon(targetPath) }.getOrDefault(emptyList())
        if (webOffers.isNotEmpty()) {
            return webOffers.sortedBy { it.price }.take(30)
        }

        // If WebView unlocked Ozon session cookies in CookieManager, retry entrypoint-api once
        val retryAfterCookies = runCatching {
            val body = HttpJson.get(
                url = endpoints.first(),
                referer = "https://www.ozon.ru/",
                extraHeaders = ozonHeaders,
                expectJson = true,
            )
            OzonOfferParser.parseResponse(body, fallbackPath = targetPath)
        }.getOrDefault(emptyList())
        if (retryAfterCookies.isNotEmpty()) {
            return retryAfterCookies.sortedBy { it.price }.take(30)
        }

        throw IOException(
            "Ozon временно ограничил автоматический запрос (HTTP 403 антибот). Нажмите «Пройти проверку и загрузить цены» ниже.",
            lastError,
        )
    }

    fun latest(product: TrackedProduct): ProductOffer? {
        val input = if (product.productUrl.contains("ozon.", ignoreCase = true)) {
            product.productUrl
        } else {
            product.query
        }
        return MarketplaceRepository.bestMatch(product, search(input))
    }

    companion object {
        internal fun extractTargetPath(value: String): String? {
            val trimmed = value.trim()
            if (trimmed.isBlank()) return null

            val urlMatch = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE).find(trimmed)?.value
            if (urlMatch != null) {
                val parsed = runCatching { URI(urlMatch) }.getOrNull()
                val host = parsed?.host.orEmpty().lowercase(Locale.ROOT)
                if (host.contains("ozon.") || host.contains("ozone.ru")) {
                    var resolvedUrl = urlMatch
                    val initialPath = parsed?.rawPath.orEmpty()
                    if (initialPath.startsWith("/t/", ignoreCase = true)) {
                        resolvedUrl = HttpJson.resolveFinalUrl(urlMatch)
                    }
                    val finalUri = runCatching { URI(resolvedUrl) }.getOrNull() ?: parsed
                    val path = finalUri?.rawPath.orEmpty().ifBlank { return null }
                    if (path.startsWith("/product/", ignoreCase = true)) {
                        return if (path.endsWith("/")) path else "$path/"
                    }
                    val query = finalUri?.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
                    return path + query
                }
            }

            if (trimmed.startsWith("/product/", ignoreCase = true)) {
                val clean = trimmed.substringBefore('?')
                return if (clean.endsWith("/")) clean else "$clean/"
            }

            Regex("^(?:(?:ozon|озон|артикул|арт\\.?)\\s*:?\\s*)?(\\d{7,12})$", RegexOption.IGNORE_CASE)
                .find(trimmed)?.groupValues?.getOrNull(1)?.let { sku ->
                    return "/product/$sku/"
                }

            return null
        }
    }
}

internal object OzonOfferParser {
    private const val MAX_DEPTH = 24
    private const val MAX_OFFERS = 100

    fun parseResponse(body: String, fallbackPath: String? = null): List<ProductOffer> {
        val offers = LinkedHashMap<String, ProductOffer>()
        val trimmed = body.trim()

        val rootObject = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: extractEmbeddedWidgetStates(trimmed)

        if (rootObject != null) {
            parseWidgetStates(rootObject, offers, fallbackPath, depth = 0)
            parseJsonLdObject(rootObject, offers, fallbackPath)
            if (offers.isEmpty()) {
                visit(rootObject, offers, 0)
            }
        }

        if (offers.isEmpty()) {
            runCatching { JSONArray(trimmed) }.getOrNull()?.let { visit(it, offers, 0) }
        }

        if (offers.isEmpty()) {
            val scripts = Regex("(?is)<script\\b[^>]*>(.*?)</script>").findAll(body)
            scripts.forEach { match ->
                val script = decodeHtml(match.groupValues[1].trim())
                parseEmbeddedJson(script)?.let { parsed ->
                    if (parsed is JSONObject) {
                        parseWidgetStates(parsed, offers, fallbackPath, depth = 0)
                        parseJsonLdObject(parsed, offers, fallbackPath)
                    }
                    if (offers.size < MAX_OFFERS) {
                        visit(parsed, offers, 0)
                    }
                }
            }
        }

        if (offers.isEmpty()) {
            parseProductMeta(body, fallbackPath)?.let { offers[it.productUrl] = it }
        }
        return offers.values.toList()
    }

    private fun extractEmbeddedWidgetStates(html: String): JSONObject? {
        if (!html.startsWith("<")) return null
        val marker = "\"widgetStates\":"
        val startIdx = html.indexOf(marker)
        if (startIdx >= 0) {
            val after = html.substring(startIdx + marker.length)
            val endIdx = after.indexOf(",\"layout\":")
            if (endIdx > 0) {
                val slice = "{\"widgetStates\":${after.substring(0, endIdx)}}"
                runCatching { return JSONObject(slice) }
            }
        }
        return null
    }

    private fun parseWidgetStates(
        root: JSONObject,
        offers: LinkedHashMap<String, ProductOffer>,
        fallbackPath: String?,
        depth: Int,
    ) {
        if (depth > 4) return
        val widgetStates = root.optJSONObject("widgetStates") ?: return

        var cardTitle: String? = null
        var cardPrice: Double? = null
        var cardUrl: String? = null
        var cardId: String? = null

        val keys = widgetStates.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val rawVal = widgetStates.opt(key) ?: continue
            val widgetObj = when (rawVal) {
                is JSONObject -> rawVal
                is String -> parseEmbeddedJson(rawVal) as? JSONObject
                else -> null
            } ?: continue

            // Nested widgetStates (e.g. inside megaPaginator)
            if (widgetObj.has("widgetStates")) {
                parseWidgetStates(widgetObj, offers, fallbackPath, depth + 1)
            }

            val lowerKey = key.lowercase(Locale.ROOT)
            val isSearchWidget = lowerKey.startsWith("tilegriddesktop") ||
                lowerKey.startsWith("searchresultsv2") ||
                lowerKey.startsWith("searchresultsv3") ||
                lowerKey.startsWith("searchmergeddesktop") ||
                lowerKey.startsWith("tilegrid") ||
                lowerKey.startsWith("catalog") ||
                lowerKey.startsWith("megapaginator") ||
                lowerKey.startsWith("shelf") ||
                lowerKey.startsWith("skugrid") ||
                lowerKey.startsWith("skuline")

            if (isSearchWidget) {
                val items = widgetObj.optJSONArray("items")
                    ?: widgetObj.optJSONArray("products")
                    ?: widgetObj.optJSONArray("elements")
                    ?: widgetObj.optJSONArray("tiles")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        if (offers.size >= MAX_OFFERS) break
                        val item = items.optJSONObject(i) ?: continue
                        parseSearchTile(item)?.let { offer ->
                            offers.putIfAbsent(offer.productUrl, offer)
                        }
                    }
                }
            }

            if (lowerKey.startsWith("webproductheading")) {
                val t = widgetObj.optString("title", "").trim()
                if (t.isNotBlank()) cardTitle = t
            } else if (lowerKey.startsWith("webprice") || lowerKey.startsWith("websale")) {
                val p = parsePrice(widgetObj.optString("cardPrice", ""))
                    ?: parsePrice(widgetObj.optString("price", ""))
                    ?: findPrice(widgetObj, 0)
                if (p != null && p > 0.0) cardPrice = p
            } else if (lowerKey.startsWith("webstickyproducts")) {
                if (cardTitle.isNullOrBlank()) {
                    val t = widgetObj.optString("name", widgetObj.optString("title", "")).trim()
                    if (t.isNotBlank()) cardTitle = t
                }
                if (cardPrice == null) {
                    findPrice(widgetObj, 0)?.let { cardPrice = it }
                }
                findProductUrl(widgetObj, 0)?.let { cardUrl = it }
                val sku = widgetObj.optString("sku", widgetObj.optString("id", "")).trim()
                if (sku.isNotBlank()) cardId = sku
            }
        }

        // Check seo object for product card fallback title/url/JSON-LD
        root.optJSONObject("seo")?.let { seo ->
            if (cardTitle.isNullOrBlank()) {
                val seoTitle = seo.optString("title", "")
                    .replace(Regex("(?i)\\s*[—\\-|]\\s*купить на ozon.*$"), "")
                    .trim()
                if (seoTitle.isNotBlank()) cardTitle = seoTitle
            }
            val scripts = seo.optJSONArray("script")
            if (scripts != null) {
                for (i in 0 until scripts.length()) {
                    val inner = scripts.optJSONObject(i)?.optString("innerHTML", "").orEmpty()
                    if (inner.isNotBlank()) {
                        (parseEmbeddedJson(inner) as? JSONObject)?.let { ld ->
                            parseJsonLdObject(ld, offers, fallbackPath)
                        }
                    }
                }
            }
        }

        if (!cardTitle.isNullOrBlank() && cardPrice != null && cardPrice > 0.0) {
            val rawUrl = cardUrl
                ?: fallbackPath?.takeIf { it.contains("/product/", ignoreCase = true) }
                ?: cardId?.let { "/product/$it/" }
            if (rawUrl != null) {
                canonicalOzonUrl(rawUrl)?.let { canonical ->
                    val id = cardId ?: extractIdFromUrl(canonical)
                    offers.putIfAbsent(
                        canonical,
                        ProductOffer(
                            marketplace = Marketplace.OZON,
                            title = cardTitle!!.replace(Regex("\\s+"), " ").trim(),
                            price = cardPrice!!,
                            productUrl = canonical,
                            externalId = id,
                        ),
                    )
                }
            }
        }
    }

    private fun parseSearchTile(item: JSONObject): ProductOffer? {
        val sku = item.optString("sku", item.optString("id", ""))
            .trim()
            .takeIf { it.isNotBlank() && it != "0" }

        val rawLink = item.optJSONObject("action")?.optString("link", "")
            ?.takeIf { it.isNotBlank() }
            ?: findProductUrl(item, 0)
            ?: sku?.let { "/product/$it/" }
            ?: return null

        val canonicalUrl = canonicalOzonUrl(rawLink) ?: return null
        val id = sku ?: extractIdFromUrl(canonicalUrl)

        var title: String? = null
        var price: Double? = null

        val mainState = item.optJSONArray("mainState") ?: item.optJSONArray("state")
        if (mainState != null) {
            for (i in 0 until mainState.length()) {
                val atom = mainState.optJSONObject(i) ?: continue
                val type = atom.optString("type", "")

                if (title == null) {
                    val textAtom = atom.optJSONObject("textAtom")
                        ?: if (type.equals("textAtom", ignoreCase = true)) atom else null
                    val text = textAtom?.optString("text", "")?.let(::decodeHtml)?.trim().orEmpty()
                    if (isLikelyProductTitle(text)) {
                        title = text.replace(Regex("\\s+"), " ")
                    }
                }

                if (price == null) {
                    val priceAtom = atom.optJSONObject("priceV2")
                        ?: atom.optJSONObject("priceV3")
                        ?: atom.optJSONObject("splitPrice")
                        ?: atom.optJSONObject("price")
                        ?: if (type.contains("price", ignoreCase = true)) atom else null
                    if (priceAtom != null) {
                        val priceArray = priceAtom.optJSONArray("price")
                        if (priceArray != null) {
                            for (j in 0 until priceArray.length()) {
                                val entry = priceArray.optJSONObject(j) ?: continue
                                val style = entry.optString("textStyle", entry.optString("theme", "")).uppercase(Locale.ROOT)
                                if ("ORIGINAL" in style || "STRIKE" in style || "OLD" in style) continue
                                val pText = entry.optString("text", entry.optString("price", ""))
                                val parsed = parsePrice(pText)
                                if (parsed != null && parsed > 0.0) {
                                    price = parsed
                                    break
                                }
                            }
                            if (price == null && priceArray.length() > 0) {
                                val firstText = priceArray.optJSONObject(0)?.optString("text", "").orEmpty()
                                price = parsePrice(firstText)
                            }
                        } else {
                            price = parsePrice(priceAtom.optString("price", ""))
                                ?: parsePrice(priceAtom.optString("cardPrice", ""))
                                ?: parsePrice(priceAtom.optString("text", ""))
                        }
                    }
                }
            }
        }

        if (title.isNullOrBlank()) {
            title = findTitle(item, 0)
        }
        if (price == null || price <= 0.0) {
            price = findPrice(item, 0)
        }

        val finalTitle = title?.takeIf { it.isNotBlank() } ?: return null
        val finalPrice = price?.takeIf { it > 0.0 } ?: return null

        return ProductOffer(
            marketplace = Marketplace.OZON,
            title = finalTitle,
            price = finalPrice,
            productUrl = canonicalUrl,
            externalId = id,
        )
    }

    private fun parseJsonLdObject(
        node: JSONObject,
        offers: LinkedHashMap<String, ProductOffer>,
        fallbackPath: String?,
    ) {
        val type = node.optString("@type", "")
        if (!type.equals("Product", ignoreCase = true)) return
        val name = decodeHtml(node.optString("name", "")).trim()
        if (name.isBlank()) return
        val offersNode = node.optJSONObject("offers")
            ?: node.optJSONArray("offers")?.optJSONObject(0)
            ?: return
        val rawPrice = offersNode.optString("price", offersNode.optString("lowPrice", ""))
        val price = parsePrice(rawPrice) ?: offersNode.optDouble("price", 0.0).takeIf { it > 0.0 } ?: return
        val rawUrl = offersNode.optString("url", node.optString("url", ""))
            .ifBlank { fallbackPath.orEmpty() }
        val canonical = canonicalOzonUrl(rawUrl) ?: return
        val id = node.optString("sku", "").takeIf { it.isNotBlank() } ?: extractIdFromUrl(canonical)
        offers.putIfAbsent(
            canonical,
            ProductOffer(
                marketplace = Marketplace.OZON,
                title = name,
                price = price,
                productUrl = canonical,
                externalId = id,
            ),
        )
    }

    private fun parseProductMeta(html: String, fallbackPath: String?): ProductOffer? {
        val values = mutableMapOf<String, String>()
        Regex("(?is)<meta\\b[^>]*>").findAll(html).forEach { match ->
            val tag = match.value
            val key = Regex("(?i)\\b(?:property|name)\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]")
                .find(tag)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT) ?: return@forEach
            val content = Regex("(?i)\\bcontent\\s*=\\s*['\\\"]([^'\\\"]*)['\\\"]")
                .find(tag)?.groupValues?.getOrNull(1) ?: return@forEach
            values[key] = decodeHtml(content)
        }
        val title = values["og:title"] ?: values["twitter:title"] ?: return null
        val url = values["og:url"] ?: values["twitter:url"] ?: fallbackPath ?: return null
        val rawPrice = values["product:price:amount"] ?: values["og:price:amount"] ?: return null
        val price = parsePrice(rawPrice) ?: return null
        val canonical = canonicalOzonUrl(url) ?: return null
        val id = extractIdFromUrl(canonical)
        return ProductOffer(Marketplace.OZON, title.trim(), price, canonical, id)
    }

    private fun decodeHtml(value: String): String = value
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#34;", "\"", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&#x2F;", "/", ignoreCase = true)
        .replace("&#47;", "/", ignoreCase = true)
        .replace("\\u002F", "/", ignoreCase = true)

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
        val title = findTitle(node, 0) ?: return null
        val price = findPrice(node, 0) ?: return null
        val canonicalUrl = canonicalOzonUrl(url) ?: return null
        val id = node.optString("id", node.optString("sku", "")).takeIf { it.isNotBlank() }
            ?: extractIdFromUrl(canonicalUrl)
        return ProductOffer(
            marketplace = Marketplace.OZON,
            title = title,
            price = price,
            productUrl = canonicalUrl,
            externalId = id,
        )
    }

    private fun canonicalOzonUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        val withHost = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed.replaceFirst("http://", "https://", ignoreCase = true)
            trimmed.startsWith("/") -> "https://www.ozon.ru$trimmed"
            else -> return null
        }
        if (!withHost.contains("ozon.", ignoreCase = true)) return null
        return if (withHost.contains("/product/", ignoreCase = true)) {
            withHost.substringBefore('?')
        } else {
            withHost
        }
    }

    private fun extractIdFromUrl(url: String): String? =
        Regex("(?:-|/)(\\d{5,})(?:/|$|\\?)").find(url)?.groupValues?.getOrNull(1)

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
            !value.contains("/reviews", ignoreCase = true) &&
            (value.contains("ozon.", ignoreCase = true) || value.trim().startsWith("/"))

    private fun isLikelyProductTitle(text: String): Boolean {
        if (text.length < 4) return false
        if (text.contains('₽') || text.startsWith("/product/")) return false
        val lower = text.lowercase(Locale.ROOT)
        if (lower.startsWith("+") || lower.endsWith("%")) return false
        val noise = listOf("балл", "отзыв", "оценк", "доставк", "в корзин", "завтра", "сегодня", "осталось ")
        return noise.none { it in lower }
    }

    private fun findTitle(node: JSONObject, depth: Int): String? {
        if (depth > 3) return null
        val titleKeys = listOf("title", "name", "productname", "producttitle", "skuName", "text")
        for (wanted in titleKeys) {
            val keys = node.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!key.equals(wanted, ignoreCase = true)) continue
                val value = node.opt(key) as? String ?: continue
                val cleaned = decodeHtml(value).replace(Regex("\\s+"), " ").trim()
                if (isLikelyProductTitle(cleaned)) return cleaned
            }
        }
        val children = node.keys()
        while (children.hasNext()) {
            val child = node.opt(children.next())
            if (child is JSONObject) {
                findTitle(child, depth + 1)?.let { return it }
            } else if (child is JSONArray) {
                for (i in 0 until child.length()) {
                    val elem = child.optJSONObject(i) ?: continue
                    findTitle(elem, depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    private fun findPrice(node: JSONObject, depth: Int): Double? {
        if (depth > 4) return null
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.contains("original", ignoreCase = true) || key.contains("old", ignoreCase = true)) continue
            val value = node.opt(key)
            if (key.contains("price", ignoreCase = true)) {
                when (value) {
                    is String -> parsePrice(value)?.let { return it }
                    is Number -> value.toDouble().takeIf { it > 0.0 && it < 100_000_000.0 }?.let { return it }
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

    internal fun parsePrice(value: String): Double? {
        val match = Regex("[0-9][0-9\\s\\u00A0\\u2009\\u202F.,]*").find(value) ?: return null
        var number = match.value
            .replace(" ", "")
            .replace("\u00A0", "")
            .replace("\u2009", "")
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
