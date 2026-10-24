package by.salesmaster.app.monitor

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class DailyMonitorWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {
    override fun doWork(): Result = try {
        val summary = PriceMonitor(applicationContext).checkAll()
        if (summary.total > 0 && summary.checked == 0 && summary.failed > 0) Result.retry() else Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

internal object MonitorScheduler {
    private const val UNIQUE_WORK_NAME = "daily-price-monitor"

    fun scheduleDaily(context: Context) {
        val request = PeriodicWorkRequestBuilder<DailyMonitorWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(UNIQUE_WORK_NAME)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
