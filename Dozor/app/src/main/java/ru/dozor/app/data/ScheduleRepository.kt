package ru.dozor.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Сотрудник на суточном графике: смена длится сутки, затем [daysOff] выходных.
 * 1 — «сутки через сутки», 2 — «сутки через двое», 3 — «сутки через трое».
 */
data class Employee(
    val id: Long,
    val lastName: String,
    val firstName: String,
    val middleName: String,
    /** День первой (опорной) смены, LocalDate.toEpochDay(). */
    val firstShiftDay: Long,
    val daysOff: Int,
    val colorIndex: Int,
) {
    val fullName: String
        get() = listOf(lastName, firstName, middleName).filter { it.isNotBlank() }.joinToString(" ")

    /** «Иванов И.И.» */
    val shortName: String
        get() = buildString {
            append(lastName.ifBlank { firstName })
            if (lastName.isNotBlank() && firstName.isNotBlank()) append(' ').append(firstName.first()).append('.')
            if (lastName.isNotBlank() && middleName.isNotBlank()) append(middleName.first()).append('.')
        }

    val period: Int get() = daysOff + 1

    /** Начинается ли смена этого сотрудника в указанный день. */
    fun worksOn(day: LocalDate): Boolean =
        Math.floorMod(day.toEpochDay() - firstShiftDay, period.toLong()) == 0L
}

/** Хранилище графика (JSON в SharedPreferences) и расчёт, кто дежурит. */
class ScheduleRepository(context: Context) {

    private val prefs = context.getSharedPreferences("dozor_schedule", Context.MODE_PRIVATE)

    private val _version = MutableStateFlow(0)
    /** Меняется при любом изменении графика. */
    val version: StateFlow<Int> = _version

    /** Время пересменки, минуты от полуночи (по умолчанию 08:00). */
    var shiftStartMin: Int
        get() = prefs.getInt(K_SHIFT_START, 8 * 60)
        set(value) {
            prefs.edit().putInt(K_SHIFT_START, value).apply()
            _version.update { it + 1 }
        }

    fun list(): List<Employee> {
        val raw = prefs.getString(K_EMPLOYEES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Employee(
                    id = o.getLong("id"),
                    lastName = o.optString("last"),
                    firstName = o.optString("first"),
                    middleName = o.optString("middle"),
                    firstShiftDay = o.getLong("firstShift"),
                    daysOff = o.optInt("daysOff", 3),
                    colorIndex = o.optInt("color", i),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(e: Employee) {
        val all = list().toMutableList()
        val idx = all.indexOfFirst { it.id == e.id }
        if (idx >= 0) all[idx] = e else all.add(e)
        write(all)
    }

    fun delete(id: Long) = write(list().filterNot { it.id == id })

    fun nextColorIndex(): Int {
        val used = list().map { it.colorIndex }.toSet()
        return (0 until 100).first { it !in used }
    }

    /** Кто заступает на смену в этот день. */
    fun onDuty(day: LocalDate): List<Employee> = list().filter { it.worksOn(day) }

    /**
     * Кто на смене прямо сейчас. Смена суточная: до времени пересменки
     * дежурит тот, кто заступил вчера.
     */
    fun onShiftAt(timeMs: Long): List<Employee> {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(timeMs), ZoneId.systemDefault())
        val minutes = now.hour * 60 + now.minute
        val shiftDay = if (minutes < shiftStartMin) now.toLocalDate().minusDays(1) else now.toLocalDate()
        return onDuty(shiftDay)
    }

    private fun write(list: List<Employee>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("last", e.lastName)
                    .put("first", e.firstName)
                    .put("middle", e.middleName)
                    .put("firstShift", e.firstShiftDay)
                    .put("daysOff", e.daysOff)
                    .put("color", e.colorIndex)
            )
        }
        prefs.edit().putString(K_EMPLOYEES, arr.toString()).apply()
        _version.update { it + 1 }
    }

    private companion object {
        const val K_EMPLOYEES = "employees"
        const val K_SHIFT_START = "shift_start"
    }
}
