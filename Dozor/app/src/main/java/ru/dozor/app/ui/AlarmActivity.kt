package ru.dozor.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.dozor.app.DozorApp
import ru.dozor.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Полноэкранная тревога: показывается поверх экрана блокировки и включает экран. */
class AlarmActivity : AppCompatActivity() {

    private val app get() = application as DozorApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)
        bind(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    private fun bind(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val shotView = findViewById<ImageView>(R.id.alarmShot)
        val timeView = findViewById<TextView>(R.id.alarmTime)

        findViewById<TextView>(R.id.silenceButton).setOnClickListener {
            app.alerts.silence()
            finish()
        }
        findViewById<TextView>(R.id.openButton).setOnClickListener {
            app.alerts.silence()
            startActivity(EventActivity.intent(this, id))
            finish()
        }

        lifecycleScope.launch {
            // Первый снимок может дописываться ещё долю секунды — подождём.
            repeat(10) {
                val ev = withContext(Dispatchers.IO) { app.events.get(id) }
                if (ev != null) {
                    timeView.text = SimpleDateFormat("HH:mm:ss, d MMMM", Locale("ru")).format(Date(ev.timestamp))
                    val first = ev.shots.firstOrNull()
                    if (first != null) {
                        val bmp = withContext(Dispatchers.IO) { Images.decode(first, resources.displayMetrics.widthPixels) }
                        if (bmp != null) {
                            shotView.setImageBitmap(bmp)
                            return@launch
                        }
                    }
                }
                delay(300)
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "event_id"
        fun intent(context: Context, id: String): Intent =
            Intent(context, AlarmActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
