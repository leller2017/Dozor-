package ru.dozor.app.data

import android.content.Context
import android.net.Uri
import ru.dozor.app.detection.DetectorParams
import java.util.Calendar
import kotlin.math.pow
import kotlin.math.roundToInt

/** Снимок всех настроек «Дозора». */
data class DozorSettings(
    /** 0..100, чем больше — тем чувствительнее. */
    val sensitivity: Int = 50,
    /** Сколько «тревожных» кадров подряд нужно для срабатывания. */
    val confirmFrames: Int = 3,
    /** Кадров анализа в секунду (2..5). */
    val fps: Int = 3,
    /** Минимальный интервал между тревогами, секунды. */
    val cooldownSec: Int = 60,
    /** Снимков в серии при срабатывании (3..5). */
    val shotsPerEvent: Int = 4,
    /** null — встроенная сирена «Дозора», иначе системный звук. */
    val soundUri: Uri? = null,
    /** Длительность звукового сигнала, секунды. */
    val soundDurationSec: Int = 15,
    /** Поднимать громкость будильника до максимума на время сигнала. */
    val maxVolume: Boolean = true,
    val quietEnabled: Boolean = false,
    /** Минуты от полуночи. */
    val quietStartMin: Int = 8 * 60,
    val quietEndMin: Int = 20 * 60,
) {
    /**
     * Перевод ползунка чувствительности в параметры детектора.
     * Порог яркости пикселя: 45 (грубо) … 10 (тонко).
     * Доля изменившейся площади: 6 % … 0.2 % (по экспоненте — так ползунок «ощущается» равномерно).
     */
    fun detectorParams(): DetectorParams {
        val s = sensitivity.coerceIn(0, 100) / 100f
        val pixelThreshold = (45 - 35 * s).roundToInt()
        val area = 0.06f * (0.002f / 0.06f).pow(s)
        return DetectorParams(pixelThreshold, area, confirmFrames)
    }

    fun isQuietAt(timeMs: Long): Boolean {
        if (!quietEnabled || quietStartMin == quietEndMin) return false
        val c = Calendar.getInstance().apply { timeInMillis = timeMs }
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (quietStartMin < quietEndMin) {
            now in quietStartMin until quietEndMin
        } else {
            now >= quietStartMin || now < quietEndMin
        }
    }
}

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("dozor_settings", Context.MODE_PRIVATE)

    fun get(): DozorSettings = DozorSettings(
        sensitivity = prefs.getInt(K_SENS, 50),
        confirmFrames = prefs.getInt(K_CONFIRM, 3),
        fps = prefs.getInt(K_FPS, 3),
        cooldownSec = prefs.getInt(K_COOLDOWN, 60),
        shotsPerEvent = prefs.getInt(K_SHOTS, 4),
        soundUri = prefs.getString(K_SOUND, null)?.let(Uri::parse),
        soundDurationSec = prefs.getInt(K_SOUND_DUR, 15),
        maxVolume = prefs.getBoolean(K_MAX_VOL, true),
        quietEnabled = prefs.getBoolean(K_QUIET, false),
        quietStartMin = prefs.getInt(K_QUIET_START, 8 * 60),
        quietEndMin = prefs.getInt(K_QUIET_END, 20 * 60),
    )

    fun update(block: (DozorSettings) -> DozorSettings) {
        val s = block(get())
        prefs.edit()
            .putInt(K_SENS, s.sensitivity)
            .putInt(K_CONFIRM, s.confirmFrames)
            .putInt(K_FPS, s.fps)
            .putInt(K_COOLDOWN, s.cooldownSec)
            .putInt(K_SHOTS, s.shotsPerEvent)
            .putString(K_SOUND, s.soundUri?.toString())
            .putInt(K_SOUND_DUR, s.soundDurationSec)
            .putBoolean(K_MAX_VOL, s.maxVolume)
            .putBoolean(K_QUIET, s.quietEnabled)
            .putInt(K_QUIET_START, s.quietStartMin)
            .putInt(K_QUIET_END, s.quietEndMin)
            .apply()
    }

    private companion object {
        const val K_SENS = "sensitivity"
        const val K_CONFIRM = "confirm_frames"
        const val K_FPS = "fps"
        const val K_COOLDOWN = "cooldown_sec"
        const val K_SHOTS = "shots"
        const val K_SOUND = "sound_uri"
        const val K_SOUND_DUR = "sound_duration"
        const val K_MAX_VOL = "max_volume"
        const val K_QUIET = "quiet_enabled"
        const val K_QUIET_START = "quiet_start"
        const val K_QUIET_END = "quiet_end"
    }
}
