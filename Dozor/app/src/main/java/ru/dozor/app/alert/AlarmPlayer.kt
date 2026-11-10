package ru.dozor.app.alert

import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import ru.dozor.app.R

/**
 * Громкий сигнал через поток будильника (USAGE_ALARM): он звучит даже в беззвучном режиме
 * на большинстве телефонов. Вся работа — в главном потоке.
 */
class AlarmPlayer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var savedVolume: Int = -1
    private val stopRunnable = Runnable { stopNow() }

    val isPlaying: Boolean get() = player != null

    fun play(uri: Uri?, durationSec: Int, maxVolume: Boolean) {
        main.post { startNow(uri, durationSec, maxVolume) }
    }

    fun stop() {
        main.post { stopNow() }
    }

    private fun startNow(uri: Uri?, durationSec: Int, maxVolume: Boolean) {
        stopNow()
        val source = uri ?: builtInSiren(context)
        val mp = createPlayer(source)
            ?: createPlayer(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            ?: return

        if (maxVolume) {
            runCatching {
                savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            }.onFailure { savedVolume = -1 }
        }
        mp.start()
        player = mp
        main.removeCallbacks(stopRunnable)
        main.postDelayed(stopRunnable, durationSec.coerceIn(1, 600) * 1000L)
    }

    private fun createPlayer(uri: Uri?): MediaPlayer? {
        if (uri == null) return null
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.prepare()
            mp
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось открыть звук $uri", e)
            mp.release()
            null
        }
    }

    private fun stopNow() {
        main.removeCallbacks(stopRunnable)
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        if (savedVolume >= 0) {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
            savedVolume = -1
        }
    }

    companion object {
        private const val TAG = "AlarmPlayer"

        fun builtInSiren(context: Context): Uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.packageName)
            .appendPath(context.resources.getResourceTypeName(R.raw.siren))
            .appendPath(context.resources.getResourceEntryName(R.raw.siren))
            .build()
    }
}
