package by.salesmaster.app.monitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import by.salesmaster.app.R
import by.salesmaster.app.data.MarketplaceRepository
import by.salesmaster.app.data.ProductOffer
import by.salesmaster.app.data.ProductStore
import by.salesmaster.app.data.TrackedProduct
import by.salesmaster.app.data.formatRubles

internal data class MonitorSummary(
    val total: Int,
    val checked: Int,
    val failed: Int,
    val notifications: Int,
)

/** Shared by the foreground "check now" action and the daily WorkManager job. */
internal class PriceMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val store = ProductStore(appContext)
    private val repository = MarketplaceRepository()

    fun checkAll(): MonitorSummary {
        val products = store.all()
        var checked = 0
        var failed = 0
        var notifications = 0

        products.forEach { product ->
            try {
                val offer = repository.latest(product)
                if (offer == null || offer.price <= 0.0) {
                    failed++
                    return@forEach
                }

                val shouldNotify = shouldNotify(product, offer.price)
                val updated = product.copy(
                    productUrl = offer.productUrl,
                    externalId = offer.externalId ?: product.externalId,
                    lastPrice = offer.price,
                    lastNotifiedPrice = if (shouldNotify) offer.price else product.lastNotifiedPrice,
                    lastCheckedAt = System.currentTimeMillis(),
                )
                store.update(updated)
                checked++

                if (shouldNotify) {
                    PriceNotification.show(appContext, updated, offer)
                    notifications++
                }
            } catch (_: Exception) {
                failed++
            }
        }
        return MonitorSummary(products.size, checked, failed, notifications)
    }

    private fun shouldNotify(product: TrackedProduct, price: Double): Boolean {
        val target = product.targetPrice
        if (target != null) {
            val newlyLower = product.lastNotifiedPrice == null || price < product.lastNotifiedPrice - 0.005
            return price <= target && newlyLower
        }
        val previous = product.lastPrice ?: return false
        return price < previous - 0.005
    }
}

private object PriceNotification {
    private const val CHANNEL_ID = "price_alerts"

    fun show(context: Context, product: TrackedProduct, offer: ProductOffer) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Изменения цен", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Уведомления, когда цена товара достигает цели или снижается"
                },
            )
        }

        val openProduct = PendingIntent.getActivity(
            context,
            product.id.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(offer.productUrl)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (product.targetPrice != null && offer.price <= product.targetPrice) {
            "Цена достигла вашей цели"
        } else {
            "Цена снизилась"
        }
        val target = product.targetPrice?.let { " · цель ${formatRubles(it)}" }.orEmpty()
        val text = "${product.name}: ${formatRubles(offer.price)}$target"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n${offer.marketplace.displayName}. Нажмите, чтобы открыть товар."))
            .setContentIntent(openProduct)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(product.id.hashCode(), notification) }
    }
}
