package by.salesmaster.app.data

import android.webkit.CookieManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Marketplace HTTP client with cross-subdomain CookieManager synchronization,
 * bounded redirect resolution, and anti-bot challenge detection.
 */
internal object HttpJson {
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 6
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @Volatile
    var userAgent: String = DEFAULT_USER_AGENT

    private val memoryCookies = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    fun isAccessLimited(exception: Throwable): Boolean =
        generateSequence(exception) { it.cause }.any { cause ->
            val message = cause.message.orEmpty().lowercase(Locale.ROOT)
            "http 401" in message ||
                "http 403" in message ||
                "http 429" in message ||
                "http 498" in message ||
                "временно ограничила автоматические запросы" in message ||
                "страницу защиты" in message ||
                "антибот" in message
        }

    fun isAntiBotHtml(body: String): Boolean {
        val sample = body.take(4096).lowercase(Locale.ROOT)
        if ("/__wbaas/challenges" in sample || "x_wbaas_token" in sample) return true
        if ("проверяем браузер" in sample || "подозрительная активность" in sample) return true
        if ("challenge-platform" in sample || "cf-turnstile" in sample) return true
        if ("antibot" in sample || "доступ ограничен" in sample || "powchallenge" in sample) return true
        if ("\"incidentid\"" in sample || "\"incident_id\"" in sample) return true
        return false
    }

    fun get(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        expectJson: Boolean = false,
    ): String {
        val start = URL(url)
        var current = start
        val visited = mutableSetOf<String>()

        repeat(MAX_REDIRECTS + 1) { redirectNumber ->
            if (!visited.add(current.toExternalForm())) {
                throw IOException("Площадка перенаправляет запрос по кругу")
            }

            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty(
                    "Accept",
                    if (expectJson) "application/json, text/plain, */*"
                    else "application/json, text/plain, text/html, */*",
                )
                setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Connection", "close")

                if (referer != null) {
                    setRequestProperty("Referer", referer)
                    val refererUrl = runCatching { URL(referer) }.getOrNull()
                    if (refererUrl != null && sameMarketplace(refererUrl.host, current.host)) {
                        val sameOrigin = refererUrl.host.equals(current.host, ignoreCase = true)
                        setRequestProperty("Origin", "${refererUrl.protocol}://${refererUrl.host}")
                        setRequestProperty("Sec-Fetch-Site", if (sameOrigin) "same-origin" else "cross-site")
                        setRequestProperty("Sec-Fetch-Mode", "cors")
                        setRequestProperty("Sec-Fetch-Dest", "empty")
                        if (sameOrigin) {
                            setRequestProperty("X-Requested-With", "XMLHttpRequest")
                        }
                    }
                }

                extraHeaders.forEach { (key, value) ->
                    setRequestProperty(key, value)
                }

                val cookieHeader = buildCookieHeader(current)
                if (!cookieHeader.isNullOrBlank()) {
                    setRequestProperty("Cookie", cookieHeader)
                }
            }

            try {
                val status = try {
                    connection.responseCode
                } catch (exception: IOException) {
                    if (exception.message.orEmpty().contains("Unexpected status line", ignoreCase = true)) {
                        throw IOException("Площадка вернула страницу защиты вместо данных каталога", exception)
                    }
                    throw exception
                }

                captureResponseCookies(current, connection.headerFields)

                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Площадка вернула перенаправление без адреса")
                    if (redirectNumber == MAX_REDIRECTS) {
                        throw IOException("Площадка перенаправляет запрос слишком много раз")
                    }
                    val next = URL(current, location)
                    if (next.protocol != "https" || !sameMarketplace(start.host, next.host)) {
                        throw IOException("Площадка перенаправила запрос на неизвестный адрес")
                    }
                    val nextUrlStr = next.toExternalForm().lowercase(Locale.ROOT)
                    if ("abt/result" in nextUrlStr || "__rr=" in nextUrlStr || "captcha" in nextUrlStr) {
                        throw IOException("Площадка временно ограничила автоматические запросы (HTTP 403 антибот)")
                    }
                    current = next
                    return@repeat
                }

                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val detail = when (status) {
                        401, 403, 429, 498 -> "Площадка временно ограничила автоматические запросы (HTTP $status)"
                        else -> "Площадка вернула HTTP $status"
                    }
                    throw IOException(detail)
                }
                if (body.isBlank()) throw IOException("Площадка вернула пустой ответ")
                if (expectJson && (body.trimStart().startsWith("<") || isAntiBotHtml(body))) {
                    throw IOException("Площадка вернула страницу защиты вместо данных каталога (HTTP 403)")
                }
                return body
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Площадка не завершила перенаправление")
    }

    /**
     * Resolves short links (such as https://ozon.ru/t/...) to their target product/search URL.
     * Even if the final destination returns 403 to plain HTTP, the redirected URL path is preserved.
     */
    fun resolveFinalUrl(url: String): String {
        var current = runCatching { URL(url) }.getOrNull() ?: return url
        val startHost = current.host

        repeat(MAX_REDIRECTS) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9")
                val cookieHeader = buildCookieHeader(current)
                if (!cookieHeader.isNullOrBlank()) {
                    setRequestProperty("Cookie", cookieHeader)
                }
            }
            try {
                val status = runCatching { connection.responseCode }.getOrDefault(0)
                captureResponseCookies(current, connection.headerFields)
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return current.toExternalForm()
                    val next = runCatching { URL(current, location) }.getOrNull() ?: return current.toExternalForm()
                    if (next.protocol == "https" && sameMarketplace(startHost, next.host)) {
                        current = next
                        val path = current.path.orEmpty()
                        if (path.contains("/product/", ignoreCase = true) ||
                            path.contains("/catalog/", ignoreCase = true)
                        ) {
                            return current.toExternalForm()
                        }
                        return@repeat
                    }
                }
                return current.toExternalForm()
            } catch (_: Exception) {
                return current.toExternalForm()
            } finally {
                connection.disconnect()
            }
        }
        return current.toExternalForm()
    }

    fun syncCookiesFromWebView(marketplace: Marketplace) {
        val family = when (marketplace) {
            Marketplace.WILDBERRIES -> "wb"
            Marketplace.OZON -> "ozon"
        }
        val origins = when (marketplace) {
            Marketplace.WILDBERRIES -> listOf(
                "https://www.wildberries.ru/",
                "https://wildberries.ru/",
                "https://card.wb.ru/",
                "https://search.wb.ru/",
            )
            Marketplace.OZON -> listOf(
                "https://www.ozon.ru/",
                "https://ozon.ru/",
                "https://api.ozon.ru/",
            )
        }
        val map = memoryCookies.getOrPut(family) { ConcurrentHashMap() }
        runCatching {
            val cookieManager = CookieManager.getInstance()
            origins.forEach { origin ->
                val raw = cookieManager.getCookie(origin).orEmpty()
                parseCookieString(raw, map)
            }
            cookieManager.flush()
        }
    }

    internal fun putCookie(host: String, name: String, value: String) {
        val family = marketplaceFamily(host) ?: return
        if (name.isBlank() || value.isBlank()) return
        memoryCookies.getOrPut(family) { ConcurrentHashMap() }[name.trim()] = value.trim()
    }

    internal fun sameMarketplace(firstHost: String, secondHost: String): Boolean {
        val firstFamily = marketplaceFamily(firstHost)
        val secondFamily = marketplaceFamily(secondHost)
        return firstFamily != null && firstFamily == secondFamily
    }

    internal fun marketplaceFamily(host: String): String? {
        val clean = host.lowercase(Locale.ROOT).trim()
        val wbRoots = listOf("wildberries.ru", "wildberries.by", "wildberries.kz", "wb.ru", "wbbasket.ru")
        if (wbRoots.any { clean == it || clean.endsWith(".$it") }) return "wb"
        val ozonRoots = listOf("ozon.ru", "ozon.by", "ozon.kz", "ozone.ru")
        if (ozonRoots.any { clean == it || clean.endsWith(".$it") }) return "ozon"
        return null
    }

    private fun buildCookieHeader(url: URL): String? {
        val family = marketplaceFamily(url.host) ?: return null
        val combined = linkedMapOf<String, String>()

        memoryCookies[family]?.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank()) combined[k] = v
        }

        runCatching {
            val cookieManager = CookieManager.getInstance()
            val primaryOrigin = when (family) {
                "wb" -> "https://www.wildberries.ru/"
                "ozon" -> "https://www.ozon.ru/"
                else -> null
            }
            if (primaryOrigin != null) {
                parseCookieString(cookieManager.getCookie(primaryOrigin).orEmpty(), combined)
            }
            parseCookieString(cookieManager.getCookie(url.toExternalForm()).orEmpty(), combined)
        }

        if (combined.isEmpty()) return null
        return combined.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun captureResponseCookies(url: URL, headerFields: Map<String?, List<String>>) {
        val family = marketplaceFamily(url.host) ?: return
        val jar = memoryCookies.getOrPut(family) { ConcurrentHashMap() }
        headerFields.entries
            .filter { it.key != null && it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value.orEmpty() }
            .forEach { header ->
                val pair = header.substringBefore(';').split('=', limit = 2)
                if (pair.size == 2 && pair[0].isNotBlank() && pair[1].isNotBlank()) {
                    jar[pair[0].trim()] = pair[1].trim()
                }
                runCatching {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setCookie(url.toExternalForm(), header)
                    val primaryOrigin = when (family) {
                        "wb" -> "https://www.wildberries.ru"
                        "ozon" -> "https://www.ozon.ru"
                        else -> null
                    }
                    if (primaryOrigin != null) {
                        cookieManager.setCookie(primaryOrigin, header.substringBefore(';'))
                    }
                }
            }
    }

    private fun parseCookieString(raw: String, target: MutableMap<String, String>) {
        if (raw.isBlank()) return
        raw.split(';').forEach { part ->
            val pair = part.trim().split('=', limit = 2)
            if (pair.size == 2 && pair[0].isNotBlank() && pair[1].isNotBlank()) {
                target[pair[0].trim()] = pair[1].trim()
            }
        }
    }
}
