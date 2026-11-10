package ru.dozor.app

import android.app.Application
import ru.dozor.app.alert.AlertManager
import ru.dozor.app.alert.Notifications
import ru.dozor.app.data.EventRepository
import ru.dozor.app.data.SettingsRepository
import ru.dozor.app.publish.CloudUploader
import ru.dozor.app.publish.EventPublisher
import ru.dozor.app.publish.LocalAlertPublisher

/**
 * Точка сборки зависимостей. Простая «ручная» DI — без фреймворков,
 * но все компоненты подменяемы через интерфейсы.
 */
class DozorApp : Application() {

    lateinit var settings: SettingsRepository
        private set
    lateinit var events: EventRepository
        private set
    lateinit var alerts: AlertManager
        private set

    /**
     * Получатели событий тревоги. Сейчас — локальное уведомление со звуком.
     * Сюда же в будущем добавляются выгрузка в облако и push на другое устройство.
     */
    lateinit var publishers: List<EventPublisher>
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.createChannels(this)
        settings = SettingsRepository(this)
        events = EventRepository(this)
        alerts = AlertManager(this, settings)
        publishers = listOf(
            LocalAlertPublisher(alerts),
            CloudUploader.Disabled, // заглушка: облако появится в следующих версиях
        )
    }

    companion object {
        lateinit var instance: DozorApp
            private set
    }
}
