package ru.dozor.app.camera

import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlin.math.atan
import kotlin.math.roundToInt

/** Одна из задних камер телефона. */
data class CameraOption(val id: String, val fovDegrees: Int, val info: CameraInfo)

/**
 * Выбор камеры и масштаба. На многих телефонах есть широкоугольная камера:
 * она либо видна отдельной камерой (выбираем её по id), либо доступна
 * через масштаб меньше 1× у основной камеры.
 */
@OptIn(markerClass = [ExperimentalCamera2Interop::class])
object CameraChoice {

    fun idOf(info: CameraInfo): String? = runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()

    /** Задние камеры, отсортированные от самой широкой к самой узкой. */
    fun backCameras(provider: ProcessCameraProvider): List<CameraOption> =
        provider.availableCameraInfos
            .filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            .mapNotNull { info -> idOf(info)?.let { CameraOption(it, fovOf(info), info) } }
            .distinctBy { it.id }
            .sortedByDescending { it.fovDegrees }

    /** Селектор для сохранённой камеры; если её нет — основная задняя. */
    fun selector(provider: ProcessCameraProvider, cameraId: String?): CameraSelector {
        if (cameraId != null) {
            val byId = CameraSelector.Builder()
                .addCameraFilter { infos -> infos.filter { idOf(it) == cameraId } }
                .build()
            if (runCatching { provider.hasCamera(byId) }.getOrDefault(false)) return byId
        }
        return CameraSelector.DEFAULT_BACK_CAMERA
    }

    /** Применяет масштаб с учётом возможностей камеры. */
    fun applyZoom(camera: Camera, ratio: Float) {
        val zs = camera.cameraInfo.zoomState.value
        val r = if (zs != null) ratio.coerceIn(zs.minZoomRatio, zs.maxZoomRatio) else ratio
        camera.cameraControl.setZoomRatio(r)
    }

    /** Горизонтальный угол обзора, градусы (по фокусному расстоянию и размеру матрицы). */
    private fun fovOf(info: CameraInfo): Int = runCatching {
        val c = Camera2CameraInfo.from(info)
        val focal = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
        val size = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (focal == null || size == null || focal <= 0f) 0
        else Math.toDegrees(2 * atan(size.width / (2.0 * focal))).roundToInt()
    }.getOrDefault(0)
}
