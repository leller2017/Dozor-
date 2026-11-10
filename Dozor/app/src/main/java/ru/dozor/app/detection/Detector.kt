package ru.dozor.app.detection

/**
 * Уменьшенный кадр в оттенках серого (яркость 0..255 в каждом байте).
 * Ориентация — как у сенсора; [rotationDegrees] — на сколько повернуть для показа.
 */
class LumaFrame(
    val width: Int,
    val height: Int,
    val luma: ByteArray,
    val rotationDegrees: Int,
    val timestampMs: Long,
)

data class DetectorParams(
    /** Насколько (0..255) должна измениться яркость пикселя, чтобы он считался изменившимся. */
    val pixelThreshold: Int,
    /** Доля изменившихся пикселей (0..1), при которой кадр «тревожный». */
    val areaRatio: Float,
    /** Сколько тревожных кадров подряд нужно для срабатывания. */
    val confirmFrames: Int,
)

data class DetectionResult(
    /** Доля изменившихся пикселей, 0..1. */
    val changeRatio: Float,
    /** Этот кадр превысил порог. */
    val alarmFrame: Boolean,
    /** Набралось нужное число тревожных кадров подряд — это срабатывание. */
    val triggered: Boolean,
    /** Текущая длина серии тревожных кадров. */
    val streak: Int,
    /** Маска изменившихся пикселей (1 — изменился) — для визуализации, может быть null. */
    val mask: ByteArray?,
)

/**
 * Интерфейс детектора. Сейчас реализация одна — [MotionDetector] (разница кадров).
 * В будущем сюда подключается нейросетевой детектор «человек / не человек»,
 * например цепочкой: MotionDetector → PersonClassifier.
 */
interface Detector {
    fun process(frame: LumaFrame, params: DetectorParams): DetectionResult
    fun reset()
}
