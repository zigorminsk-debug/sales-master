package by.salesmaster.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import by.salesmaster.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class AvailableUpdate(
    val version: String,
    val downloadUrl: String,
    val releaseUrl: String,
)

internal object GithubReleaseClient {
    private const val APK_NAME = "sales-master.apk"

    fun checkForUpdate(): AvailableUpdate? {
        val endpoint = "https://api.github.com/repos/${BuildConfig.GITHUB_REPOSITORY}/releases/latest"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "sales-master-android")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.optString("tag_name", "").removePrefix("v").removePrefix("V")
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                if (asset.optString("name") == APK_NAME) {
                    apkUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                    break
                }
            }
            val downloadUrl = apkUrl ?: return null
            if (tag.isBlank() || compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) return null
            return AvailableUpdate(
                version = tag,
                downloadUrl = downloadUrl,
                releaseUrl = json.optString("html_url", "https://github.com/${BuildConfig.GITHUB_REPOSITORY}/releases/latest"),
            )
        } finally {
            connection.disconnect()
        }
    }

    fun download(update: AvailableUpdate, context: Context): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val output = File(directory, APK_NAME)
        val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "sales-master-android")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("GitHub не смог отдать APK")
            connection.inputStream.use { input ->
                output.outputStream().use { target -> input.copyTo(target) }
            }
            if (output.length() == 0L) throw IOException("GitHub вернул пустой APK")
            return output
        } catch (exception: Exception) {
            output.delete()
            throw exception
        } finally {
            connection.disconnect()
        }
    }

    fun installerIntent(context: Context, apk: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun compareVersions(first: String, second: String): Int {
        val left = Regex("\\d+").findAll(first).mapNotNull { it.value.toLongOrNull() }.toList()
        val right = Regex("\\d+").findAll(second).mapNotNull { it.value.toLongOrNull() }.toList()
        val length = maxOf(left.size, right.size)
        for (index in 0 until length) {
            val a = left.getOrElse(index) { 0L }
            val b = right.getOrElse(index) { 0L }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }
}
