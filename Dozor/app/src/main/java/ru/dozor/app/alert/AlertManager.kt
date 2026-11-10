package ru.dozor.app.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import ru.dozor.app.DozorApp
import ru.dozor.app.data.AlarmEvent
import ru.dozor.app.data.SettingsRepository

/** Звук + уведомление с учётом «тихих часов». */
class AlertManager(private val context: Context, private val settings: SettingsRepository) {

    val player = AlarmPlayer(context)

    fun fire(event: AlarmEvent, picture: Bitmap?, number: Int) {
        val s = settings.get()
        val quiet = s.isQuietAt(event.timestamp)
        Notifications.showAlarm(context, event, picture, quiet, number)
        if (!quiet) player.play(s.soundUri, s.soundDurationSec, s.maxVolume)
    }

    fun silence() = player.stop()
}

/** Кнопка «Заглушить» в уведомлении. */
class SilenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as DozorApp).alerts.silence()
    }
}
