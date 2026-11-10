package ru.dozor.app.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.YuvImage
import ru.dozor.app.data.AlarmEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Сохраняет снимки серии: JPEG с меткой времени + миниатюра для журнала. */
object ShotWriter {

    private val stampFormat = SimpleDateFormat("dd.MM.yyyy  HH:mm:ss.SSS", Locale("ru"))

    /**
     * @return уменьшенная картинка (для уведомления), если это первый снимок серии, иначе null.
     */
    fun save(event: AlarmEvent, index: Int, total: Int, frame: Nv21Frame): Bitmap? {
        val jpegBytes = ByteArrayOutputStream().use { bos ->
            YuvImage(frame.data, ImageFormat.NV21, frame.width, frame.height, null)
                .compressToJpeg(Rect(0, 0, frame.width, frame.height), 92, bos)
            bos.toByteArray()
        }
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
        val rotated = FrameUtils.rotate(decoded, frame.rotationDegrees)
        val bmp = if (rotated.isMutable) rotated else rotated.copy(Bitmap.Config.ARGB_8888, true)

        stamp(bmp, "ДОЗОР  ${stampFormat.format(Date(frame.timestampMs))}  [$index/$total]")

        FileOutputStream(File(event.dir, "shot_%02d.jpg".format(index))).use {
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, it)
        }

        if (index != 1) return null
        val tw = 360
        val th = (bmp.height * tw / bmp.width.toFloat()).toInt().coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(bmp, tw, th, true)
        FileOutputStream(event.thumb).use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return thumb
    }

    private fun stamp(bmp: Bitmap, text: String) {
        val canvas = Canvas(bmp)
        val size = bmp.width / 34f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 179, 0)
            textSize = size
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val pad = size * 0.5f
        val textW = paint.measureText(text)
        val bg = Paint().apply { color = Color.argb(170, 0, 0, 0) }
        val bottom = bmp.height.toFloat()
        canvas.drawRect(0f, bottom - size - pad * 2, textW + pad * 2, bottom, bg)
        canvas.drawText(text, pad, bottom - pad - size * 0.15f, paint)
    }
}
