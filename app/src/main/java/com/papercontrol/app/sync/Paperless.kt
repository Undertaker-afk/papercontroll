package com.papercontrol.app.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.papercontrol.app.data.LabelKind
import com.papercontrol.app.data.Settings
import com.papercontrol.app.data.Store
import com.papercontrol.app.processing.Pdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Optional link to a Paperless-ngx server: documents scanned here are uploaded with their
 * title, date, correspondent, type and tags (created on the server when missing).
 */
object Paperless {
    private const val TAG = "Paperless"

    fun configured(): Boolean = Settings.current.paperlessUrl.startsWith("http") && Settings.current.paperlessToken.isNotBlank()

    /** Checks the address and token; returns null when fine, otherwise what went wrong. */
    suspend fun test(): String? = withContext(Dispatchers.IO) {
        try {
            val c = open("/api/documents/?page_size=1", "GET")
            val code = c.responseCode
            c.disconnect()
            when (code) {
                200 -> null
                401, 403 -> "The server refused the token ($code)."
                404 -> "No Paperless-ngx API at this address."
                else -> "The server answered $code."
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    fun enqueueUpload(context: Context, docId: Long) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf("doc" to docId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("upload-$docId", ExistingWorkPolicy.REPLACE, req)
    }

    /** Uploads one document; returns the server's task id. */
    suspend fun upload(context: Context, docId: Long): String = withContext(Dispatchers.IO) {
        val doc = Store.document(docId) ?: throw IllegalStateException("Document not found")
        val labels = Store.allLabels()
        val pdf = Pdf.toCache(context, doc)
        val fields = mutableListOf<Pair<String, String>>()
        fields += "title" to doc.title
        doc.created?.let { fields += "created" to it.toString() }
        doc.correspondentId?.let { labels[it] }?.let { fields += "correspondent" to remoteId("correspondents", it.name).toString() }
        doc.docTypeId?.let { labels[it] }?.let { fields += "document_type" to remoteId("document_types", it.name).toString() }
        // Folders become storage paths: <folder>/<correspondent>/<title> on the server.
        doc.folderId?.let { labels[it] }?.let { l ->
            runCatching { remoteId("storage_paths", l.name, l.name + "/{{ correspondent }}/{{ title }}") }.getOrNull()
                ?.let { fields += "storage_path" to it.toString() }
        }
        for (t in doc.tagIds.mapNotNull { labels[it] }.filter { !it.isInbox }) fields += "tags" to remoteId("tags", t.name).toString()
        doc.asn?.let { fields += "archive_serial_number" to it.toString() }

        val boundary = "----papercontrol${System.nanoTime()}"
        val c = open("/api/documents/post_document/", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        DataOutputStream(c.outputStream).use { out ->
            for ((k, v) in fields) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n")
                out.write(v.toByteArray()); out.writeBytes("\r\n")
            }
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"${pdf.name.replace("\"", "")}\"\r\nContent-Type: application/pdf\r\n\r\n")
            pdf.inputStream().use { it.copyTo(out) }
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        c.disconnect()
        pdf.delete()
        if (code !in 200..299) throw IOException("Upload failed ($code): ${body.take(200)}")
        val task = body.trim().trim('"')
        Store.updateDocument(doc.copy(remoteTask = task))
        task
    }

    /** The id of the object called [name] on the server, created when missing. */
    private fun remoteId(endpoint: String, name: String, path: String? = null): Long {
        val q = open("/api/$endpoint/?name__iexact=" + URLEncoder.encode(name, "UTF-8"), "GET")
        if (q.responseCode == 200) {
            val res = JSONObject(q.inputStream.bufferedReader().readText()).optJSONArray("results")
            q.disconnect()
            if (res != null && res.length() > 0) return res.getJSONObject(0).getLong("id")
        } else q.disconnect()
        val c = open("/api/$endpoint/", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("name", name).put("matching_algorithm", 6)
        if (path != null) body.put("path", path)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
            c.disconnect()
            throw IOException("Could not create $name on the server: ${err.take(120)}")
        }
        val id = JSONObject(c.inputStream.bufferedReader().readText()).getLong("id")
        c.disconnect()
        return id
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val cfg = Settings.current
        val c = URL(cfg.paperlessUrl + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 120_000
        c.setRequestProperty("Authorization", "Token ${cfg.paperlessToken}")
        c.setRequestProperty("Accept", "application/json; version=5")
        return c
    }

    class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            Settings.init(applicationContext)
            Store.init(applicationContext)
            val id = inputData.getLong("doc", -1)
            if (id < 0 || !configured()) return Result.failure()
            return try {
                upload(applicationContext, id)
                Result.success()
            } catch (e: IOException) {
                Log.w(TAG, "upload failed", e)
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            } catch (t: Throwable) {
                Log.w(TAG, "upload failed", t)
                Result.failure()
            }
        }
    }
}
