package ru.dozor.app.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.dozor.app.DozorApp
import ru.dozor.app.R
import ru.dozor.app.service.WatchService
import ru.dozor.app.service.WatchState
import ru.dozor.app.service.WatchState.Mode

class MainActivity : AppCompatActivity() {

    private val app get() = application as DozorApp

    private lateinit var previewView: PreviewView
    private lateinit var analysisView: ImageView
    private lateinit var overlayHint: TextView
    private lateinit var cameraMessageBox: View
    private lateinit var cameraMessage: TextView
    private lateinit var grantButton: MaterialButton
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var levelBar: LinearProgressIndicator
    private lateinit var sessionCount: TextView
    private lateinit var totalCount: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var pendingStart = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (!hasPermission(Manifest.permission.CAMERA)) {
            pendingStart = false
            showCameraPermissionProblem()
            return@registerForActivityResult
        }
        if (Build.VERSION.SDK_INT >= 33 && result[Manifest.permission.POST_NOTIFICATIONS] == false) {
            Toast.makeText(this, R.string.perm_notif_denied, Toast.LENGTH_LONG).show()
        }
        render(WatchState.status.value)
        bindPreviewIfIdle()
        if (pendingStart) {
            pendingStart = false
            doStart()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        analysisView = findViewById(R.id.analysisView)
        overlayHint = findViewById(R.id.overlayHint)
        cameraMessageBox = findViewById(R.id.cameraMessageBox)
        cameraMessage = findViewById(R.id.cameraMessage)
        grantButton = findViewById(R.id.grantButton)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        levelBar = findViewById(R.id.levelBar)
        sessionCount = findViewById(R.id.sessionCount)
        totalCount = findViewById(R.id.totalCount)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        startButton.setOnClickListener { requestStart() }
        stopButton.setOnClickListener { WatchService.stop(this) }
        grantButton.setOnClickListener { permissionLauncher.launch(requiredPermissions()) }
        findViewById<View>(R.id.journalButton).setOnClickListener {
            startActivity(Intent(this, JournalActivity::class.java))
        }
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { WatchState.status.collect { render(it) } }
                launch {
                    WatchState.preview.collect { bmp -> if (bmp != null) analysisView.setImageBitmap(bmp) }
                }
                launch {
                    app.events.version.collect {
                        val total = withContext(Dispatchers.IO) { app.events.count() }
                        totalCount.text = getString(R.string.total_count, total)
                    }
                }
            }
        }

        if (savedInstanceState == null && !hasPermission(Manifest.permission.CAMERA)) {
            permissionLauncher.launch(requiredPermissions())
        }
    }

    override fun onStart() {
        super.onStart()
        bindPreviewIfIdle()
    }

    override fun onStop() {
        // Обязательно отвязываем превью: иначе при возврате на экран CameraX
        // отдал бы камеру активности и остановил анализ в сервисе.
        unbindPreview()
        super.onStop()
    }

    // ------------------------------------------------------------ запуск дежурства

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestStart() {
        val missing = requiredPermissions().filterNot { hasPermission(it) }
        if (missing.isNotEmpty()) {
            pendingStart = true
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        doStart()
    }

    private fun doStart() {
        unbindPreview()
        WatchService.start(this)
        suggestFullScreenPermission()
    }

    private fun showCameraPermissionProblem() {
        render(WatchState.status.value)
        if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            // «Больше не спрашивать» — отправляем в настройки приложения.
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.perm_camera_title)
                .setMessage(R.string.perm_camera_text)
                .setPositiveButton(R.string.open_settings) { _, _ -> openAppSettings() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }

    private fun suggestFullScreenPermission() {
        if (Build.VERSION.SDK_INT < 34) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.canUseFullScreenIntent()) return
        Snackbar.make(startButton, R.string.fsi_hint, Snackbar.LENGTH_LONG)
            .setAction(R.string.allow) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", packageName, null))
                )
            }
            .show()
    }

    // ------------------------------------------------------------ превью для наведения

    private fun bindPreviewIfIdle() {
        if (!hasPermission(Manifest.permission.CAMERA)) return
        if (WatchState.status.value.isRunning || previewUseCase != null) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@addListener
            if (WatchState.status.value.isRunning || previewUseCase != null) return@addListener
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            cameraProvider = provider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            try {
                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                previewUseCase = preview
                camera.cameraInfo.cameraState.observe(this) { state -> onPreviewCameraState(state) }
            } catch (e: Exception) {
                showCameraMessage(getString(R.string.err_camera_generic, e.message ?: e.javaClass.simpleName), false)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindPreview() {
        val preview = previewUseCase ?: return
        runCatching { cameraProvider?.unbind(preview) }
        previewUseCase = null
    }

    private fun onPreviewCameraState(state: CameraState) {
        if (WatchState.status.value.isRunning) return
        val code = state.error?.code
        if (code == CameraState.ERROR_CAMERA_IN_USE || code == CameraState.ERROR_MAX_CAMERAS_IN_USE) {
            showCameraMessage(getString(R.string.preview_busy), false)
        } else if (state.type == CameraState.Type.OPEN) {
            cameraMessageBox.visibility = View.GONE
        }
    }

    private fun showCameraMessage(text: String, showGrant: Boolean) {
        cameraMessageBox.visibility = View.VISIBLE
        cameraMessage.text = text
        grantButton.visibility = if (showGrant) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------ отрисовка состояния

    private var lastRunning: Boolean? = null

    private fun render(s: WatchState.Status) {
        val running = s.isRunning

        if (lastRunning != running) {
            lastRunning = running
            previewView.visibility = if (running) View.INVISIBLE else View.VISIBLE
            analysisView.visibility = if (running) View.VISIBLE else View.GONE
            overlayHint.setText(if (running) R.string.watch_hint else R.string.aim_hint)
            if (!running) {
                analysisView.setImageDrawable(null)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) bindPreviewIfIdle()
            }
        }

        if (!hasPermission(Manifest.permission.CAMERA)) {
            showCameraMessage(getString(R.string.need_camera), true)
        } else if (running && s.mode == Mode.CAMERA_BUSY) {
            showCameraMessage(s.message, false)
        } else if (running || cameraMessage.text == getString(R.string.need_camera)) {
            cameraMessageBox.visibility = View.GONE
        }

        val (title, color) = when (s.mode) {
            Mode.IDLE -> getString(R.string.mode_idle) to R.color.text_dim
            Mode.STARTING -> getString(R.string.mode_starting) to R.color.blue
            Mode.WARMUP -> getString(R.string.mode_warmup) to R.color.blue
            Mode.WATCHING -> getString(R.string.mode_watching) to R.color.green
            Mode.ALARM -> getString(R.string.mode_alarm) to R.color.red
            Mode.COOLDOWN -> getString(R.string.mode_cooldown) to R.color.amber
            Mode.CAMERA_BUSY -> getString(R.string.mode_busy) to R.color.amber
            Mode.ERROR -> getString(R.string.mode_error) to R.color.red
        }
        statusText.text = title
        statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))

        statusDetail.text = when (s.mode) {
            Mode.IDLE -> getString(R.string.detail_idle)
            Mode.ERROR, Mode.CAMERA_BUSY -> s.message
            Mode.COOLDOWN, Mode.ALARM -> getString(R.string.detail_cooldown, s.cooldownLeftSec)
            Mode.WATCHING -> getString(
                R.string.detail_watching, s.changeRatio * 100, s.threshold * 100, s.streak, s.confirmFrames
            )
            else -> ""
        }

        levelBar.visibility = if (running) View.VISIBLE else View.INVISIBLE
        val level = if (s.threshold > 0) (s.changeRatio / s.threshold * 100).toInt().coerceIn(0, 100) else 0
        levelBar.setProgressCompat(level, true)
        levelBar.setIndicatorColor(
            ContextCompat.getColor(this, if (level >= 100) R.color.red else R.color.amber)
        )

        sessionCount.text = getString(R.string.session_count, s.sessionAlarms)
        startButton.isEnabled = !running
        stopButton.isEnabled = running
    }
}
