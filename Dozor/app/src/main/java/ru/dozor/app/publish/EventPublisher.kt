package ru.dozor.app.publish

import android.graphics.Bitmap
import ru.dozor.app.alert.AlertManager
import ru.dozor.app.data.AlarmEvent

/**
 * Получатель событий тревоги. Точка расширения:
 *  - [LocalAlertPublisher] — звук и уведомление на этом телефоне (есть сейчас);
 *  - [CloudUploader] — выгрузка снимков в облако (следующая версия);
 *  - PushNotifier — push на телефон хозяина (следующая версия).
 */
interface EventPublisher {
    /** Срабатывание зафиксировано, первый снимок уже сохранён. Вызывается из фонового потока. */
    fun onAlarm(event: AlarmEvent, firstShot: Bitmap?, number: Int)

    /** Вся серия снимков сохранена — можно выгружать. Вызывается из фонового потока. */
    fun onEventComplete(event: AlarmEvent) {}
}

class LocalAlertPublisher(private val alerts: AlertManager) : EventPublisher {
    override fun onAlarm(event: AlarmEvent, firstShot: Bitmap?, number: Int) {
        alerts.fire(event, firstShot, number)
    }
}

/**
 * Заготовка под облако. Реализация (Firebase Storage, Яндекс Облако, S3, свой сервер)
 * должна в [onEventComplete] поставить задачу WorkManager на выгрузку event.shots,
 * чтобы загрузка переживала обрывы связи.
 */
interface CloudUploader : EventPublisher {
    object Disabled : CloudUploader {
        override fun onAlarm(event: AlarmEvent, firstShot: Bitmap?, number: Int) = Unit
    }
}
