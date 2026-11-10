package ru.dozor.app.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import ru.dozor.app.DozorApp
import ru.dozor.app.R
import ru.dozor.app.alert.AlarmPlayer
import ru.dozor.app.data.DozorSettings

class SettingsActivity : AppCompatActivity() {

    private val repo get() = (application as DozorApp).settings
    private lateinit var testPlayer: AlarmPlayer
    private var testing = false

    private lateinit var soundName: TextView
    private lateinit var soundTest: MaterialButton
    private lateinit var quietFrom: MaterialButton
    private lateinit var quietTo: MaterialButton

    private val ringtonePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data ?: return@registerForActivityResult
        if (res.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = IntentCompat.getParcelableExtra(data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        repo.update { it.copy(soundUri = uri) } // null («Без звука») → встроенная сирена
        renderSound()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        testPlayer = AlarmPlayer(this)

        val s = repo.get()

        // --- детекция
        val sensLabel = findViewById<TextView>(R.id.sensLabel)
        slider(R.id.sensSlider, s.sensitivity) { v ->
            val word = getString(
                when {
                    v < 34 -> R.string.sens_low
                    v < 67 -> R.string.sens_mid
                    else -> R.string.sens_high
                }
            )
            sensLabel.text = getString(R.string.s_sensitivity) + ": " + getString(R.string.s_sensitivity_value, v, word)
            repo.update { it.copy(sensitivity = v) }
        }

        val confirmLabel = findViewById<TextView>(R.id.confirmLabel)
        slider(R.id.confirmSlider, s.confirmFrames) { v ->
            confirmLabel.text = getString(R.string.s_confirm) + ": " + v
            repo.update { it.copy(confirmFrames = v) }
        }

        val fpsLabel = findViewById<TextView>(R.id.fpsLabel)
        slider(R.id.fpsSlider, s.fps) { v ->
            fpsLabel.text = getString(R.string.s_fps) + ": " + v
            repo.update { it.copy(fps = v) }
        }

        // --- тревога
        val cooldownLabel = findViewById<TextView>(R.id.cooldownLabel)
        slider(R.id.cooldownSlider, s.cooldownSec) { v ->
            cooldownLabel.text = getString(R.string.s_cooldown) + ": " + formatDuration(v)
            repo.update { it.copy(cooldownSec = v) }
        }

        val shotsLabel = findViewById<TextView>(R.id.shotsLabel)
        slider(R.id.shotsSlider, s.shotsPerEvent) { v ->
            shotsLabel.text = getString(R.string.s_shots) + ": " + v
            repo.update { it.copy(shotsPerEvent = v) }
        }

        soundName = findViewById(R.id.soundName)
        soundTest = findViewById(R.id.soundTest)
        renderSound()
        findViewById<MaterialButton>(R.id.soundChoose).setOnClickListener { chooseSound() }
        soundTest.setOnClickListener { toggleTest() }

        val durationLabel = findViewById<TextView>(R.id.durationLabel)
        slider(R.id.durationSlider, s.soundDurationSec) { v ->
            durationLabel.text = getString(R.string.s_sound_duration) + ": " + formatDuration(v)
            repo.update { it.copy(soundDurationSec = v) }
        }

        findViewById<MaterialSwitch>(R.id.maxVolumeSwitch).apply {
            isChecked = s.maxVolume
            setOnCheckedChangeListener { _, checked -> repo.update { it.copy(maxVolume = checked) } }
        }

        // --- тихие часы
        quietFrom = findViewById(R.id.quietFrom)
        quietTo = findViewById(R.id.quietTo)
        val quietSwitch = findViewById<MaterialSwitch>(R.id.quietSwitch)
        quietSwitch.isChecked = s.quietEnabled
        quietSwitch.setOnCheckedChangeListener { _, checked ->
            repo.update { it.copy(quietEnabled = checked) }
            renderQuiet()
        }
        quietFrom.setOnClickListener {
            pickTime(repo.get().quietStartMin) { m -> repo.update { it.copy(quietStartMin = m) }; renderQuiet() }
        }
        quietTo.setOnClickListener {
            pickTime(repo.get().quietEndMin) { m -> repo.update { it.copy(quietEndMin = m) }; renderQuiet() }
        }
        renderQuiet()

        // --- система
        findViewById<MaterialButton>(R.id.batteryButton).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        findViewById<MaterialButton>(R.id.fsiButton).setOnClickListener {
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", packageName, null))
            } else {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            runCatching { startActivity(intent) }
        }
    }

    override fun onStop() {
        if (testing) toggleTest()
        super.onStop()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** Настраивает ползунок, сразу вызывает [onValue] для подписи и далее — при каждом изменении. */
    private fun slider(id: Int, initial: Int, onValue: (Int) -> Unit) {
        val sl = findViewById<Slider>(id)
        val v = initial.toFloat().coerceIn(sl.valueFrom, sl.valueTo)
        // значение должно попадать в сетку stepSize
        val step = sl.stepSize
        sl.value = if (step > 0) sl.valueFrom + Math.round((v - sl.valueFrom) / step) * step else v
        onValue(sl.value.toInt())
        sl.addOnChangeListener { _, value, fromUser -> if (fromUser) onValue(value.toInt()) }
    }

    private fun formatDuration(sec: Int): String = when {
        sec < 60 -> getString(R.string.seconds, sec)
        sec % 60 == 0 -> getString(R.string.minutes, sec / 60)
        else -> getString(R.string.minutes_seconds, sec / 60, sec % 60)
    }

    private fun renderSound() {
        val uri = repo.get().soundUri
        soundName.text = if (uri == null) {
            getString(R.string.s_sound_builtin)
        } else {
            runCatching { RingtoneManager.getRingtone(this, uri)?.getTitle(this) }.getOrNull() ?: uri.toString()
        }
    }

    private fun chooseSound() {
        val options = arrayOf(getString(R.string.sound_builtin_option), getString(R.string.sound_system_option))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.s_sound)
            .setItems(options) { _, which ->
                if (which == 0) {
                    repo.update { it.copy(soundUri = null) }
                    renderSound()
                } else {
                    val current = repo.get().soundUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_NOTIFICATION)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.s_sound))
                    runCatching { ringtonePicker.launch(i) }
                }
            }
            .show()
    }

    private fun toggleTest() {
        testing = !testing
        if (testing) {
            val s: DozorSettings = repo.get()
            testPlayer.play(s.soundUri, 5, maxVolume = false)
            soundTest.setText(R.string.s_sound_stop)
            soundTest.postDelayed({ if (testing) toggleTest() }, 5000)
        } else {
            testPlayer.stop()
            soundTest.setText(R.string.s_sound_test)
        }
    }

    private fun renderQuiet() {
        val s = repo.get()
        quietFrom.text = getString(R.string.s_quiet_from, hhmm(s.quietStartMin))
        quietTo.text = getString(R.string.s_quiet_to, hhmm(s.quietEndMin))
        quietFrom.isEnabled = s.quietEnabled
        quietTo.isEnabled = s.quietEnabled
    }

    private fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    private fun pickTime(initialMin: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(this, { _, h, m -> onPicked(h * 60 + m) }, initialMin / 60, initialMin % 60, true).show()
    }
}
