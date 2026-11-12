package ru.dozor.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import ru.dozor.app.DozorApp
import ru.dozor.app.R
import ru.dozor.app.camera.CameraChoice
import ru.dozor.app.camera.CameraOption
import ru.dozor.app.service.WatchState
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Режим наведения: полноэкранное превью с сеткой, выбор камеры (в т.ч. широкоугольной)
 * и масштаба. Выбранные камера и масштаб используются во время дежурства.
 */
class AimActivity : AppCompatActivity() {

    private val app get() = application as DozorApp

    private lateinit var previewView: PreviewView
    private lateinit var zoomLabel: TextView
    private lateinit var zoomSlider: Slider
    private lateinit var cameraChips: ChipGroup

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private var cameras: List<CameraOption> = emptyList()

    private var cameraId: String? = null
    private var zoomRatio = 1f
    private var zoomMin = 1f
    private var zoomMax = 1f
    private var zoomReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (WatchState.status.value.isRunning) {
            Toast.makeText(this, R.string.aim_busy_duty, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_aim)

        previewView = findViewById(R.id.aimPreview)
        zoomLabel = findViewById(R.id.zoomLabel)
        zoomSlider = findViewById(R.id.zoomSlider)
        cameraChips = findViewById(R.id.cameraChips)

        val s = app.settings.get()
        cameraId = s.cameraId
        zoomRatio = s.zoomRatio

        zoomSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && zoomReady) setZoom(sliderToRatio(value))
        }
        findViewById<MaterialButton>(R.id.widestButton).setOnClickListener { goWidest() }
        findViewById<MaterialButton>(R.id.aimDone).setOnClickListener { finishWith(start = false) }
        findViewById<MaterialButton>(R.id.aimStart).setOnClickListener { finishWith(start = true) }
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            cameras = CameraChoice.backCameras(p)
            buildCameraChips()
            bind()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        unbind()
        super.onStop()
    }

    // ------------------------------------------------------------ камера

    private fun bind() {
        val p = provider ?: return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        unbind()
        val pv = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        try {
            val cam = p.bindToLifecycle(this, CameraChoice.selector(p, cameraId), pv)
            preview = pv
            camera = cam
            zoomReady = false
            cam.cameraInfo.zoomState.observe(this) { zs ->
                if (!zoomReady) {
                    zoomMin = zs.minZoomRatio
                    zoomMax = minOf(zs.maxZoomRatio, MAX_ZOOM)
                    zoomReady = true
                    val supported = zoomMax - zoomMin > 0.05f
                    zoomSlider.visibility = if (supported) View.VISIBLE else View.GONE
                    setZoom(zoomRatio)
                    if (supported) zoomSlider.value = ratioToSlider(zoomRatio)
                }
            }
        } catch (e: Exception) {
            zoomLabel.text = getString(R.string.err_camera_generic, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun unbind() {
        val pv = preview ?: return
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        runCatching { provider?.unbind(pv) }
        preview = null
        camera = null
    }

    private fun setZoom(ratio: Float) {
        val r = ratio.coerceIn(zoomMin, zoomMax)
        zoomRatio = r
        camera?.cameraControl?.setZoomRatio(r)
        zoomLabel.text = if (zoomMax - zoomMin > 0.05f) {
            getString(R.string.aim_zoom, r)
        } else {
            getString(R.string.aim_zoom_unsupported)
        }
    }

    /** Ползунок 0..100 → масштаб по логарифмической шкале (равномерно на глаз). */
    private fun sliderToRatio(v: Float): Float =
        if (zoomMax <= zoomMin) zoomMin else zoomMin * (zoomMax / zoomMin).pow(v / 100f)

    private fun ratioToSlider(r: Float): Float {
        if (zoomMax <= zoomMin) return 0f
        val v = 100f * ln(r.coerceIn(zoomMin, zoomMax) / zoomMin) / ln(zoomMax / zoomMin)
        return v.roundToInt().toFloat().coerceIn(0f, 100f)
    }

    private fun goWidest() {
        val widest = cameras.firstOrNull()
        if (widest != null && widest.id != cameraId && cameras.size > 1) {
            selectCamera(widest.id, initialZoom = 0f) // 0 → минимально возможный масштаб
            return
        }
        setZoom(zoomMin)
        zoomSlider.value = 0f
    }

    // ------------------------------------------------------------ выбор камеры

    private fun buildCameraChips() {
        val box = findViewById<View>(R.id.cameraBox)
        cameraChips.removeAllViews()
        if (cameras.size < 2) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        val p = provider ?: return
        val current = cameraId ?: runCatching {
            CameraChoice.idOf(CameraSelector.DEFAULT_BACK_CAMERA.filter(p.availableCameraInfos).first())
        }.getOrNull()
        cameras.forEachIndexed { i, opt ->
            val chip = Chip(this).apply {
                text = if (i == 0 && opt.fovDegrees > 0) {
                    getString(R.string.aim_camera_wide, opt.fovDegrees)
                } else if (opt.fovDegrees > 0) {
                    getString(R.string.aim_camera_item, opt.fovDegrees)
                } else {
                    getString(R.string.aim_camera) + " " + (i + 1)
                }
                isCheckable = true
                isChecked = opt.id == current
                setOnClickListener { selectCamera(opt.id) }
            }
            cameraChips.addView(chip)
        }
    }

    private fun selectCamera(id: String, initialZoom: Float = 1f) {
        if (id == cameraId) return
        cameraId = id
        zoomRatio = initialZoom
        for (i in 0 until cameraChips.childCount) {
            val chip = cameraChips.getChildAt(i) as Chip
            chip.isChecked = cameras.getOrNull(i)?.id == id
        }
        bind()
    }

    private fun finishWith(start: Boolean) {
        val id = cameraId
        val z = zoomRatio
        app.settings.update { it.copy(cameraId = id, zoomRatio = z) }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_START, start))
        finish()
    }

    companion object {
        const val EXTRA_START = "start_duty"
        private const val MAX_ZOOM = 5f
        fun intent(context: Context) = Intent(context, AimActivity::class.java)
    }
}
