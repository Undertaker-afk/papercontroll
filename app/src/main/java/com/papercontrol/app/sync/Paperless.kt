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
import java.io.File
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
        Store.setRemote(doc.id, null, task)
        task
    }

    // ------------------------------------------------------------------ two-way sync

    data class SyncResult(val uploaded: Int, val updatedRemote: Int, val downloaded: Int, val updatedLocal: Int, val removed: Int)

    val syncing = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /**
     * One sync round:
     *  1. upload documents the server does not have yet, and learn their server ids,
     *  2. push local edits (title, date, sender, type, folder, tags, ASN),
     *  3. delete on the server what was deleted here,
     *  4. download new documents and apply edits made on the server,
     *  5. move documents deleted on the server to the trash here.
     * When both sides changed a document, this phone's version wins.
     */
    suspend fun sync(context: Context): SyncResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val last = Settings.current.lastSync
        var up = 0; var pushed = 0; var down = 0; var pulled = 0; var removed = 0
        try {
            syncing.value = "Uploading"
            val all = Store.documents(com.papercontrol.app.data.Filter(), Int.MAX_VALUE)
            for (d in all.filter { it.remoteId == null && it.remoteTask == null }) { upload(context, d.id); up++ }
            for (d in Store.documents(com.papercontrol.app.data.Filter(), Int.MAX_VALUE).filter { it.remoteId == null && it.remoteTask != null }) {
                taskDocument(d.remoteTask!!)?.let { Store.setRemote(d.id, it, null) }
            }

            syncing.value = "Sending changes"
            val labels = Store.allLabels()
            for (d in Store.documents(com.papercontrol.app.data.Filter(), Int.MAX_VALUE).filter { it.remoteId != null && it.modifiedAt > last }) {
                val body = JSONObject().put("title", d.title)
                d.created?.let { body.put("created", it.toString()) }
                body.put("correspondent", d.correspondentId?.let { labels[it] }?.let { remoteId("correspondents", it.name) } ?: JSONObject.NULL)
                body.put("document_type", d.docTypeId?.let { labels[it] }?.let { remoteId("document_types", it.name) } ?: JSONObject.NULL)
                body.put("storage_path", d.folderId?.let { labels[it] }?.let { l -> runCatching { remoteId("storage_paths", l.name, l.name + "/{{ correspondent }}/{{ title }}") }.getOrNull() } ?: JSONObject.NULL)
                body.put("tags", org.json.JSONArray(d.tagIds.mapNotNull { labels[it] }.filter { !it.isInbox }.map { remoteId("tags", it.name) }))
                body.put("archive_serial_number", d.asn ?: JSONObject.NULL)
                request("PATCH", "/api/documents/${d.remoteId}/", body.toString())
                pushed++
            }
            for (d in Store.documents(com.papercontrol.app.data.Filter(trash = true), Int.MAX_VALUE).filter { it.remoteId != null && (it.deletedAt ?: 0) > last }) {
                runCatching { request("DELETE", "/api/documents/${d.remoteId}/", null) }
                Store.setRemote(d.id, null, null)
            }

            syncing.value = "Downloading"
            val names = mapOf(
                "tags" to remoteNames("tags"), "correspondents" to remoteNames("correspondents"),
                "document_types" to remoteNames("document_types"), "storage_paths" to remoteNames("storage_paths"),
            )
            val since = java.time.Instant.ofEpochMilli(maxOf(0L, last - 60_000)).toString()
            var next: String? = "/api/documents/?page_size=50&ordering=modified" + if (last > 0) "&modified__gt=" + URLEncoder.encode(since, "UTF-8") else ""
            while (next != null) {
                val page = JSONObject(request("GET", next, null))
                val results = page.getJSONArray("results")
                for (i in 0 until results.length()) {
                    val r = results.getJSONObject(i)
                    val rid = r.getLong("id")
                    val local = Store.byRemoteId(rid)
                    if (local != null) {
                        // Local edits were sent first, so the server copy already includes them.
                        applyRemote(local, r, names)
                        Store.setModified(local.id, started - 1)
                        pulled++
                    } else {
                        if (download(context, r, names, started)) down++
                    }
                }
                next = page.optString("next").takeIf { it.isNotBlank() && it != "null" }?.let { u -> u.substring(u.indexOf("/api/")) }
            }

            syncing.value = "Checking deletions"
            val remoteIds = HashSet<Long>()
            var more: String? = "/api/documents/?page_size=1000&fields=id"
            while (more != null) {
                val page = JSONObject(request("GET", more, null))
                val res = page.getJSONArray("results")
                for (i in 0 until res.length()) remoteIds += res.getJSONObject(i).getLong("id")
                more = page.optString("next").takeIf { it.isNotBlank() && it != "null" }?.let { u -> u.substring(u.indexOf("/api/")) }
            }
            val gone = Store.documents(com.papercontrol.app.data.Filter(), Int.MAX_VALUE).filter { it.remoteId != null && it.remoteId !in remoteIds }
            if (gone.isNotEmpty()) { Store.trash(gone.map { it.id }); removed = gone.size }

            Settings.update { it.copy(lastSync = started) }
            SyncResult(up, pushed, down, pulled, removed)
        } finally {
            syncing.value = null
        }
    }

    /** The server's document id for a finished upload task. */
    private fun taskDocument(task: String): Long? = runCatching {
        val a = org.json.JSONArray(request("GET", "/api/tasks/?task_id=" + URLEncoder.encode(task, "UTF-8"), null))
        if (a.length() == 0) return null
        val t = a.getJSONObject(0)
        if (t.optString("status") != "SUCCESS") return null
        t.optString("related_document").toLongOrNull()
    }.getOrNull()

    private fun remoteNames(endpoint: String): Map<Long, String> {
        val out = HashMap<Long, String>()
        var next: String? = "/api/$endpoint/?page_size=1000"
        while (next != null) {
            val page = JSONObject(request("GET", next, null))
            val res = page.getJSONArray("results")
            for (i in 0 until res.length()) res.getJSONObject(i).let { out[it.getLong("id")] = it.getString("name") }
            next = page.optString("next").takeIf { it.isNotBlank() && it != "null" }?.let { u -> u.substring(u.indexOf("/api/")) }
        }
        return out
    }

    private fun localLabel(kind: LabelKind, remote: Any?, names: Map<Long, String>): Long? {
        val id = (remote as? Number)?.toLong() ?: return null
        return names[id]?.let { Store.labelNamed(kind, it) }
    }

    private fun applyRemote(local: com.papercontrol.app.data.Document, r: JSONObject, names: Map<String, Map<Long, String>>) {
        val tags = r.optJSONArray("tags")?.let { a -> (0 until a.length()).mapNotNull { names["tags"]!![a.getLong(it)]?.let { n -> Store.labelNamed(LabelKind.TAG, n) } } }.orEmpty()
        val inbox = Store.inboxTagIds().filter { it in local.tagIds }
        Store.updateDocument(
            local.copy(
                title = r.optString("title", local.title),
                created = r.optString("created").take(10).let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: local.created,
                correspondentId = localLabel(LabelKind.CORRESPONDENT, r.opt("correspondent"), names["correspondents"]!!),
                docTypeId = localLabel(LabelKind.DOCTYPE, r.opt("document_type"), names["document_types"]!!),
                folderId = localLabel(LabelKind.FOLDER, r.opt("storage_path"), names["storage_paths"]!!) ?: local.folderId,
                tagIds = (tags + inbox).distinct(),
                asn = if (r.isNull("archive_serial_number")) null else r.optInt("archive_serial_number"),
                content = r.optString("content").ifBlank { local.content },
            )
        )
    }

    /** Downloads a server document (its archived PDF, or the original image) as a new local document. */
    private fun download(context: Context, r: JSONObject, names: Map<String, Map<Long, String>>, started: Long): Boolean {
        val rid = r.getLong("id")
        val c = open("/api/documents/$rid/download/", "GET")
        if (c.responseCode != 200) { c.disconnect(); return false }
        val type = c.contentType.orEmpty()
        val tmp = File(context.cacheDir, "remote-$rid").apply { outputStream().use { o -> c.inputStream.use { it.copyTo(o) } } }
        c.disconnect()
        val bitmaps = try {
            if (type.contains("pdf")) Pdf.render(android.os.ParcelFileDescriptor.open(tmp, android.os.ParcelFileDescriptor.MODE_READ_ONLY), 2000)
            else listOfNotNull(com.papercontrol.app.processing.Images.decode(tmp))
        } finally {
            tmp.delete()
        }
        if (bitmaps.isEmpty()) return false
        val content = r.optString("content")
        val pages = bitmaps.mapIndexed { i, b ->
            val f = com.papercontrol.app.processing.Images.save(b, File(Store.pagesDir(), "remote-$rid-$i-${System.nanoTime()}.jpg"))
            com.papercontrol.app.data.Page(docId = 0, index = i, path = f.path, originalPath = null, text = if (i == 0) content else "", capturedAt = 0)
        }
        val tags = r.optJSONArray("tags")?.let { a -> (0 until a.length()).mapNotNull { names["tags"]!![a.getLong(it)]?.let { n -> Store.labelNamed(LabelKind.TAG, n) } } }.orEmpty()
        val id = Store.insertDocument(
            com.papercontrol.app.data.Document(
                title = r.optString("title", "Document"),
                correspondentId = localLabel(LabelKind.CORRESPONDENT, r.opt("correspondent"), names["correspondents"]!!),
                docTypeId = localLabel(LabelKind.DOCTYPE, r.opt("document_type"), names["document_types"]!!),
                folderId = localLabel(LabelKind.FOLDER, r.opt("storage_path"), names["storage_paths"]!!),
                created = r.optString("created").take(10).let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() },
                addedAt = started - 1, modifiedAt = started - 1,
                asn = if (r.isNull("archive_serial_number")) null else r.optInt("archive_serial_number"),
                content = content,
                pageCount = pages.size,
                thumbPath = pages.first().path,
                tagIds = tags,
                remoteId = rid,
            ),
            pages,
        )
        // insertDocument stamps the modification time; keep it at the sync start so it is not sent back.
        Store.setModified(id, started - 1)
        return true
    }

    private fun request(method: String, path: String, json: String?): String {
        // Android's HTTP stack accepts PATCH; plain Java does not, hence the fallback.
        val c = try { open(path, method) } catch (e: java.net.ProtocolException) {
            open(path, "POST").also { it.setRequestProperty("X-HTTP-Method-Override", method) }
        }
        if (json != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray()) }
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        c.disconnect()
        if (code !in 200..299) throw IOException("$method $path failed ($code): ${body.take(160)}")
        return body
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
