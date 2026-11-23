package com.papercontrol.app.processing

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.papercontrol.app.data.Settings
import com.papercontrol.app.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The whole archive in one zip: the database and every page image. Restoring replaces the
 * archive on this phone. Models and server tokens are not included.
 */
object Backup {
    private const val DB = "papercontrol.db"
    private const val MANIFEST = "manifest.json"
    private const val KEEP = 4

    suspend fun write(context: Context, out: OutputStream) = withContext(Dispatchers.IO) {
        val pagesDir = Store.pagesDir()
        ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(JSONObject().put("version", 2).put("filesDir", context.filesDir.absolutePath).put("created", System.currentTimeMillis()).toString().toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(DB))
                Store.snapshot { it.inputStream().use { s -> s.copyTo(zip) } }
                zip.closeEntry()
                pagesDir.listFiles()?.forEach { f ->
                    zip.putNextEntry(ZipEntry("pages/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    /** Replaces the archive with the backup. Throws when [input] is not a PaperControl backup. */
    suspend fun restore(context: Context, input: InputStream) = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var oldFilesDir: String? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                // Only the files a backup contains; no paths outside the staging folder.
                if (name.contains("..")) continue
                when {
                    name == MANIFEST -> oldFilesDir = JSONObject(zip.readBytes().decodeToString()).optString("filesDir")
                    name == DB -> File(staging, DB).outputStream().use { zip.copyTo(it) }
                    name.startsWith("pages/") && !e.isDirectory -> File(staging, "pages").apply { mkdirs() }.let { d -> File(d, name.substringAfterLast('/')).outputStream().use { zip.copyTo(it) } }
                }
            }
        }
        val db = File(staging, DB)
        if (!db.exists()) throw IllegalArgumentException("This file is not a PaperControl backup.")
        Store.close()
        try {
            val target = Store.databaseFile()
            listOf("", "-wal", "-shm", "-journal").forEach { File(target.path + it).delete() }
            db.copyTo(target, overwrite = true)
            val pages = Store.pagesDir()
            pages.deleteRecursively(); pages.mkdirs()
            File(staging, "pages").listFiles()?.forEach { it.copyTo(File(pages, it.name), overwrite = true) }
        } finally {
            Store.reopen()
            staging.deleteRecursively()
        }
        // Another phone or user may have another app folder: point the pages at this one.
        val from = oldFilesDir
        val to = context.filesDir.absolutePath
        if (!from.isNullOrBlank() && from != to) Store.relocate(from, to)
    }

    /** Writes a dated backup into the folder the user picked and keeps the newest [KEEP]. */
    suspend fun toFolder(context: Context, tree: Uri) = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, tree) ?: throw IllegalStateException("The backup folder is not available.")
        val name = "PaperControl-backup-${LocalDate.now()}.zip"
        dir.findFile(name)?.delete()
        val file = dir.createFile("application/zip", name) ?: throw IllegalStateException("Could not create the backup file.")
        context.contentResolver.openOutputStream(file.uri)?.use { write(context, it) } ?: throw IllegalStateException("Could not write the backup file.")
        dir.listFiles().filter { it.name?.startsWith("PaperControl-backup-") == true }.sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
        Settings.update { it.copy(lastBackup = System.currentTimeMillis()) }
    }
}
