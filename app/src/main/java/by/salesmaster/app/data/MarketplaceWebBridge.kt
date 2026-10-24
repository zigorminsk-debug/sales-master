package by.salesmaster.app.data

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Bridges marketplace requests through Android's built-in Chromium WebView.
 *
 * 1. Headless mode: automatically solves JS anti-bot Proof-of-Work challenges
 *    (Wildberries wbaas / x_wbaas_token and Ozon abt_data / Cloudflare), executes
 *    same-origin fetch() calls with credentials, and extracts rendered DOM product tiles.
 * 2. Interactive mode: opens an in-app WebView dialog when a marketplace requires
 *    manual CAPTCHA verification, then imports extracted prices directly into the app.
 */
internal object MarketplaceWebBridge {
    private const val HEADLESS_TIMEOUT_MS = 20_000L
    private const val POLL_INTERVAL_MS = 550L

    @Volatile
    private var appContext: Context? = null

    private val headlessLock = ReentrantLock()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val challengeFlags = ConcurrentHashMap<Marketplace, Boolean>()

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        mainHandler.post {
            runCatching {
                val rawUa = WebSettings.getDefaultUserAgent(app)
                val cleanUa = cleanWebViewUserAgent(rawUa)
                if (cleanUa.isNotBlank()) {
                    HttpJson.userAgent = cleanUa
                }
                CookieManager.getInstance().setAcceptCookie(true)
            }
        }
    }

    fun wasChallengeRequired(marketplace: Marketplace): Boolean =
        challengeFlags[marketplace] == true

    fun clearChallengeFlag(marketplace: Marketplace) {
        challengeFlags[marketplace] = false
    }

    fun fetchWildberriesSearch(query: String): List<ProductOffer> {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val pageUrl = "https://www.wildberries.ru/catalog/0/search.aspx?sort=priceup&search=$encoded"
        val apiPath = "/__internal/u-search/exactmatch/ru/common/v18/search" +
            "?ab_testing=false&appType=1&curr=rub&dest=-1257786&lang=ru&page=1" +
            "&query=$encoded&resultset=catalog&sort=priceup&spp=30&suppressSpellcheck=false"
        return runHeadless(
            marketplace = Marketplace.WILDBERRIES,
            pageUrl = pageUrl,
            scriptBuilder = { buildWildberriesScript(apiPath, expectedId = null) },
            parser = { payload -> parseWildberriesPayload(payload, expectedId = null) },
        )
    }

    fun fetchWildberriesCard(productId: String): ProductOffer? {
        val cleanId = productId.trim()
        val pageUrl = "https://www.wildberries.ru/catalog/$cleanId/detail.aspx"
        val apiPath = "/__internal/u-card/cards/v4/detail" +
            "?appType=1&curr=rub&dest=-1257786&spp=30&lang=ru&nm=$cleanId"
        return runHeadless(
            marketplace = Marketplace.WILDBERRIES,
            pageUrl = pageUrl,
            scriptBuilder = { buildWildberriesScript(apiPath, expectedId = cleanId) },
            parser = { payload -> parseWildberriesPayload(payload, expectedId = cleanId) },
        ).firstOrNull()
    }

    fun fetchOzon(targetPath: String): List<ProductOffer> {
        val normalizedPath = if (targetPath.startsWith("/")) targetPath else "/$targetPath"
        val pageUrl = "https://www.ozon.ru$normalizedPath"
        return runHeadless(
            marketplace = Marketplace.OZON,
            pageUrl = pageUrl,
            scriptBuilder = { buildOzonScript(normalizedPath) },
            parser = { payload -> parseOzonPayload(payload, normalizedPath) },
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun showInteractiveDialog(
        activity: Activity,
        marketplace: Marketplace,
        query: String,
        onOffersLoaded: (List<ProductOffer>) -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val targetInfo = resolveInteractiveTarget(marketplace, query)
        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(244, 246, 251))
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(activity, 14)
            setPadding(pad, dp(activity, 12), pad, dp(activity, 10))
            setBackgroundColor(Color.WHITE)
            elevation = dp(activity, 3).toFloat()
        }

        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(activity).apply {
            text = "${marketplace.displayName}: загрузка цен"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.rgb(24, 38, 61))
        }
        topRow.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val closeButton = TextView(activity).apply {
            text = "Закрыть"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(112, 126, 147))
            setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 4), dp(activity, 6))
        }
        topRow.addView(closeButton)
        header.addView(topRow)

        val statusView = TextView(activity).apply {
            text = "Если площадка покажет проверку («Я не робот»), пройдите её — карточки распознаются автоматически."
            textSize = 12f
            setTextColor(Color.rgb(112, 126, 147))
            setPadding(0, dp(activity, 4), 0, dp(activity, 8))
        }
        header.addView(statusView)

        val takeButton = Button(activity).apply {
            text = "Ожидаем загрузку карточек…"
            isAllCaps = false
            isEnabled = false
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(82, 95, 212))
            minHeight = dp(activity, 42)
            minimumHeight = dp(activity, 42)
        }
        header.addView(
            takeButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val webView = WebView(activity)
        configureWebView(webView, blockImages = false)
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dialog.setContentView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        var collectedOffers: List<ProductOffer> = emptyList()
        val closed = AtomicBoolean(false)

        fun finishAndClose(deliverOffers: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            HttpJson.syncCookiesFromWebView(marketplace)
            if (collectedOffers.isNotEmpty()) {
                clearChallengeFlag(marketplace)
            }
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
            runCatching {
                if (dialog.isShowing) dialog.dismiss()
            }
            if (deliverOffers && collectedOffers.isNotEmpty()) {
                onOffersLoaded(collectedOffers)
            }
        }

        closeButton.setOnClickListener { finishAndClose(deliverOffers = collectedOffers.isNotEmpty()) }
        takeButton.setOnClickListener { finishAndClose(deliverOffers = true) }
        dialog.setOnDismissListener { finishAndClose(deliverOffers = false) }

        val pollRunnable = object : Runnable {
            override fun run() {
                if (closed.get() || activity.isFinishing || activity.isDestroyed) return
                val script = when (marketplace) {
                    Marketplace.WILDBERRIES -> buildWildberriesScript(targetInfo.apiPath, targetInfo.expectedId)
                    Marketplace.OZON -> buildOzonScript(targetInfo.targetPath)
                }
                webView.evaluateJavascript(script) { rawResult ->
                    if (closed.get()) return@evaluateJavascript
                    val decoded = decodeEvaluateResult(rawResult)
                    val payload = decoded?.let { runCatching { JSONObject(it) }.getOrNull() }
                    if (payload != null) {
                        val state = payload.optString("state", "")
                        val offers = when (marketplace) {
                            Marketplace.WILDBERRIES -> parseWildberriesPayload(payload, targetInfo.expectedId)
                            Marketplace.OZON -> parseOzonPayload(payload, targetInfo.targetPath)
                        }
                        if (offers.isNotEmpty()) {
                            collectedOffers = offers.sortedBy { it.price }.take(30)
                            HttpJson.syncCookiesFromWebView(marketplace)
                            clearChallengeFlag(marketplace)
                            statusView.text = "Найдено предложений с ценами: ${collectedOffers.size}. Нажмите кнопку ниже, чтобы перенести их в список."
                            statusView.setTextColor(Color.rgb(22, 134, 110))
                            takeButton.isEnabled = true
                            takeButton.text = "Забрать найденные цены (${collectedOffers.size})"
                        } else if (state == "captcha" || state == "challenge") {
                            statusView.text = "Площадка проверяет браузер. Если видите кнопку подтверждения или капчу — нажмите на неё."
                        }
                    }
                    if (!closed.get()) {
                        mainHandler.postDelayed(this, 900L)
                    }
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                HttpJson.syncCookiesFromWebView(marketplace)
            }
        }

        dialog.show()
        webView.loadUrl(targetInfo.pageUrl)
        mainHandler.postDelayed(pollRunnable, 1_000L)
    }

    private fun runHeadless(
        marketplace: Marketplace,
        pageUrl: String,
        scriptBuilder: () -> String,
        parser: (JSONObject) -> List<ProductOffer>,
    ): List<ProductOffer> {
        val context = appContext ?: return emptyList()
        // Never block the main thread if called from UI thread by mistake
        if (Looper.myLooper() == Looper.getMainLooper()) return emptyList()

        if (!headlessLock.tryLock(HEADLESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return emptyList()
        }
        try {
            val latch = CountDownLatch(1)
            val completed = AtomicBoolean(false)
            var resultOffers: List<ProductOffer> = emptyList()
            var webViewRef: WebView? = null
            val startTime = System.currentTimeMillis()

            fun complete(offers: List<ProductOffer>, challengeDetected: Boolean) {
                if (!completed.compareAndSet(false, true)) return
                challengeFlags[marketplace] = challengeDetected && offers.isEmpty()
                resultOffers = offers
                HttpJson.syncCookiesFromWebView(marketplace)
                mainHandler.post {
                    runCatching {
                        webViewRef?.stopLoading()
                        webViewRef?.loadUrl("about:blank")
                        webViewRef?.destroy()
                        webViewRef = null
                    }
                }
                latch.countDown()
            }

            mainHandler.post {
                try {
                    val webView = WebView(context)
                    webViewRef = webView
                    configureWebView(webView, blockImages = true)

                    val pollRunnable = object : Runnable {
                        override fun run() {
                            if (completed.get()) return
                            val elapsed = System.currentTimeMillis() - startTime
                            if (elapsed >= HEADLESS_TIMEOUT_MS) {
                                complete(emptyList(), challengeDetected = false)
                                return
                            }
                            val currentView = webViewRef ?: run {
                                complete(emptyList(), challengeDetected = false)
                                return
                            }
                            currentView.evaluateJavascript(scriptBuilder()) { raw ->
                                if (completed.get()) return@evaluateJavascript
                                val decoded = decodeEvaluateResult(raw)
                                val payload = decoded?.let { runCatching { JSONObject(it) }.getOrNull() }
                                if (payload != null) {
                                    val state = payload.optString("state", "")
                                    val offers = runCatching { parser(payload) }.getOrDefault(emptyList())
                                    if (offers.isNotEmpty()) {
                                        complete(offers.sortedBy { it.price }.take(30), challengeDetected = false)
                                        return@evaluateJavascript
                                    }
                                    if (state == "captcha" && elapsed > 4_500L) {
                                        complete(emptyList(), challengeDetected = true)
                                        return@evaluateJavascript
                                    }
                                    if (state == "empty" && elapsed > 6_000L) {
                                        complete(emptyList(), challengeDetected = false)
                                        return@evaluateJavascript
                                    }
                                }
                                if (!completed.get()) {
                                    mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                                }
                            }
                        }
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString()?.lowercase().orEmpty()
                            if (shouldSkipHeavyResource(reqUrl)) {
                                return WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    ByteArrayInputStream(ByteArray(0)),
                                )
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            HttpJson.syncCookiesFromWebView(marketplace)
                        }
                    }

                    webView.loadUrl(pageUrl)
                    mainHandler.postDelayed(pollRunnable, 750L)
                } catch (_: Throwable) {
                    complete(emptyList(), challengeDetected = false)
                }
            }

            latch.await(HEADLESS_TIMEOUT_MS + 1_500L, TimeUnit.MILLISECONDS)
            return resultOffers
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return emptyList()
        } finally {
            headlessLock.unlock()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView, blockImages: Boolean) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = !blockImages
            blockNetworkImage = blockImages
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            val cleanedUa = cleanWebViewUserAgent(userAgentString)
            if (cleanedUa.isNotBlank()) {
                userAgentString = cleanedUa
                HttpJson.userAgent = cleanedUa
            }
        }
    }

    internal fun cleanWebViewUserAgent(rawUa: String?): String {
        val base = rawUa.orEmpty().trim()
        if (base.isBlank()) return HttpJson.userAgent
        return base
            .replace("; wv)", ")")
            .replace("; wv;", ";")
            .replace(Regex("\\bVersion/\\d+\\.\\d+\\s*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun shouldSkipHeavyResource(url: String): Boolean {
        if (url.isBlank()) return false
        if (url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg") ||
            url.endsWith(".webp") || url.endsWith(".gif") || url.endsWith(".mp4") ||
            url.endsWith(".woff2") || url.endsWith(".woff") || url.endsWith(".ttf")
        ) {
            return true
        }
        val trackers = listOf(
            "mc.yandex.ru",
            "google-analytics.com",
            "googletagmanager.com",
            "doubleclick.net",
            "vk.com/rtrg",
            "top-fwz1.mail.ru",
        )
        return trackers.any { it in url }
    }

    private data class InteractiveTarget(
        val pageUrl: String,
        val apiPath: String,
        val targetPath: String,
        val expectedId: String?,
    )

    private fun resolveInteractiveTarget(marketplace: Marketplace, query: String): InteractiveTarget {
        return when (marketplace) {
            Marketplace.WILDBERRIES -> {
                val id = WildberriesClient.extractProductId(query)
                if (id != null) {
                    InteractiveTarget(
                        pageUrl = "https://www.wildberries.ru/catalog/$id/detail.aspx",
                        apiPath = "/__internal/u-card/cards/v4/detail?appType=1&curr=rub&dest=-1257786&spp=30&lang=ru&nm=$id",
                        targetPath = "/catalog/$id/detail.aspx",
                        expectedId = id,
                    )
                } else {
                    val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
                    InteractiveTarget(
                        pageUrl = "https://www.wildberries.ru/catalog/0/search.aspx?sort=priceup&search=$encoded",
                        apiPath = "/__internal/u-search/exactmatch/ru/common/v18/search?ab_testing=false&appType=1&curr=rub&dest=-1257786&lang=ru&page=1&query=$encoded&resultset=catalog&sort=priceup&spp=30&suppressSpellcheck=false",
                        targetPath = "/catalog/0/search.aspx?sort=priceup&search=$encoded",
                        expectedId = null,
                    )
                }
            }
            Marketplace.OZON -> {
                val path = OzonClient.extractTargetPath(query)
                    ?: "/search/?text=${Uri.encode(query.trim())}&from_global=true&sorting=price"
                InteractiveTarget(
                    pageUrl = "https://www.ozon.ru$path",
                    apiPath = path,
                    targetPath = path,
                    expectedId = null,
                )
            }
        }
    }

    internal fun decodeEvaluateResult(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return null
        if (trimmed.startsWith("\"")) {
            return runCatching { JSONTokener(trimmed).nextValue() as? String }.getOrNull()
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed
        }
        return null
    }

    private fun parseWildberriesPayload(payload: JSONObject, expectedId: String?): List<ProductOffer> {
        val apiJson = payload.optString("apiJson", "").trim()
        if (apiJson.startsWith("{")) {
            val apiOffers = runCatching { WildberriesClient.parseProductsFromJson(apiJson) }.getOrDefault(emptyList())
            if (apiOffers.isNotEmpty()) return apiOffers
        }

        val domArray = payload.optJSONArray("domOffers") ?: return emptyList()
        return buildList {
            for (i in 0 until domArray.length()) {
                val item = domArray.optJSONObject(i) ?: continue
                val id = item.optString("id", "").trim().ifBlank { expectedId.orEmpty() }
                if (id.isBlank()) continue
                val price = item.optDouble("price", 0.0)
                if (price <= 0.0 || price.isNaN()) continue
                val rawTitle = item.optString("title", "").trim()
                val title = rawTitle
                    .replace(Regex("\\s+"), " ")
                    .removeSuffix("- купить в интернет-магазине Wildberries")
                    .trim()
                    .ifBlank { "Товар Wildberries $id" }
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
    }

    private fun parseOzonPayload(payload: JSONObject, fallbackPath: String): List<ProductOffer> {
        val apiJson = payload.optString("apiJson", "").trim()
        if (apiJson.startsWith("{")) {
            val apiOffers = runCatching { OzonOfferParser.parseResponse(apiJson, fallbackPath) }.getOrDefault(emptyList())
            if (apiOffers.isNotEmpty()) return apiOffers
        }

        val ldJsonArray = payload.optJSONArray("ldJson")
        if (ldJsonArray != null) {
            for (i in 0 until ldJsonArray.length()) {
                val ldText = ldJsonArray.optString(i, "")
                val ldOffers = runCatching { OzonOfferParser.parseResponse(ldText, fallbackPath) }.getOrDefault(emptyList())
                if (ldOffers.isNotEmpty()) return ldOffers
            }
        }

        val domArray = payload.optJSONArray("domOffers") ?: return emptyList()
        return buildList {
            for (i in 0 until domArray.length()) {
                val item = domArray.optJSONObject(i) ?: continue
                val price = item.optDouble("price", 0.0)
                if (price <= 0.0 || price.isNaN()) continue
                val rawUrl = item.optString("url", "").trim().ifBlank { fallbackPath }
                val canonicalUrl = when {
                    rawUrl.startsWith("https://", ignoreCase = true) -> rawUrl.substringBefore('?')
                    rawUrl.startsWith("http://", ignoreCase = true) -> rawUrl.replaceFirst("http://", "https://", ignoreCase = true).substringBefore('?')
                    rawUrl.startsWith("/") -> "https://www.ozon.ru${rawUrl.substringBefore('?')}"
                    else -> continue
                }
                if (!canonicalUrl.contains("ozon.", ignoreCase = true)) continue
                val id = item.optString("id", "").takeIf { it.isNotBlank() }
                    ?: Regex("(?:-|/)(\\d{5,})(?:/|$|\\?)").find(canonicalUrl)?.groupValues?.getOrNull(1)
                val title = item.optString("title", "")
                    .replace(Regex("\\s+"), " ")
                    .replace(Regex("(?i)\\s*[—\\-|]\\s*купить на ozon.*$"), "")
                    .trim()
                    .ifBlank { if (id != null) "Товар Ozon $id" else continue }
                add(
                    ProductOffer(
                        marketplace = Marketplace.OZON,
                        title = title,
                        price = price,
                        productUrl = canonicalUrl,
                        externalId = id,
                    ),
                )
            }
        }
    }

    private fun buildWildberriesScript(apiPath: String, expectedId: String?): String {
        val escapedApiPath = JSONObject.quote(apiPath)
        val escapedId = JSONObject.quote(expectedId.orEmpty())
        return """
        (function() {
            try {
                const clean = s => (s || '').replace(/\s+/g, ' ').trim();
                const parseRub = text => {
                    if (!text) return 0;
                    const m = String(text).match(/(?<![\d.,-])((?:\d{1,3}(?:[\s\u00a0\u2009\u202f]\d{3})+|\d+)(?:[.,]\d{1,2})?)[\s\u00a0\u2009\u202f]*₽/);
                    if (!m) return 0;
                    const idx = m.index || 0;
                    const prefix = String(text).slice(Math.max(0, idx - 3), idx).trim();
                    if (prefix.endsWith('-') || prefix.endsWith('−') || prefix.endsWith('–')) return 0;
                    const raw = m[1].replace(/[\s\u00a0\u2009\u202f]/g, '').replace(',', '.');
                    const val = parseFloat(raw);
                    return (val > 0 && val < 100000000) ? val : 0;
                };

                const bodyText = (document.body && document.body.innerText) ? document.body.innerText.slice(0, 5000) : '';
                const hay = ((document.title || '') + '\n' + bodyText).toLowerCase();

                let state = 'loading';
                if (/подозрительная активность|введите символы с картинки|я не робот/.test(hay)) {
                    state = 'captcha';
                } else if (/проверяем браузер|проверка браузера|checking your browser/.test(hay)) {
                    state = 'challenge';
                } else if (/ничего не нашлось|по вашему запросу ничего не найдено|товар не найден/.test(hay)) {
                    state = 'empty';
                }

                const apiPath = $escapedApiPath;
                const expectedId = $escapedId;
                if (location.origin.indexOf('wildberries.') >= 0 && state !== 'challenge' && state !== 'captcha') {
                    if (!window.__sm_wb_fetching && !window.__sm_wb_api) {
                        window.__sm_wb_fetching = true;
                        fetch(apiPath, {
                            method: 'GET',
                            credentials: 'include',
                            headers: {
                                'Accept': '*/*',
                                'Accept-Language': 'ru-RU,ru;q=0.9',
                                'x-requested-with': 'XMLHttpRequest',
                                'x-spa-version': '14.28.4'
                            }
                        }).then(r => r.ok ? r.text() : '').then(t => {
                            if (t && t.trim().startsWith('{')) window.__sm_wb_api = t;
                            window.__sm_wb_fetching = false;
                        }).catch(() => { window.__sm_wb_fetching = false; });
                    }
                }

                const domOffers = [];
                const seen = new Set();

                // 1. Search / catalog tiles
                const tiles = document.querySelectorAll('[data-nm-id], article.product-card, div.product-card');
                for (const el of tiles) {
                    let nm = el.getAttribute('data-nm-id') || '';
                    const linkEl = el.querySelector('a[href*="/catalog/"]');
                    if (!nm && linkEl) {
                        const m = (linkEl.getAttribute('href') || '').match(/\/catalog\/(\d+)/);
                        if (m) nm = m[1];
                    }
                    if (!nm || seen.has(nm)) continue;

                    let price = 0;
                    const priceSelectors = [
                        '[data-testid="product-card-current-price"]',
                        '.price__lower-price',
                        'ins.price__lower-price',
                        '[class*="lower-price"]',
                        'ins'
                    ];
                    for (const sel of priceSelectors) {
                        const pEl = el.querySelector(sel);
                        if (pEl) {
                            price = parseRub(pEl.innerText || pEl.textContent);
                            if (price > 0) break;
                        }
                    }
                    if (price <= 0) {
                        const lines = (el.innerText || '').split('\n');
                        for (const line of lines) {
                            if (line.indexOf('₽') >= 0 && line.indexOf('/мес') < 0) {
                                price = parseRub(line);
                                if (price > 0) break;
                            }
                        }
                    }
                    if (price <= 0) continue;

                    let title = '';
                    const brandEl = el.querySelector('.product-card__brand, [class*="product-card__brand"]');
                    const nameEl = el.querySelector('.product-card__name, [class*="product-card__name"]');
                    const brand = brandEl ? clean(brandEl.innerText) : '';
                    const name = nameEl ? clean(nameEl.innerText).replace(/^[\/|\\s]+/, '') : '';
                    if (brand && name) title = brand + ' ' + name;
                    else if (name) title = name;
                    else if (linkEl && linkEl.getAttribute('aria-label')) title = clean(linkEl.getAttribute('aria-label'));
                    if (!title) {
                        const lines = (el.innerText || '').split('\n').map(clean).filter(Boolean);
                        for (const line of lines) {
                            if (line.indexOf('₽') < 0 && line.length >= 4 && !/^\d([.,]\d)?$/.test(line) && !/оцен|отзыв|завтра|сегодня|послезавтра|корзин|кошел/i.test(line)) {
                                title = line;
                                break;
                            }
                        }
                    }
                    if (title) {
                        seen.add(nm);
                        domOffers.push({ id: nm, title: title, price: price });
                        if (domOffers.length >= 35) break;
                    }
                }

                // 2. Product detail card page
                if (domOffers.length === 0) {
                    const urlMatch = location.href.match(/\/catalog\/(\d+)/);
                    const cardId = expectedId || (urlMatch ? urlMatch[1] : '');
                    if (cardId) {
                        let cardPrice = 0;
                        const cardPriceSels = [
                            '[class*="priceBlockFinalPrice"]',
                            '[class*="priceBlockWalletPrice"]',
                            '.price-block__final-price',
                            '.price-block__wallet-price',
                            '[class*="priceBlockPrice"] ins',
                            '[data-testid="product-card-current-price"]'
                        ];
                        for (const sel of cardPriceSels) {
                            const nodes = document.querySelectorAll(sel);
                            for (const n of nodes) {
                                // Ignore "Смотрите также" carousel
                                if (n.closest('[class*="carousel" i], [class*="recommend" i], [class*="seeAlso" i]')) continue;
                                cardPrice = parseRub(n.innerText || n.textContent);
                                if (cardPrice > 0) break;
                            }
                            if (cardPrice > 0) break;
                        }
                        if (cardPrice <= 0) {
                            const mainEl = document.querySelector('main') || document.body;
                            const lines = ((mainEl && mainEl.innerText) || '').split('\n').slice(0, 80);
                            for (const line of lines) {
                                if (line.indexOf('₽') >= 0 && line.indexOf('/мес') < 0) {
                                    cardPrice = parseRub(line);
                                    if (cardPrice > 0) break;
                                }
                            }
                        }
                        if (cardPrice > 0) {
                            let cardTitle = '';
                            for (const h of document.querySelectorAll('h1')) {
                                const t = clean(h.innerText);
                                if (t.length >= 3 && !/^\d([.,]\d)?$/.test(t)) {
                                    cardTitle = t;
                                    break;
                                }
                            }
                            if (!cardTitle) cardTitle = clean(document.title || '');
                            domOffers.push({ id: cardId, title: cardTitle || ('Товар Wildberries ' + cardId), price: cardPrice });
                        }
                    }
                }

                if (domOffers.length > 0 || window.__sm_wb_api) {
                    state = 'ready';
                }

                return JSON.stringify({
                    state: state,
                    apiJson: window.__sm_wb_api || '',
                    domOffers: domOffers
                });
            } catch (e) {
                return JSON.stringify({ state: 'error', error: String(e), domOffers: [] });
            }
        })();
        """.trimIndent()
    }

    private fun buildOzonScript(targetPath: String): String {
        val escapedTargetPath = JSONObject.quote(targetPath)
        return """
        (function() {
            try {
                const clean = s => (s || '').replace(/\s+/g, ' ').trim();
                const parseRub = text => {
                    if (!text) return 0;
                    const m = String(text).match(/(?<![\d.,-])((?:\d{1,3}(?:[\s\u00a0\u2009\u202f]\d{3})+|\d+)(?:[.,]\d{1,2})?)[\s\u00a0\u2009\u202f]*₽/);
                    if (!m) return 0;
                    const idx = m.index || 0;
                    const prefix = String(text).slice(Math.max(0, idx - 3), idx).trim();
                    if (prefix.endsWith('-') || prefix.endsWith('−') || prefix.endsWith('–')) return 0;
                    const raw = m[1].replace(/[\s\u00a0\u2009\u202f]/g, '').replace(',', '.');
                    const val = parseFloat(raw);
                    return (val > 0 && val < 100000000) ? val : 0;
                };

                const bodyText = (document.body && document.body.innerText) ? document.body.innerText.slice(0, 5000) : '';
                const hay = ((document.title || '') + '\n' + bodyText).toLowerCase();

                let state = 'loading';
                if (/доступ ограничен|подтвердите, что вы не робот|cf-turnstile|incident/.test(hay)) {
                    state = 'captcha';
                } else if (/antibot|checking your browser|challenge-platform|powchallenge/.test(hay)) {
                    state = 'challenge';
                } else if (/простите, по вашему запросу товаров сейчас нет|ничего не нашлось/.test(hay)) {
                    state = 'empty';
                }

                const targetPath = $escapedTargetPath;
                if (location.origin.indexOf('ozon.') >= 0 && state !== 'challenge' && state !== 'captcha') {
                    if (!window.__sm_ozon_fetching && !window.__sm_ozon_api) {
                        window.__sm_ozon_fetching = true;
                        const apiUrl = '/api/entrypoint-api.bx/page/json/v2?url=' + encodeURIComponent(targetPath);
                        fetch(apiUrl, {
                            method: 'GET',
                            credentials: 'include',
                            headers: {
                                'Accept': 'application/json, text/plain, */*',
                                'x-o3-app-name': 'dweb_client',
                                'x-o3-app-version': 'release_2-7-1'
                            }
                        }).then(r => r.ok ? r.text() : '').then(t => {
                            if (t && t.trim().startsWith('{') && t.indexOf('widgetStates') >= 0) {
                                window.__sm_ozon_api = t;
                            }
                            window.__sm_ozon_fetching = false;
                        }).catch(() => { window.__sm_ozon_fetching = false; });
                    }
                }

                const ldJson = [];
                for (const s of document.querySelectorAll('script[type="application/ld+json"]')) {
                    const txt = (s.textContent || '').trim();
                    if (txt) ldJson.push(txt);
                }

                const domOffers = [];
                const seenUrls = new Set();

                // 1. Single product page check
                if (location.pathname.indexOf('/product/') === 0) {
                    const h1 = document.querySelector('h1');
                    const h1Text = h1 ? clean(h1.innerText) : '';
                    let cardPrice = 0;
                    const priceWidgets = document.querySelectorAll('[data-widget="webPrice"], [data-widget="webSale"], [data-widget*="Price"]');
                    for (const w of priceWidgets) {
                        cardPrice = parseRub(w.innerText || w.textContent);
                        if (cardPrice > 0) break;
                    }
                    if (cardPrice > 0 && h1Text.length >= 3) {
                        const cleanPath = location.pathname;
                        seenUrls.add(cleanPath);
                        domOffers.push({
                            url: cleanPath,
                            title: h1Text,
                            price: cardPrice
                        });
                    }
                }

                // 2. Search / catalog grid tiles
                const normHref = a => {
                    const raw = a.getAttribute('href') || '';
                    if (!raw) return '';
                    const path = raw.split('?')[0];
                    if (path.indexOf('/product/') < 0 || path.indexOf('/reviews') >= 0) return '';
                    return path;
                };

                const allLinks = [...document.querySelectorAll('a[href*="/product/"]')];
                for (const a of allLinks) {
                    const path = normHref(a);
                    if (!path || seenUrls.has(path)) continue;

                    let card = a;
                    while (card.parentElement && card.parentElement !== document.body) {
                        const parentLinks = new Set(
                            [...card.parentElement.querySelectorAll('a[href*="/product/"]')]
                                .map(normHref)
                                .filter(Boolean)
                        );
                        if (parentLinks.size > 1) break;
                        card = card.parentElement;
                    }

                    const lines = (card.innerText || '').split('\n').map(clean).filter(Boolean);
                    let price = 0;
                    for (const line of lines) {
                        if (line.indexOf('₽') >= 0 && line.indexOf('/мес') < 0 && line.indexOf('балл') < 0) {
                            price = parseRub(line);
                            if (price > 0) break;
                        }
                    }
                    if (price <= 0) continue;

                    let title = '';
                    const sameCardLinks = [...card.querySelectorAll('a[href*="/product/"]')];
                    for (const link of sameCardLinks) {
                        const t = clean(link.innerText);
                        if (t.length >= 5 && t.indexOf('₽') < 0 && !/отзыв|оцен|купить|завтра|сегодня/i.test(t)) {
                            title = t;
                            break;
                        }
                    }
                    if (!title) {
                        for (const line of lines) {
                            if (line.length >= 6 && line.indexOf('₽') < 0 && !/^\d([.,]\d)?$/.test(line) &&
                                !/распродаж|скидк|балл|отзыв|оцен|завтра|сегодня|послезавтра|доставк|корзин|озон карт|ozon карт/i.test(line)) {
                                title = line;
                                break;
                            }
                        }
                    }
                    if (title) {
                        seenUrls.add(path);
                        domOffers.push({ url: path, title: title, price: price });
                        if (domOffers.length >= 35) break;
                    }
                }

                if (domOffers.length > 0 || window.__sm_ozon_api || ldJson.length > 0) {
                    state = 'ready';
                }

                return JSON.stringify({
                    state: state,
                    apiJson: window.__sm_ozon_api || '',
                    ldJson: ldJson,
                    domOffers: domOffers
                });
            } catch (e) {
                return JSON.stringify({ state: 'error', error: String(e), domOffers: [] });
            }
        })();
        """.trimIndent()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
