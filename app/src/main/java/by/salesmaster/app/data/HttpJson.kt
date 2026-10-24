package by.salesmaster.app.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal marketplace HTTP client with bounded, cookie-aware redirects. */
internal object HttpJson {
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 6
    private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun get(url: String, referer: String? = null): String {
        val start = URL(url)
        var current = start
        val visited = mutableSetOf<String>()
        val cookies = linkedMapOf<String, String>()

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
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json, text/plain, text/html, */*")
                setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Connection", "close")
                if (referer != null) {
                    setRequestProperty("Referer", referer)
                    val refererUrl = runCatching { URL(referer) }.getOrNull()
                    if (refererUrl != null && sameMarketplace(refererUrl.host, current.host)) {
                        setRequestProperty("Origin", "${refererUrl.protocol}://${refererUrl.host}")
                        setRequestProperty("Sec-Fetch-Site", "same-origin")
                        setRequestProperty("Sec-Fetch-Mode", "cors")
                        setRequestProperty("Sec-Fetch-Dest", "empty")
                        setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    }
                }
                if (cookies.isNotEmpty() && sameMarketplace(start.host, current.host)) {
                    setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
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

                if (status in 300..399) {
                    connection.headerFields.entries
                        .firstOrNull { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                        ?.value.orEmpty().forEach { header ->
                        val pair = header.substringBefore(';').split('=', limit = 2)
                        if (pair.size == 2 && pair[0].isNotBlank() && sameMarketplace(start.host, current.host)) {
                            cookies[pair[0].trim()] = pair[1].trim()
                        }
                    }
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Площадка вернула перенаправление без адреса")
                    if (redirectNumber == MAX_REDIRECTS) {
                        throw IOException("Площадка перенаправляет запрос слишком много раз")
                    }
                    val next = URL(current, location)
                    if (next.protocol != "https" || !sameMarketplace(start.host, next.host)) {
                        throw IOException("Площадка перенаправила запрос на неизвестный адрес")
                    }
                    current = next
                    return@repeat
                }

                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val detail = when (status) {
                        403, 429 -> "Площадка временно ограничила автоматические запросы (HTTP $status)"
                        else -> "Площадка вернула HTTP $status"
                    }
                    throw IOException(detail)
                }
                if (body.isBlank()) throw IOException("Площадка вернула пустой ответ")
                return body
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Площадка не завершила перенаправление")
    }

    private fun sameMarketplace(firstHost: String, secondHost: String): Boolean {
        val roots = listOf("ozon.ru", "wildberries.ru", "wb.ru")
        val firstRoot = roots.firstOrNull { firstHost == it || firstHost.endsWith(".$it") }
        val secondRoot = roots.firstOrNull { secondHost == it || secondHost.endsWith(".$it") }
        return firstRoot != null && firstRoot == secondRoot
    }
}
