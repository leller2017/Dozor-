package ru.dozor.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object Images {
    /** Декодирует JPEG, уменьшая его примерно до нужной ширины (экономия памяти). */
    fun decode(file: File, reqWidth: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqWidth) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
