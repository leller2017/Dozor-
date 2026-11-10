package ru.dozor.app.detection

import kotlin.math.abs

/**
 * Детектор движения по разнице соседних кадров.
 *
 * Защита от ложных тревог при съёмке монитора:
 *  1. Кадр заранее уменьшен с усреднением блоков (≈96 точек по ширине) —
 *     это гасит шум матрицы и муар от пикселей монитора.
 *  2. Компенсация общей яркости: из разницы вычитается её среднее значение,
 *     поэтому равномерное мерцание/смена яркости монитора не даёт изменений.
 *  3. Подтверждение: тревога засчитывается только после N тревожных кадров подряд.
 */
class MotionDetector : Detector {

    private var prev: IntArray? = null
    private var prevW = 0
    private var prevH = 0
    private var streak = 0

    override fun process(frame: LumaFrame, params: DetectorParams): DetectionResult {
        val n = frame.width * frame.height
        val cur = IntArray(n) { frame.luma[it].toInt() and 0xFF }
        val p = prev

        if (p == null || prevW != frame.width || prevH != frame.height) {
            prev = cur
            prevW = frame.width
            prevH = frame.height
            streak = 0
            return DetectionResult(0f, false, false, 0, null)
        }

        // Средний сдвиг яркости по всему кадру — это мерцание/автоэкспозиция, а не движение.
        var sum = 0L
        for (i in 0 until n) sum += cur[i] - p[i]
        val meanShift = (sum / n).toInt()

        val mask = ByteArray(n)
        val thr = params.pixelThreshold
        for (i in 0 until n) {
            if (abs(cur[i] - p[i] - meanShift) > thr) {
                mask[i] = 1
            }
        }
        // Одиночные «шумные» точки не считаем: пиксель засчитывается, если рядом есть ещё изменения.
        val filtered = removeIsolated(mask, frame.width, frame.height)
        val ratio = filtered.toFloat() / n

        val alarmFrame = ratio >= params.areaRatio
        streak = if (alarmFrame) streak + 1 else 0
        val triggered = streak >= params.confirmFrames.coerceAtLeast(1)
        if (triggered) streak = 0

        prev = cur
        return DetectionResult(ratio, alarmFrame, triggered, streak, mask)
    }

    override fun reset() {
        prev = null
        streak = 0
    }

    /** Обнуляет в маске точки без соседей-изменений (8-связность). Возвращает число оставшихся. */
    private fun removeIsolated(mask: ByteArray, w: Int, h: Int): Int {
        var count = 0
        val keep = BooleanArray(mask.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (mask[i].toInt() == 0) continue
                var neighbours = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        if (mask[yy * w + xx].toInt() != 0) neighbours++
                    }
                }
                if (neighbours >= 1) {
                    keep[i] = true
                    count++
                }
            }
        }
        for (i in mask.indices) if (!keep[i]) mask[i] = 0
        return count
    }
}
