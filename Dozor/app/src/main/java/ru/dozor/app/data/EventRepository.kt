package ru.dozor.app.data

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Одно срабатывание: папка со снимками shot_1.jpg … shot_N.jpg, миниатюрой и meta.txt. */
data class AlarmEvent(
    val id: String,
    val timestamp: Long,
    val changeRatio: Float,
    val dir: File,
) {
    val shots: List<File>
        get() = dir.listFiles { f -> f.name.startsWith("shot_") && f.name.endsWith(".jpg") }
            ?.sortedBy { it.name }
            .orEmpty()

    val thumb: File get() = File(dir, "thumb.jpg")
}

/**
 * Хранилище журнала на файловой системе — без базы данных, чтобы было просто
 * и чтобы снимки можно было забрать с телефона по USB
 * (Android/data/ru.dozor.app/files/Pictures/events).
 */
class EventRepository(context: Context) {

    private val root: File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir,
        "events"
    ).apply { mkdirs() }

    private val _version = MutableStateFlow(0)
    /** Меняется при любом изменении журнала — экраны подписываются и перечитывают список. */
    val version: StateFlow<Int> = _version

    fun create(timestamp: Long, changeRatio: Float): AlarmEvent {
        val id = ID_FORMAT.format(Date(timestamp))
        val dir = File(root, id).apply { mkdirs() }
        File(dir, META).writeText("$timestamp;$changeRatio")
        notifyChanged()
        return AlarmEvent(id, timestamp, changeRatio, dir)
    }

    fun list(): List<AlarmEvent> =
        root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { read(it) }
            ?.sortedByDescending { it.timestamp }
            .orEmpty()

    fun get(id: String): AlarmEvent? = read(File(root, id))

    fun count(): Int = root.listFiles { f -> f.isDirectory }?.size ?: 0

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        notifyChanged()
    }

    fun clear() {
        root.listFiles()?.forEach { it.deleteRecursively() }
        notifyChanged()
    }

    fun notifyChanged() {
        _version.update { it + 1 }
    }

    private fun read(dir: File): AlarmEvent? {
        if (!dir.isDirectory) return null
        val meta = File(dir, META)
        val parts = runCatching { meta.readText().split(";") }.getOrNull()
        val ts = parts?.getOrNull(0)?.toLongOrNull() ?: dir.lastModified()
        val ratio = parts?.getOrNull(1)?.toFloatOrNull() ?: 0f
        return AlarmEvent(dir.name, ts, ratio, dir)
    }

    private companion object {
        const val META = "meta.txt"
        val ID_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    }
}
