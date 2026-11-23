package com.papercontrol.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.papercontrol.app.R
import com.papercontrol.app.data.ModelCatalog
import com.papercontrol.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

data class DownloadProgress(val running: Boolean = false, val bytes: Long = 0, val total: Long = 0, val error: String? = null)

/** Downloads the optional chat model, resuming where it stopped, with a progress notification. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Settings.init(ctx)
        val spec = ModelCatalog.resolve(Settings.current) ?: return fail("Enter a download link for your model first.")
        if (spec.isReady(ctx)) { progress.value = DownloadProgress(); return Result.success() }
        createChannel(ctx)
        return try {
            download(spec.url, spec.partFile(ctx))
            if (isStopped) return Result.success()
            val target = spec.file(ctx)
            target.delete()
            if (!spec.partFile(ctx).renameTo(target)) throw IOException("could not store the model file")
            spec.markReady(ctx)
            progress.value = DownloadProgress()
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "download interrupted", e)
            progress.value = progress.value.copy(running = false, error = "Download interrupted: ${e.message}. It resumes automatically.")
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        } catch (t: Throwable) {
            fail(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun fail(message: String): Result {
        progress.value = DownloadProgress(error = message)
        return Result.failure()
    }

    private suspend fun download(url: String, part: java.io.File) = withContext(Dispatchers.IO) {
        var offset = if (part.exists()) part.length() else 0L
        var conn = open(url, offset)
        var code = conn.responseCode
        if (code == 416) {
            conn.disconnect(); part.delete(); offset = 0
            conn = open(url, 0); code = conn.responseCode
        }
        if (code == 404) { conn.disconnect(); throw IllegalStateException("The model file was not found at its address.") }
        if (code == 401 || code == 403) { conn.disconnect(); throw IllegalStateException("The server refused the download ($code).") }
        if (code != 200 && code != 206) { conn.disconnect(); throw IOException("Server answered $code") }
        if (code == 200) offset = 0
        val remaining = conn.contentLengthLong
        val total = if (remaining > 0) offset + remaining else 0L
        if (total > 0 && applicationContext.filesDir.usableSpace < remaining + 64L * 1024 * 1024) {
            conn.disconnect(); throw IllegalStateException("Not enough free storage: ${remaining / 1_000_000} MB needed.")
        }
        progress.value = DownloadProgress(true, offset, total)
        runCatching { setForeground(foreground(offset, total)) }
        try {
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(offset); out.seek(offset)
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var bytes = offset
                    var last = 0L
                    while (!isStopped) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        bytes += n
                        val t = SystemClock.elapsedRealtime()
                        if (t - last > 500) {
                            last = t
                            progress.value = DownloadProgress(true, bytes, total)
                            runCatching { setForeground(foreground(bytes, total)) }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!isStopped && total > 0 && part.length() != total) throw IOException("connection closed early")
    }

    private fun foreground(bytes: Long, total: Long): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle("Downloading AI model")
            .setContentText(if (total > 0) "${bytes / 1_000_000} of ${total / 1_000_000} MB" else "${bytes / 1_000_000} MB")
            .setProgress(if (total > 0) 1000 else 0, if (total > 0) (bytes * 1000 / total).toInt() else 0, total <= 0)
            .setOngoing(true)
            .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIFICATION_ID, n)
    }

    private fun open(url: String, offset: Long): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "PaperControl-Android")
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        return conn
    }

    companion object {
        private const val TAG = "DownloadWorker"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 41
        private const val WORK = "model-download"
        val progress = MutableStateFlow(DownloadProgress())

        fun start(context: Context) {
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (Settings.current.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .build()
            progress.value = DownloadProgress(running = true)
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
            progress.value = DownloadProgress()
        }

        private fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
