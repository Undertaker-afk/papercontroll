package com.papercontrol.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.papercontrol.app.R
import com.papercontrol.app.data.ModelCatalog
import com.papercontrol.app.data.Settings
import com.papercontrol.app.data.Store
import com.papercontrol.app.ml.Embedder
import com.papercontrol.app.processing.Backup
import com.papercontrol.app.sync.Paperless
import com.papercontrol.app.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** Turns new and changed documents into search vectors. */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Settings.init(ctx); Store.init(ctx)
        if (!Embedder.isReady(ctx)) return Result.success()
        val key = ModelCatalog.EMBEDDING_KEY
        try {
            while (!isStopped) {
                val batch = Store.unembedded(key, 20)
                if (batch.isEmpty()) break
                for (d in batch) {
                    if (isStopped) break
                    val v = Embedder.document(ctx, d.title, d.content)
                    Store.saveEmbedding(d.id, key, v)
                    indexed.value = indexed.value + 1
                }
            }
        } catch (t: Throwable) {
            Log.w("IndexWorker", "indexing failed", t)
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
        return Result.success()
    }

    companion object {
        val indexed = MutableStateFlow(0)
        fun start(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("index", ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<IndexWorker>().build())
        }
    }
}

/** Once a day: notifies about payment, warranty, cancellation and expiry dates that come up. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Settings.init(ctx); Store.init(ctx)
        if (!Settings.current.reminders) return Result.success()
        Store.emptyTrash()
        val today = LocalDate.now()
        for (r in Store.reminders(openOnly = true, until = today.plusDays(31))) {
            val days = ChronoUnit.DAYS.between(today, r.date).toInt()
            // The nearest step not yet notified; overdue reminders count as step 0.
            val step = r.kind.remindDaysBefore.filter { days <= it }.minOrNull() ?: continue
            if (step in r.notified) continue
            notify(ctx, r.id.toInt(), r.docId, r.kind.label + if (days > 0) " in $days day${if (days == 1) "" else "s"}" else if (days == 0) " today" else " (overdue)", r.docTitle)
            Store.saveReminder(r.copy(notified = r.notified + step))
        }
        return Result.success()
    }

    companion object {
        const val CHANNEL = "reminders"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "reminders", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build(),
            )
        }

        fun notify(context: Context, id: Int, docId: Long, title: String, text: String) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Deadlines", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context, id,
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_DOC, docId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_scan)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(1000 + id, n) }
        }
    }
}

/** Weekly backup into the folder chosen in settings. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Settings.init(ctx); Store.init(ctx)
        val uri = Settings.current.backupUri.ifBlank { return Result.success() }
        return try {
            Backup.toFolder(ctx, android.net.Uri.parse(uri))
            Result.success()
        } catch (t: Throwable) {
            Log.w("BackupWorker", "backup failed", t)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (Settings.current.backupUri.isBlank()) { wm.cancelUniqueWork("backup"); return }
            wm.enqueueUniquePeriodicWork(
                "backup", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupWorker>(7, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build(),
            )
        }
    }
}

/** Two-way sync with the Paperless-ngx server every few hours. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Settings.init(ctx); Store.init(ctx)
        if (!Settings.current.paperlessSync || !Paperless.configured()) return Result.success()
        return try {
            Paperless.sync(ctx)
            Result.success()
        } catch (t: Throwable) {
            Log.w("SyncWorker", "sync failed", t)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!Settings.current.paperlessSync) { wm.cancelUniqueWork("sync"); return }
            wm.enqueueUniquePeriodicWork(
                "sync", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
            )
        }

        fun now(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "sync-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
            )
        }
    }
}
