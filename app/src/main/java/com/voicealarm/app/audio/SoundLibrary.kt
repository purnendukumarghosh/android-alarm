package com.voicealarm.app.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import com.voicealarm.app.AlarmStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A song or converted clip the user can pick as an alarm sound. */
data class Sound(
    val id: String,
    val name: String,
    val fileName: String,
    val durationMs: Long,
    /** "music" (imported song) or "video" (made with the ringtone maker). */
    val kind: String,
) {
    fun file(context: Context) = File(SoundLibrary.dir(context), fileName)

    fun toJson() = JSONObject().put("id", id).put("name", name).put("file", fileName).put("duration", durationMs).put("kind", kind)

    companion object {
        fun fromJson(o: JSONObject) = Sound(o.getString("id"), o.optString("name"), o.getString("file"), o.optLong("duration"), o.optString("kind", "music"))
    }
}

/**
 * The app's own copies of alarm sounds (files/sounds/). Songs are copied in
 * rather than referenced, so an alarm still rings after the original is moved,
 * deleted, or its access permission is gone after a reboot.
 */
object SoundLibrary {
    private const val PREFS = "sounds"
    private const val KEY = "list"
    const val MAX_IMPORT_BYTES = 80L * 1024 * 1024

    fun dir(context: Context) = File(context.filesDir, "sounds").apply { mkdirs() }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(context: Context): List<Sound> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Sound.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(context: Context, id: String?): Sound? = id?.let { i -> all(context).firstOrNull { it.id == i } }

    /** The playable file of [id], or null if it is gone. */
    fun fileFor(context: Context, id: String?): File? = get(context, id)?.file(context)?.takeIf { it.exists() && it.length() > 0 }

    @Synchronized
    fun add(context: Context, sound: Sound) {
        write(context, listOf(sound) + all(context).filter { it.id != sound.id })
    }

    @Synchronized
    fun rename(context: Context, id: String, name: String) {
        write(context, all(context).map { if (it.id == id) it.copy(name = name) else it })
    }

    /** Removes the sound; alarms that used it go back to speaking their label. */
    @Synchronized
    fun delete(context: Context, id: String) {
        get(context, id)?.file(context)?.delete()
        write(context, all(context).filter { it.id != id })
        AlarmStore.all(context).filter { it.musicId == id }.forEach { AlarmStore.save(context, it.copy(musicId = null)) }
    }

    private fun write(context: Context, list: List<Sound>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun newId(): String = UUID.randomUUID().toString().take(12)

    /**
     * Copies an audio file the user picked into the library (background
     * thread). Returns null if it is not playable audio.
     */
    fun importAudio(context: Context, uri: Uri): Sound? {
        val cr = context.contentResolver
        val name = displayName(context, uri) ?: "Song"
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 } ?: "audio"
        val id = newId()
        val file = File(dir(context), "$id.$ext")
        try {
            var copied = 0L
            cr.openInputStream(uri)?.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_IMPORT_BYTES) throw IllegalStateException("too big")
                        out.write(buf, 0, n)
                    }
                }
            } ?: return null
            val duration = durationOf(file)
            if (duration <= 0) {
                file.delete()
                return null
            }
            return Sound(id, name.substringBeforeLast('.'), file.name, duration, "music").also { add(context, it) }
        } catch (e: Exception) {
            file.delete()
            return null
        }
    }

    /** Moves a converted file into the library. */
    fun addConverted(context: Context, source: File, name: String, durationMs: Long): Sound {
        val id = newId()
        val file = File(dir(context), "$id.${source.extension}")
        source.copyTo(file, overwrite = true)
        return Sound(id, name, file.name, durationMs, "video").also { add(context, it) }
    }

    fun durationOf(file: File): Long = try {
        MediaMetadataRetriever().run {
            try {
                setDataSource(file.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                release()
            }
        }
    } catch (e: Exception) {
        0L
    }

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')
}

/** Publishes a sound to the phone so it shows up in the system ringtone / alarm pickers. */
object RingtoneExporter {
    const val FOLDER = "Voice Alarm"

    /** API 26–28 need storage permission to write to shared storage; 29+ don't. */
    fun needsStoragePermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /**
     * Copies [file] to Ringtones/Voice Alarm/ and marks it as a ringtone,
     * alarm and notification sound. Returns its content Uri, or null.
     */
    fun export(context: Context, file: File, title: String, mime: String): Uri? {
        val cr = context.contentResolver
        val displayName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) + "." + file.extension
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.TITLE, title)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.Audio.AudioColumns.IS_RINGTONE, true)
            put(MediaStore.Audio.AudioColumns.IS_ALARM, true)
            put(MediaStore.Audio.AudioColumns.IS_NOTIFICATION, true)
            put(MediaStore.Audio.AudioColumns.IS_MUSIC, false)
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES + "/" + FOLDER)
                values.put(MediaStore.MediaColumns.IS_PENDING, 1)
                val uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
                cr.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES), FOLDER).apply { mkdirs() }
                val dest = File(dir, displayName)
                file.copyTo(dest, overwrite = true)
                @Suppress("DEPRECATION")
                values.put(MediaStore.MediaColumns.DATA, dest.absolutePath)
                cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** True if the app may change the phone's default ringtone (special "modify system settings" access). */
    fun canSetDefault(context: Context) = Settings.System.canWrite(context)

    /** Makes [uri] the phone's ringtone ([type] = RingtoneManager.TYPE_RINGTONE / TYPE_ALARM). */
    fun setDefault(context: Context, uri: Uri, type: Int = RingtoneManager.TYPE_RINGTONE): Boolean = try {
        RingtoneManager.setActualDefaultRingtoneUri(context, type, uri)
        true
    } catch (e: Exception) {
        false
    }
}
