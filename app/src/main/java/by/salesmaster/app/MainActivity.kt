package by.salesmaster.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import by.salesmaster.app.data.Marketplace
import by.salesmaster.app.data.MarketplaceRepository
import by.salesmaster.app.data.ProductOffer
import by.salesmaster.app.data.ProductStore
import by.salesmaster.app.data.SearchFailure
import by.salesmaster.app.data.TrackedProduct
import by.salesmaster.app.data.formatRubles
import by.salesmaster.app.monitor.MonitorScheduler
import by.salesmaster.app.monitor.PriceMonitor
import by.salesmaster.app.update.AvailableUpdate
import by.salesmaster.app.update.GithubReleaseClient
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val ioExecutor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val store by lazy { ProductStore(applicationContext) }
    private val repository by lazy { MarketplaceRepository() }
    private val selectedMarkets = linkedSetOf(Marketplace.WILDBERRIES, Marketplace.OZON)

    private lateinit var queryInput: EditText
    private lateinit var targetInput: EditText
    private lateinit var marketChipRow: LinearLayout
    private lateinit var searchButton: Button
    private lateinit var resultsCard: LinearLayout
    private lateinit var resultsContainer: LinearLayout
    private lateinit var watchesContainer: LinearLayout
    private lateinit var checkNowButton: Button
    private lateinit var updateCard: LinearLayout
    private lateinit var updateDescription: TextView
    private lateinit var updateButton: Button
    private lateinit var versionFooter: TextView

    private var availableUpdate: AvailableUpdate? = null
    private var pendingUpdateApk: File? = null
    private var lastSearchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        buildScreen()
        renderWatchList()
        MonitorScheduler.scheduleDaily(applicationContext)
        requestNotificationPermissionIfNeeded()
        checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
        val apk = pendingUpdateApk
        if (apk != null && GithubReleaseClient.canInstallPackages(this)) {
            pendingUpdateApk = null
            launchInstaller(apk)
        }
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildScreen() {
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(COLOR_BACKGROUND)
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(34))
        }
        scrollView.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scrollView)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(18))
        }
        val eyebrow = text("ЦЕНЫ ПОД КОНТРОЛЕМ", 11f, COLOR_ACCENT, bold = true)
        eyebrow.letterSpacing = 0.12f
        header.addView(eyebrow)
        header.addView(text("ЦенаРадар", 30f, COLOR_INK, bold = true).apply {
            setPadding(0, dp(5), 0, 0)
        })
        header.addView(text("Находите выгодные предложения на Ozon и Wildberries — и узнавайте, когда цена снижается.", 14f, COLOR_MUTED).apply {
            setPadding(0, dp(7), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        content.addView(header)

        buildUpdateCard(content)
        buildSearchCard(content)
        buildResultsCard(content)
        buildWatchCard(content)

        versionFooter = text("Версия ${BuildConfig.VERSION_NAME} · авто-проверка обновлений GitHub", 12f, COLOR_MUTED)
        versionFooter.gravity = Gravity.CENTER
        versionFooter.setPadding(dp(8), dp(4), dp(8), dp(4))
        content.addView(versionFooter, matchWrap())
    }

    private fun buildUpdateCard(parent: LinearLayout) {
        updateCard = card().apply {
            visibility = View.GONE
            setBackground(rounded(COLOR_UPDATE_BG, dp(20).toFloat()))
        }
        updateCard.addView(text("Доступна новая версия", 17f, COLOR_INK, bold = true))
        updateDescription = text("", 13f, COLOR_MUTED).apply { setPadding(0, dp(5), 0, dp(11)) }
        updateCard.addView(updateDescription)
        updateButton = actionButton("Обновить", COLOR_ACCENT)
        updateButton.setOnClickListener { downloadAndInstallUpdate() }
        updateCard.addView(updateButton, matchWrap())
        parent.addView(updateCard, cardParams())
    }

    private fun buildSearchCard(parent: LinearLayout) {
        val panel = card()
        panel.addView(text("Найти товар", 19f, COLOR_INK, bold = true))
        panel.addView(text("Введите название или вставьте ссылку на товар", 13f, COLOR_MUTED).apply {
            setPadding(0, dp(4), 0, dp(11))
        })

        queryInput = input("Например, робот-пылесос или ссылка", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        panel.addView(queryInput, matchWrap())

        panel.addView(text("Площадки", 12f, COLOR_MUTED, bold = true).apply {
            setPadding(0, dp(13), 0, dp(7))
        })
        marketChipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        panel.addView(marketChipRow, matchWrap())
        renderMarketChips()

        targetInput = input("Не выше, ₽ (необязательно)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        targetInput.setHintTextColor(COLOR_MUTED)
        targetInput.setPadding(dp(14), dp(12), dp(14), dp(12))
        val targetParams = matchWrap().apply { topMargin = dp(13) }
        panel.addView(targetInput, targetParams)
        panel.addView(text("Если оставить поле пустым, уведомим о снижении цены после первого замера. Задайте порог — и уведомление придёт, когда цена опустится до него.", 12f, COLOR_MUTED).apply {
            setPadding(dp(2), dp(7), dp(2), dp(12))
            setLineSpacing(dp(2).toFloat(), 1f)
        })

        searchButton = actionButton("Найти самые низкие цены", COLOR_ACCENT)
        searchButton.setOnClickListener { startSearch() }
        panel.addView(searchButton, matchWrap())
        parent.addView(panel, cardParams())
    }

    private fun buildResultsCard(parent: LinearLayout) {
        resultsCard = card().apply { visibility = View.GONE }
        resultsCard.addView(text("Результаты", 19f, COLOR_INK, bold = true))
        resultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        resultsCard.addView(resultsContainer, matchWrap())
        parent.addView(resultsCard, cardParams())
    }

    private fun buildWatchCard(parent: LinearLayout) {
        val panel = card()
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = text("Под наблюдением", 19f, COLOR_INK, bold = true)
        heading.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        checkNowButton = actionButton("Проверить", COLOR_ACCENT)
        checkNowButton.textSize = 12f
        checkNowButton.minHeight = dp(40)
        checkNowButton.setPadding(dp(11), 0, dp(11), 0)
        checkNowButton.setOnClickListener { checkPricesNow() }
        heading.addView(checkNowButton)
        panel.addView(heading)
        panel.addView(text("Проверяем примерно раз в сутки в фоне. Android может сдвигать время фоновой проверки.", 12f, COLOR_MUTED).apply {
            setPadding(0, dp(5), 0, dp(10))
        })

        watchesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(watchesContainer, matchWrap())
        parent.addView(panel, cardParams())
    }

    private fun renderMarketChips() {
        marketChipRow.removeAllViews()
        Marketplace.entries.forEach { market ->
            val selected = market in selectedMarkets
            val chip = TextView(this).apply {
                text = market.displayName
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(dp(15), dp(10), dp(15), dp(10))
                setTextColor(if (selected) Color.WHITE else COLOR_INK)
                background = rounded(if (selected) COLOR_ACCENT else COLOR_CHIP, dp(40).toFloat())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (market in selectedMarkets) {
                        if (selectedMarkets.size == 1) {
                            toast("Выберите хотя бы одну площадку")
                            return@setOnClickListener
                        }
                        selectedMarkets.remove(market)
                    } else {
                        selectedMarkets.add(market)
                    }
                    renderMarketChips()
                }
            }
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.rightMargin = dp(8)
            marketChipRow.addView(chip, params)
        }
    }

    private fun startSearch() {
        val query = queryInput.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            toast("Введите название товара или ссылку")
            queryInput.requestFocus()
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(queryInput.windowToken, 0)
        queryInput.clearFocus()
        lastSearchQuery = query
        val markets = selectedMarkets.toSet()
        searchButton.isEnabled = false
        searchButton.text = "Ищем…"
        resultsCard.visibility = View.VISIBLE
        resultsContainer.removeAllViews()
        resultsContainer.addView(text("Загружаем предложения…", 14f, COLOR_MUTED))

        ioExecutor.execute {
            val response = repository.search(query, markets)
            mainHandler.post {
                searchButton.isEnabled = true
                searchButton.text = "Найти самые низкие цены"
                renderResults(response.offers, response.errors)
            }
        }
    }

    private fun renderResults(offers: List<ProductOffer>, errors: List<SearchFailure>) {
        resultsContainer.removeAllViews()
        if (offers.isEmpty()) {
            val message = if (errors.isEmpty()) {
                "Ничего не найдено. Попробуйте изменить запрос."
            } else {
                "Не удалось автоматически загрузить цены. Площадки могут временно ограничивать запросы. Откройте выдачу напрямую:"
            }
            resultsContainer.addView(text(message, 14f, COLOR_MUTED).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        } else {
            resultsContainer.addView(text("Найдено: ${offers.size}. Сначала показаны варианты с меньшей ценой.", 12f, COLOR_MUTED).apply {
                setPadding(0, 0, 0, dp(8))
            })
            offers.take(20).forEach { offer -> resultsContainer.addView(offerView(offer), matchWrap().apply { bottomMargin = dp(9) }) }
        }
        if (errors.isNotEmpty()) {
            val message = errors.distinctBy { it.marketplace }
                .joinToString("\n") { "${it.marketplace.displayName}: ${it.message}" }
            resultsContainer.addView(text("\n${if (offers.isEmpty()) "Статус площадок:" else "Часть площадок не ответила:"}\n$message", 12f, COLOR_MUTED).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            val failedMarkets = errors.map { it.marketplace }.distinct()
            failedMarkets.forEach { market ->
                val button = actionButton("Открыть поиск: ${market.displayName}", COLOR_ACCENT)
                button.textSize = 12f
                button.minHeight = dp(42)
                button.setOnClickListener { openMarketplaceSearch(market, lastSearchQuery) }
                resultsContainer.addView(button, matchWrap().apply { topMargin = dp(7) })
            }
        }
    }

    private fun offerView(offer: ProductOffer): View {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(13), dp(13), dp(12))
            background = rounded(COLOR_ITEM, dp(16).toFloat())
        }
        val badge = text(offer.marketplace.displayName.uppercase(), 10f, COLOR_ACCENT, bold = true)
        badge.letterSpacing = 0.06f
        item.addView(badge)
        item.addView(text(offer.title, 14f, COLOR_INK, bold = true).apply {
            setPadding(0, dp(5), 0, dp(5))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        item.addView(text(formatRubles(offer.price), 22f, COLOR_GREEN, bold = true))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val follow = actionButton("Следить за ценой", COLOR_ACCENT)
        follow.textSize = 12f
        follow.minHeight = dp(40)
        follow.setPadding(dp(10), 0, dp(10), 0)
        follow.setOnClickListener {
            val target = targetPriceFromInput() ?: if (targetInput.text?.isNotBlank() == true) {
                toast("Проверьте цену-порог")
                return@setOnClickListener
            } else null
            if (store.addFromOffer(offer, target)) {
                toast("Товар добавлен в мониторинг")
                renderWatchList()
            } else {
                toast("Этот товар уже отслеживается")
            }
        }
        actions.addView(follow)
        val open = TextView(this).apply {
            text = "Открыть"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(COLOR_ACCENT)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(5), dp(10))
            setOnClickListener { openProduct(offer.productUrl) }
        }
        actions.addView(open)
        item.addView(actions, matchWrap())
        return item
    }

    private fun renderWatchList() {
        if (!::watchesContainer.isInitialized) return
        watchesContainer.removeAllViews()
        val watches = store.all()
        if (watches.isEmpty()) {
            watchesContainer.addView(text("Добавьте товар из результатов поиска, чтобы начать следить за ценой.", 13f, COLOR_MUTED).apply {
                setPadding(0, dp(7), 0, dp(2))
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            return
        }
        watches.forEachIndexed { index, product ->
            watchesContainer.addView(watchView(product), matchWrap().apply {
                if (index < watches.lastIndex) bottomMargin = dp(9)
            })
        }
    }

    private fun watchView(product: TrackedProduct): View {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = rounded(COLOR_ITEM, dp(16).toFloat())
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = text(product.name, 14f, COLOR_INK, bold = true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { openProduct(product.productUrl) }
        }
        topRow.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val remove = TextView(this).apply {
            text = "Убрать"
            textSize = 11f
            setTextColor(COLOR_MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), 0, dp(7))
            setOnClickListener {
                store.remove(product.id)
                renderWatchList()
                toast("Товар удалён из мониторинга")
            }
        }
        topRow.addView(remove)
        item.addView(topRow)

        item.addView(text(product.marketplace.displayName, 11f, COLOR_ACCENT, bold = true).apply {
            setPadding(0, dp(4), 0, dp(4))
        })
        val priceText = product.lastPrice?.let { "Текущая цена: ${formatRubles(it)}" } ?: "Цена будет загружена при проверке"
        item.addView(text(priceText, 14f, if (product.lastPrice == null) COLOR_MUTED else COLOR_GREEN, bold = product.lastPrice != null))
        product.targetPrice?.let { target ->
            item.addView(text("Уведомить при цене не выше ${formatRubles(target)}", 12f, COLOR_MUTED).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }
        product.lastCheckedAt?.let { checkedAt ->
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(checkedAt))
            item.addView(text("Проверено: $time", 10f, COLOR_MUTED).apply { setPadding(0, dp(5), 0, 0) })
        }
        return item
    }

    private fun checkPricesNow() {
        checkNowButton.isEnabled = false
        checkNowButton.text = "Проверяем…"
        ioExecutor.execute {
            val summary = runCatching { PriceMonitor(applicationContext).checkAll() }.getOrNull()
            mainHandler.post {
                checkNowButton.isEnabled = true
                checkNowButton.text = "Проверить"
                renderWatchList()
                if (summary == null) {
                    toast("Не удалось завершить проверку")
                } else if (summary.total == 0) {
                    toast("Сначала добавьте товар в мониторинг")
                } else {
                    toast("Проверено: ${summary.checked} из ${summary.total}; ошибок: ${summary.failed}; уведомлений: ${summary.notifications}")
                }
            }
        }
    }

    private fun checkForUpdates() {
        if (BuildConfig.DEBUG) {
            versionFooter.text = "Версия ${BuildConfig.VERSION_NAME} · отладочная сборка"
            return
        }
        ioExecutor.execute {
            val update = runCatching { GithubReleaseClient.checkForUpdate() }.getOrNull()
            mainHandler.post {
                availableUpdate = update
                if (update != null) {
                    updateDescription.text = "Версия ${update.version} доступна в GitHub Releases. Для установки Android попросит подтверждение."
                    updateCard.visibility = View.VISIBLE
                    updateButton.text = "Скачать и установить"
                }
                versionFooter.text = "Версия ${BuildConfig.VERSION_NAME} · проверка обновлений GitHub при запуске"
            }
        }
    }

    private fun downloadAndInstallUpdate() {
        val update = availableUpdate ?: return
        updateButton.isEnabled = false
        updateButton.text = "Скачиваем…"
        ioExecutor.execute {
            val result = runCatching { GithubReleaseClient.download(update, applicationContext) }
            mainHandler.post {
                updateButton.isEnabled = true
                updateButton.text = "Повторить установку"
                result.onSuccess(::prepareInstaller).onFailure {
                    toast("Не удалось скачать обновление: ${it.message ?: "ошибка сети"}")
                }
            }
        }
    }

    private fun prepareInstaller(apk: File) {
        if (GithubReleaseClient.canInstallPackages(this)) {
            launchInstaller(apk)
            return
        }
        pendingUpdateApk = apk
        AlertDialog.Builder(this)
            .setTitle("Разрешите установку обновления")
            .setMessage("Android требует разрешить установку обновлений из этого приложения. После возврата откроется системное подтверждение установки.")
            .setNegativeButton("Позже", null)
            .setPositiveButton("Открыть настройки") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
            }
            .show()
    }

    private fun launchInstaller(apk: File) {
        try {
            startActivity(GithubReleaseClient.installerIntent(this, apk))
        } catch (_: Exception) {
            toast("Не удалось открыть установщик Android")
        }
    }

    private fun targetPriceFromInput(): Double? {
        val raw = targetInput.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return null
        return raw.replace(" ", "").replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
    }

    private fun openProduct(url: String) {
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Открыть товар"))
        }.onFailure { toast("Не удалось открыть ссылку") }
    }

    private fun openMarketplaceSearch(marketplace: Marketplace, query: String) {
        val encodedQuery = Uri.encode(query)
        val url = when (marketplace) {
            Marketplace.WILDBERRIES -> "https://www.wildberries.ru/catalog/0/search.aspx?search=$encodedQuery"
            Marketplace.OZON -> "https://www.ozon.ru/search/?text=$encodedQuery"
        }
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Поиск на ${marketplace.displayName}"))
        }.onFailure { toast("Не удалось открыть площадку") }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun input(hintText: String, inputTypeValue: Int): EditText = EditText(this).apply {
        hint = hintText
        inputType = inputTypeValue
        textSize = 14f
        setTextColor(COLOR_INK)
        setHintTextColor(COLOR_MUTED)
        maxLines = 1
        background = rounded(COLOR_INPUT, dp(13).toFloat())
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun actionButton(label: String, color: Int): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(color)
        minHeight = dp(48)
        minimumHeight = dp(48)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = true
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(17), dp(17), dp(17), dp(17))
        background = rounded(Color.WHITE, dp(20).toFloat())
        elevation = dp(2).toFloat()
        clipToOutline = true
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun cardParams(): LinearLayout.LayoutParams = matchWrap().apply { bottomMargin = dp(14) }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQUEST_NOTIFICATIONS = 412
        val COLOR_BACKGROUND = Color.rgb(244, 246, 251)
        val COLOR_INK = Color.rgb(24, 38, 61)
        val COLOR_MUTED = Color.rgb(112, 126, 147)
        val COLOR_ACCENT = Color.rgb(82, 95, 212)
        val COLOR_GREEN = Color.rgb(22, 134, 110)
        val COLOR_CHIP = Color.rgb(234, 238, 248)
        val COLOR_ITEM = Color.rgb(246, 248, 252)
        val COLOR_INPUT = Color.rgb(246, 248, 252)
        val COLOR_UPDATE_BG = Color.rgb(235, 239, 255)
    }
}
