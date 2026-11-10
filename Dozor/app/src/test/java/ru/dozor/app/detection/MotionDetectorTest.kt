package ru.dozor.app.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {

    private val w = 96
    private val h = 54
    private val params = DetectorParams(pixelThreshold = 25, areaRatio = 0.01f, confirmFrames = 3)

    private fun frame(fill: (x: Int, y: Int) -> Int) =
        LumaFrame(w, h, ByteArray(w * h) { i -> fill(i % w, i / w).coerceIn(0, 255).toByte() }, 0, 0)

    private val scene = frame { x, y -> 60 + (x * 3 + y * 2) % 80 }

    @Test
    fun staticSceneNeverTriggers() {
        val d = MotionDetector()
        repeat(20) { assertFalse(d.process(scene, params).triggered) }
    }

    @Test
    fun globalBrightnessFlickerIsIgnored() {
        val d = MotionDetector()
        d.process(scene, params)
        repeat(20) { i ->
            val shift = if (i % 2 == 0) 40 else -30 // монитор «мигает»
            val f = frame { x, y -> 60 + (x * 3 + y * 2) % 80 + shift }
            val r = d.process(f, params)
            assertFalse("flicker frame $i", r.alarmFrame)
        }
    }

    @Test
    fun movingObjectTriggersAfterConfirmFrames() {
        val d = MotionDetector()
        d.process(scene, params)
        val results = (0 until 3).map { step ->
            // светлый «человек» 10x20 точек, смещается на 8 точек каждый кадр
            val x0 = 10 + step * 8
            d.process(frame { x, y ->
                if (x in x0 until x0 + 10 && y in 20 until 40) 230 else 60 + (x * 3 + y * 2) % 80
            }, params)
        }
        assertTrue(results[0].alarmFrame)
        assertFalse(results[0].triggered)
        assertFalse(results[1].triggered)
        assertTrue(results[2].triggered)
    }

    @Test
    fun singleNoisyPixelsAreFiltered() {
        val d = MotionDetector()
        d.process(scene, params)
        // 30 разрозненных «шумных» точек без соседей
        val noisy = frame { x, y ->
            val base = 60 + (x * 3 + y * 2) % 80
            if (x % 9 == 0 && y % 9 == 0) base + 120 else base
        }
        val r = d.process(noisy, params)
        assertEquals(0f, r.changeRatio, 0.0001f)
    }
}
