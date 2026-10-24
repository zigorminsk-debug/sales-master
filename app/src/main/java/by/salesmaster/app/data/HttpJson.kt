package by.salesmaster.app.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal object HttpJson {
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    fun get(url: String, referer: String? = null): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7")
            setRequestProperty("Cache-Control", "no-cache")
            if (referer != null) setRequestProperty("Referer", referer)
        }

        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IOException("HTTP $status при обращении к площадке")
            }
            if (body.isBlank()) throw IOException("Площадка вернула пустой ответ")
            return body
        } finally {
            connection.disconnect()
        }
    }
}
