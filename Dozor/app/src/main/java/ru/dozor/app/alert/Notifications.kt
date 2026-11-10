package ru.dozor.app.alert

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ru.dozor.app.R
import ru.dozor.app.data.AlarmEvent
import ru.dozor.app.service.WatchService
import ru.dozor.app.ui.AlarmActivity
import ru.dozor.app.ui.EventActivity
import ru.dozor.app.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notifications {

    const val CH_DUTY = "duty"
    const val CH_ALARM = "alarm"
    const val CH_ALARM_QUIET = "alarm_quiet"

    const val ID_DUTY = 1
    private const val ID_ALARM_BASE = 1000

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale("ru"))

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)

        val duty = NotificationChannel(CH_DUTY, context.getString(R.string.ch_duty), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.ch_duty_desc)
            setShowBadge(false)
        }

        // Звук играет сам «Дозор» через поток будильника (громче и дольше),
        // поэтому у канала звук выключен — иначе было бы два сигнала.
        val alarm = NotificationChannel(CH_ALARM, context.getString(R.string.ch_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.ch_alarm_desc)
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }

        val quiet = NotificationChannel(CH_ALARM_QUIET, context.getString(R.string.ch_alarm_quiet), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.ch_alarm_quiet_desc)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        nm.createNotificationChannels(listOf(duty, alarm, quiet))
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Постоянное уведомление сервиса дежурства. */
    fun duty(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            context, 1, Intent(context, WatchService::class.java).setAction(WatchService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CH_DUTY)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle(context.getString(R.string.duty_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.stop), stop)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun updateDuty(context: Context, text: String) {
        if (!canNotify(context)) return
        NotificationManagerCompat.from(context).notify(ID_DUTY, duty(context, text))
    }

    @SuppressLint("MissingPermission")
    fun showAlarm(context: Context, event: AlarmEvent, picture: Bitmap?, quiet: Boolean, number: Int) {
        if (!canNotify(context)) return

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openEvent = PendingIntent.getActivity(
            context, 100, EventActivity.intent(context, event.id), flags
        )
        val silence = PendingIntent.getBroadcast(
            context, 101, Intent(context, SilenceReceiver::class.java), flags
        )
        val fullScreen = PendingIntent.getActivity(
            context, 102, AlarmActivity.intent(context, event.id), flags
        )

        val time = timeFormat.format(Date(event.timestamp))
        val text = context.getString(R.string.alarm_text, time, event.changeRatio * 100)

        val b = NotificationCompat.Builder(context, if (quiet) CH_ALARM_QUIET else CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setColor(0xFFFFB300.toInt())
            .setContentTitle(context.getString(if (quiet) R.string.alarm_title_quiet else R.string.alarm_title))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openEvent)
            .setWhen(event.timestamp)
            .setShowWhen(true)

        if (picture != null) {
            b.setLargeIcon(picture)
            b.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(picture)
                    .bigLargeIcon(null as Bitmap?)
                    .setSummaryText(text)
            )
        }
        if (!quiet) {
            b.addAction(0, context.getString(R.string.silence), silence)
            // Показ поверх экрана блокировки и включение экрана.
            b.setFullScreenIntent(fullScreen, true)
        }
        NotificationManagerCompat.from(context).notify(ID_ALARM_BASE + (number % 500), b.build())
    }
}
