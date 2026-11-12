package ru.dozor.app.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import ru.dozor.app.DozorApp
import ru.dozor.app.R
import ru.dozor.app.alert.Notifications
import ru.dozor.app.camera.CameraChoice
import ru.dozor.app.camera.FrameUtils
import ru.dozor.app.camera.ShotWriter
import ru.dozor.app.data.AlarmEvent
import ru.dozor.app.data.DozorSettings
import ru.dozor.app.detection.Detector
import ru.dozor.app.detection.DetectionResult
import ru.dozor.app.detection.MotionDetector
import ru.dozor.app.service.WatchState.Mode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Сервис дежурства (foreground, тип camera): держит камеру, анализирует кадры,
 * фиксирует срабатывания. Продолжает работу при выключенном экране.
 */
class WatchService : LifecycleService() {

    private val app: DozorApp get() = application as DozorApp
    private val main = Handler(Looper.getMainLooper())

    private lateinit var analysisExecutor: ExecutorService
    private lateinit var ioExecutor: ExecutorService

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val detector: Detector = MotionDetector()

    @Volatile private var running = false
    @Volatile private var warmupUntil = 0L
    @Volatile private var cameraBusy = false
    @Volatile private var sessionAlarms = 0

    // Поля ниже трогаются только из потока анализа.
    private var lastFrameAt = 0L
    private var lastPreviewAt = 0L
    private var lastAlarmAt = 0L
    private var lastMode: Mode? = null
    private var lastDutyAlarms = -1
    private var burstEvent: AlarmEvent? = null
    private var burstIndex = 0
    private var burstTotal = 0

    override fun onCreate() {
        super.onCreate()
        analysisExecutor = Executors.newSingleThreadExecutor()
        ioExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopWatch()
            else -> startWatch()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- запуск / остановка

    private fun startWatch() {
        if (running) return

        try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_DUTY,
                Notifications.duty(this, getString(R.string.duty_starting)),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            fail(getString(R.string.err_foreground))
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fail(getString(R.string.err_no_camera_permission))
            return
        }

        running = true
        cameraBusy = false
        sessionAlarms = 0
        analysisExecutor.execute {
            detector.reset()
            lastFrameAt = 0L
            lastAlarmAt = 0L
            lastMode = null
            lastDutyAlarms = -1
            burstEvent = null
        }
        WatchState.update { WatchState.Status(mode = Mode.STARTING) }

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Dozor:watch").apply {
            setReferenceCounted(false)
            acquire(MAX_DUTY_MS)
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                bindCamera(future.get())
            } catch (e: Exception) {
                Log.e(TAG, "camera provider", e)
                fail(getString(R.string.err_camera_generic, e.message ?: e.javaClass.simpleName))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(p: ProcessCameraProvider) {
        if (!running) return
        provider = p

        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()

        val a = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        a.setAnalyzer(analysisExecutor) { image -> analyze(image) }
        analysis = a

        try {
            // Снимаем всё, что было привязано (в т.ч. превью главного экрана для наведения).
            p.unbindAll()
            val settings = app.settings.get()
            val selector = CameraChoice.selector(p, settings.cameraId)
            if (!p.hasCamera(selector)) {
                fail(getString(R.string.err_no_back_camera))
                return
            }
            val cam = p.bindToLifecycle(this, selector, a)
            camera = cam
            // Тот же угол обзора, что выставлен в режиме наведения.
            CameraChoice.applyZoom(cam, settings.zoomRatio)
            warmupUntil = SystemClock.elapsedRealtime() + WARMUP_MS
            cam.cameraInfo.cameraState.observe(this) { onCameraState(it) }
        } catch (e: Exception) {
            Log.e(TAG, "bind failed", e)
            fail(getString(R.string.err_camera_generic, e.message ?: e.javaClass.simpleName))
        }
    }

    /** Реакция на состояние камеры, в т.ч. «камера занята другим приложением». */
    private fun onCameraState(state: CameraState) {
        if (!running) return
        val err = state.error
        when {
            err != null && (err.code == CameraState.ERROR_CAMERA_IN_USE ||
                err.code == CameraState.ERROR_MAX_CAMERAS_IN_USE) -> {
                // CameraX сам переоткроет камеру, когда она освободится.
                cameraBusy = true
                WatchState.update { it.copy(mode = Mode.CAMERA_BUSY, message = getString(R.string.camera_busy)) }
                Notifications.updateDuty(this, getString(R.string.camera_busy))
            }
            err != null && err.code == CameraState.ERROR_CAMERA_DISABLED -> {
                fail(getString(R.string.err_camera_disabled))
            }
            err != null && err.type == CameraState.ErrorType.CRITICAL -> {
                fail(getString(R.string.err_camera_generic, "код ${err.code}"))
            }
            err != null -> {
                WatchState.update { it.copy(mode = Mode.CAMERA_BUSY, message = getString(R.string.camera_recovering)) }
            }
            state.type == CameraState.Type.OPEN -> {
                // Диапазон масштаба известен только после открытия камеры — применяем ещё раз.
                camera?.let { CameraChoice.applyZoom(it, app.settings.get().zoomRatio) }
                if (cameraBusy) {
                    cameraBusy = false
                    // Сцена могла поменяться, пока камеру держал кто-то другой, — начинаем с чистого листа.
                    analysisExecutor.execute { detector.reset(); lastMode = null }
                    warmupUntil = SystemClock.elapsedRealtime() + WARMUP_MS
                }
            }
        }
    }

    private fun stopWatch() {
        val wasRunning = running
        running = false
        releaseCamera()
        app.alerts.silence()
        WatchState.update { WatchState.Status(mode = Mode.IDLE, sessionAlarms = sessionAlarms) }
        WatchState.setPreview(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (wasRunning) Log.i(TAG, "duty stopped, alarms=$sessionAlarms")
    }

    private fun fail(message: String) {
        running = false
        releaseCamera()
        WatchState.update { WatchState.Status(mode = Mode.ERROR, message = message, sessionAlarms = sessionAlarms) }
        WatchState.setPreview(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseCamera() {
        main.removeCallbacksAndMessages(null)
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        analysis?.clearAnalyzer()
        runCatching { provider?.unbindAll() }
        analysis = null
        camera = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        if (running) {
            running = false
            releaseCamera()
            WatchState.update { WatchState.Status(mode = Mode.IDLE, sessionAlarms = sessionAlarms) }
        }
        analysisExecutor.shutdown()
        ioExecutor.shutdown() // дописывает уже поставленные в очередь снимки
        super.onDestroy()
    }

    // ---------------------------------------------------------------- анализ кадров

    private fun analyze(image: ImageProxy) {
        try {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val s = app.settings.get()

            // Прореживание: анализируем 2–5 кадров в секунду, остальные сразу отпускаем.
            val interval = 1000L / s.fps.coerceIn(1, 10)
            if (now - lastFrameAt < interval) return
            lastFrameAt = now
            val wall = System.currentTimeMillis()

            // Продолжение серии снимков после срабатывания.
            burstEvent?.let { saveShot(it, image, wall) }

            val frame = FrameUtils.toLumaGrid(image, GRID_WIDTH, wall)
            val params = s.detectorParams()
            val r = detector.process(frame, params)

            if (now - lastPreviewAt >= PREVIEW_INTERVAL_MS) {
                lastPreviewAt = now
                WatchState.setPreview(FrameUtils.visualize(frame, r.mask))
            }

            val warm = now < warmupUntil
            val cooldownMs = s.cooldownSec * 1000L
            val inCooldown = lastAlarmAt > 0 && now - lastAlarmAt < cooldownMs

            if (r.triggered && !warm && !inCooldown && burstEvent == null) {
                startAlarm(image, r, wall, now, s)
            }

            publishStatus(r, params.areaRatio, params.confirmFrames, now, cooldownMs, warm)
        } catch (e: Exception) {
            Log.e(TAG, "analyze", e)
        } finally {
            image.close()
        }
    }

    private fun startAlarm(image: ImageProxy, r: DetectionResult, wall: Long, now: Long, s: DozorSettings) {
        lastAlarmAt = now
        sessionAlarms++
        val onShift = runCatching {
            app.schedule.onShiftAt(wall).joinToString(", ") { it.shortName }
        }.getOrDefault("")
        val event = app.events.create(wall, r.changeRatio, onShift)
        burstEvent = event
        burstIndex = 0
        burstTotal = s.shotsPerEvent.coerceIn(3, 5)
        Log.i(TAG, "ALARM #$sessionAlarms ratio=${r.changeRatio}")
        saveShot(event, image, wall)
    }

    /** Копирует кадр и отдаёт его на сохранение в фоновый поток. */
    private fun saveShot(event: AlarmEvent, image: ImageProxy, wall: Long) {
        burstIndex++
        val index = burstIndex
        val total = burstTotal
        val number = sessionAlarms
        val last = index >= total
        if (last) burstEvent = null

        val nv21 = try {
            FrameUtils.toNv21(image, wall)
        } catch (e: Exception) {
            Log.e(TAG, "toNv21", e)
            null
        }

        ioExecutor.execute {
            val picture = nv21?.let {
                runCatching { ShotWriter.save(event, index, total, it) }
                    .onFailure { e -> Log.e(TAG, "save shot", e) }
                    .getOrNull()
            }
            if (index == 1) {
                app.events.notifyChanged()
                app.publishers.forEach { p ->
                    runCatching { p.onAlarm(event, picture, number) }.onFailure { Log.e(TAG, "publisher", it) }
                }
            }
            if (last) {
                app.events.notifyChanged()
                app.publishers.forEach { p ->
                    runCatching { p.onEventComplete(event) }.onFailure { Log.e(TAG, "publisher", it) }
                }
            }
        }
    }

    private fun publishStatus(
        r: DetectionResult, threshold: Float, confirm: Int, now: Long, cooldownMs: Long, warm: Boolean,
    ) {
        if (cameraBusy) return
        val sinceAlarm = now - lastAlarmAt
        val mode = when {
            warm -> Mode.WARMUP
            lastAlarmAt > 0 && sinceAlarm < ALARM_DISPLAY_MS -> Mode.ALARM
            lastAlarmAt > 0 && sinceAlarm < cooldownMs -> Mode.COOLDOWN
            else -> Mode.WATCHING
        }
        val cooldownLeft = if (mode == Mode.COOLDOWN || mode == Mode.ALARM) {
            ((cooldownMs - sinceAlarm).coerceAtLeast(0) / 1000).toInt()
        } else 0
        val alarms = sessionAlarms
        WatchState.update {
            it.copy(
                mode = mode,
                message = "",
                sessionAlarms = alarms,
                changeRatio = r.changeRatio,
                threshold = threshold,
                streak = r.streak,
                confirmFrames = confirm,
                cooldownLeftSec = cooldownLeft,
            )
        }
        if (mode != lastMode || alarms != lastDutyAlarms) {
            lastMode = mode
            lastDutyAlarms = alarms
            val text = when (mode) {
                Mode.WARMUP -> getString(R.string.duty_starting)
                else -> getString(R.string.duty_text, alarms)
            }
            Notifications.updateDuty(this, text)
        }
    }

    companion object {
        private const val TAG = "WatchService"
        const val ACTION_START = "ru.dozor.app.START"
        const val ACTION_STOP = "ru.dozor.app.STOP"

        private const val GRID_WIDTH = 96
        private const val WARMUP_MS = 2500L
        private const val PREVIEW_INTERVAL_MS = 250L
        private const val ALARM_DISPLAY_MS = 5000L
        private const val MAX_DUTY_MS = 24L * 60 * 60 * 1000

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, WatchService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WatchService::class.java).setAction(ACTION_STOP))
        }
    }
}
