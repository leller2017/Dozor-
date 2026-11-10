package ru.dozor.app.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import ru.dozor.app.detection.LumaFrame
import kotlin.math.max

/** Кадр в формате NV21 — копия данных, которую можно обрабатывать после закрытия ImageProxy. */
class Nv21Frame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val timestampMs: Long,
)

object FrameUtils {

    /**
     * Уменьшает Y-плоскость (яркость) до сетки шириной [gridWidth] с усреднением блоков.
     * Выбираем каждый step-й пиксель внутри блока — быстро и достаточно для детекции.
     */
    fun toLumaGrid(image: ImageProxy, gridWidth: Int, timestampMs: Long): LumaFrame {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height

        val gw = gridWidth.coerceAtMost(w)
        val gh = max(1, h * gw / w)
        val bw = max(1, w / gw)
        val bh = max(1, h / gh)
        val step = max(1, minOf(bw, bh) / 4)
        val out = ByteArray(gw * gh)

        for (gy in 0 until gh) {
            val y0 = gy * bh
            for (gx in 0 until gw) {
                val x0 = gx * bw
                var sum = 0
                var cnt = 0
                var y = y0
                while (y < y0 + bh) {
                    val rowBase = y * rowStride
                    var x = x0
                    while (x < x0 + bw) {
                        sum += buf.get(rowBase + x * pixelStride).toInt() and 0xFF
                        cnt++
                        x += step
                    }
                    y += step
                }
                out[gy * gw + gx] = (if (cnt > 0) sum / cnt else 0).toByte()
            }
        }
        return LumaFrame(gw, gh, out, image.imageInfo.rotationDegrees, timestampMs)
    }

    /** YUV_420_888 → NV21 с учётом rowStride/pixelStride (у разных телефонов они разные). */
    fun toNv21(image: ImageProxy, timestampMs: Long): Nv21Frame {
        val w = image.width
        val h = image.height
        val out = ByteArray(w * h * 3 / 2)

        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer.duplicate()
        var pos = 0
        if (yPlane.pixelStride == 1) {
            for (row in 0 until h) {
                yBuf.position(row * yPlane.rowStride)
                yBuf.get(out, pos, w)
                pos += w
            }
        } else {
            for (row in 0 until h) {
                val base = row * yPlane.rowStride
                for (col in 0 until w) out[pos++] = yBuf.get(base + col * yPlane.pixelStride)
            }
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer.duplicate()
        val vBuf = vPlane.buffer.duplicate()
        val cw = w / 2
        val ch = h / 2
        for (row in 0 until ch) {
            val uBase = row * uPlane.rowStride
            val vBase = row * vPlane.rowStride
            for (col in 0 until cw) {
                out[pos++] = vBuf.get(vBase + col * vPlane.pixelStride)
                out[pos++] = uBuf.get(uBase + col * uPlane.pixelStride)
            }
        }
        return Nv21Frame(out, w, h, image.imageInfo.rotationDegrees, timestampMs)
    }

    /** Картинка «что видит сторож»: серый кадр + изменившиеся точки янтарным. */
    fun visualize(frame: LumaFrame, mask: ByteArray?): Bitmap {
        val n = frame.width * frame.height
        val pixels = IntArray(n)
        for (i in 0 until n) {
            val l = frame.luma[i].toInt() and 0xFF
            pixels[i] = if (mask != null && mask[i].toInt() != 0) {
                Color.rgb(255, 179, 0)
            } else {
                val d = l * 3 / 4 // чуть притемним, чтобы янтарь читался
                Color.rgb(d, d, d)
            }
        }
        val bmp = Bitmap.createBitmap(pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        return rotate(bmp, frame.rotationDegrees)
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
}
