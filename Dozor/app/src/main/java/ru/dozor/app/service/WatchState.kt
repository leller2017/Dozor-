package ru.dozor.app.service

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Состояние дежурства — общий источник правды для сервиса и экранов. */
object WatchState {

    enum class Mode { IDLE, STARTING, WARMUP, WATCHING, ALARM, COOLDOWN, CAMERA_BUSY, ERROR }

    data class Status(
        val mode: Mode = Mode.IDLE,
        val message: String = "",
        val sessionAlarms: Int = 0,
        val changeRatio: Float = 0f,
        val threshold: Float = 0f,
        val streak: Int = 0,
        val confirmFrames: Int = 0,
        val cooldownLeftSec: Int = 0,
    ) {
        val isRunning: Boolean get() = mode != Mode.IDLE && mode != Mode.ERROR
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val _preview = MutableStateFlow<Bitmap?>(null)
    /** Уменьшенная картинка «что видит сторож» с подсветкой изменений. */
    val preview: StateFlow<Bitmap?> = _preview

    fun update(block: (Status) -> Status) = _status.update(block)
    fun setPreview(bmp: Bitmap?) {
        _preview.value = bmp
    }
}
