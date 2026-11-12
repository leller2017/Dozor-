package ru.dozor.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import ru.dozor.app.DozorApp
import ru.dozor.app.R
import ru.dozor.app.data.Employee
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** График суточных смен: календарь месяца + список сотрудников. */
class ScheduleActivity : AppCompatActivity() {

    private val repo get() = (application as DozorApp).schedule
    private val ru = Locale("ru")
    private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy", ru)
    private val dayFmt = DateTimeFormatter.ofPattern("d MMMM, EEEE", ru)

    private var month: YearMonth = YearMonth.now()

    private lateinit var monthTitle: TextView
    private lateinit var calendarGrid: LinearLayout
    private lateinit var staffList: LinearLayout
    private lateinit var staffEmpty: View
    private lateinit var nowText: TextView
    private lateinit var shiftStartButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.schedule_title)

        monthTitle = findViewById(R.id.monthTitle)
        calendarGrid = findViewById(R.id.calendarGrid)
        staffList = findViewById(R.id.staffList)
        staffEmpty = findViewById(R.id.staffEmpty)
        nowText = findViewById(R.id.nowText)
        shiftStartButton = findViewById(R.id.shiftStartButton)

        findViewById<View>(R.id.prevMonth).setOnClickListener { month = month.minusMonths(1); render() }
        findViewById<View>(R.id.nextMonth).setOnClickListener { month = month.plusMonths(1); render() }
        monthTitle.setOnClickListener { month = YearMonth.now(); render() }
        findViewById<View>(R.id.addEmployee).setOnClickListener { editEmployee(null) }
        shiftStartButton.setOnClickListener { pickShiftStart() }

        buildWeekHeader()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.version.collect { render() }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------ отрисовка

    private fun render() {
        val staff = repo.list()
        val now = repo.onShiftAt(System.currentTimeMillis())
        nowText.text = if (now.isEmpty()) {
            getString(R.string.on_shift_none)
        } else {
            getString(R.string.on_shift_now, now.joinToString(", ") { it.fullName })
        }
        shiftStartButton.text = getString(R.string.shift_change, hhmm(repo.shiftStartMin))

        // «LLLL» — название месяца в именительном падеже («ноябрь»).
        val name = java.text.SimpleDateFormat("LLLL", ru)
            .format(java.util.Date.from(month.atDay(15).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()))
            .replaceFirstChar { it.uppercase() }
        monthTitle.text = "$name ${month.year}"

        renderCalendar(staff)
        renderStaff(staff)
    }

    private fun buildWeekHeader() {
        val header = findViewById<LinearLayout>(R.id.weekHeader)
        header.removeAllViews()
        DayOfWeek.values().forEach { d ->
            header.addView(TextView(this).apply {
                text = WEEKDAYS[d.value - 1]
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) R.color.red else R.color.text_dim
                    )
                )
                setPadding(0, dp(4), 0, dp(4))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun renderCalendar(staff: List<Employee>) {
        calendarGrid.removeAllViews()
        val today = LocalDate.now()
        val first = month.atDay(1)
        // Неделя начинается с понедельника.
        var day = first.minusDays((first.dayOfWeek.value - 1).toLong())
        repeat(6) { row ->
            if (row > 0 && day.month != month.month && day.isAfter(first)) return@repeat
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(7) {
                line.addView(dayCell(day, staff, today), LinearLayout.LayoutParams(0, dp(70), 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
                day = day.plusDays(1)
            }
            calendarGrid.addView(line)
        }
    }

    private fun dayCell(day: LocalDate, staff: List<Employee>, today: LocalDate): View {
        val inMonth = day.month == month.month
        val onDuty = staff.filter { it.worksOn(day) }
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(2), dp(3), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(ContextCompat.getColor(context, if (inMonth) R.color.surface else R.color.bg))
                if (day == today) setStroke(dp(2), ContextCompat.getColor(context, R.color.amber))
            }
            alpha = if (inMonth) 1f else 0.45f
            setOnClickListener { showDay(day, onDuty) }
        }
        cell.addView(TextView(this).apply {
            text = day.dayOfMonth.toString()
            textSize = 13f
            setTypeface(typeface, if (day == today) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (day == today) R.color.amber
                    else if (day.dayOfWeek.value >= 6) R.color.red else R.color.text
                )
            )
        })
        onDuty.take(3).forEach { e ->
            cell.addView(TextView(this).apply {
                text = e.lastName.ifBlank { e.firstName }
                textSize = 9.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Color.BLACK)
                setPadding(dp(3), 0, dp(3), 0)
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(staffColor(e))
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            })
        }
        return cell
    }

    private fun renderStaff(staff: List<Employee>) {
        staffList.removeAllViews()
        staffEmpty.visibility = if (staff.isEmpty()) View.VISIBLE else View.GONE
        staff.forEach { e ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isClickable = true
                setOnClickListener { editEmployee(e) }
                setOnLongClickListener { confirmDelete(e); true }
            }
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(staffColor(e))
                }
            }, LinearLayout.LayoutParams(dp(14), dp(14)))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = e.fullName
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.text))
            })
            texts.addView(TextView(this).apply {
                text = getString(
                    R.string.employee_sub,
                    cycleName(e.daysOff),
                    LocalDate.ofEpochDay(e.firstShiftDay).format(dateFmt)
                )
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_dim))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            })
            staffList.addView(row)
        }
    }

    private fun showDay(day: LocalDate, onDuty: List<Employee>) {
        val msg = if (onDuty.isEmpty()) {
            getString(R.string.day_dialog_none)
        } else {
            val shift = getString(
                R.string.day_dialog_shift,
                hhmm(repo.shiftStartMin),
                day.plusDays(1).format(DateTimeFormatter.ofPattern("d MMMM", ru))
            )
            onDuty.joinToString("\n\n") { it.fullName + "\n" + cycleName(it.daysOff) } + "\n\n" + shift
        }
        val title = day.format(dayFmt).replaceFirstChar { it.uppercase() } +
            if (day == LocalDate.now()) " · " + getString(R.string.today) else ""
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------ сотрудники

    private fun editEmployee(existing: Employee?) {
        val view = layoutInflater.inflate(R.layout.dialog_employee, null)
        val last = view.findViewById<TextInputEditText>(R.id.lastName)
        val first = view.findViewById<TextInputEditText>(R.id.firstName)
        val middle = view.findViewById<TextInputEditText>(R.id.middleName)
        val dateButton = view.findViewById<MaterialButton>(R.id.firstShiftButton)
        val cycleGroup = view.findViewById<RadioGroup>(R.id.cycleGroup)

        var shiftDay = existing?.let { LocalDate.ofEpochDay(it.firstShiftDay) } ?: LocalDate.now()
        last.setText(existing?.lastName.orEmpty())
        first.setText(existing?.firstName.orEmpty())
        middle.setText(existing?.middleName.orEmpty())
        dateButton.text = getString(R.string.first_shift, shiftDay.format(dateFmt))
        cycleGroup.check(
            when (existing?.daysOff ?: 3) {
                1 -> R.id.cycle1
                2 -> R.id.cycle2
                4 -> R.id.cycle4
                else -> R.id.cycle3
            }
        )
        dateButton.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                shiftDay = LocalDate.of(y, m + 1, d)
                dateButton.text = getString(R.string.first_shift, shiftDay.format(dateFmt))
            }, shiftDay.year, shiftDay.monthValue - 1, shiftDay.dayOfMonth).show()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_employee)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val l = last.text?.toString()?.trim().orEmpty()
                val f = first.text?.toString()?.trim().orEmpty()
                val m = middle.text?.toString()?.trim().orEmpty()
                if (l.isEmpty() && f.isEmpty()) {
                    Toast.makeText(this, R.string.name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val daysOff = when (cycleGroup.checkedRadioButtonId) {
                    R.id.cycle1 -> 1
                    R.id.cycle2 -> 2
                    R.id.cycle4 -> 4
                    else -> 3
                }
                repo.save(
                    Employee(
                        id = existing?.id ?: System.currentTimeMillis(),
                        lastName = l,
                        firstName = f,
                        middleName = m,
                        firstShiftDay = shiftDay.toEpochDay(),
                        daysOff = daysOff,
                        colorIndex = existing?.colorIndex ?: repo.nextColorIndex(),
                    )
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .apply {
                if (existing != null) setNeutralButton(R.string.delete) { _, _ -> confirmDelete(existing) }
            }
            .show()
    }

    private fun confirmDelete(e: Employee) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_employee_q, e.fullName))
            .setPositiveButton(R.string.delete) { _, _ -> repo.delete(e.id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickShiftStart() {
        val m = repo.shiftStartMin
        TimePickerDialog(this, { _, h, min -> repo.shiftStartMin = h * 60 + min }, m / 60, m % 60, true).show()
    }

    // ------------------------------------------------------------ утилиты

    private fun cycleName(daysOff: Int): String = getString(
        when (daysOff) {
            1 -> R.string.cycle_1
            2 -> R.string.cycle_2
            4 -> R.string.cycle_4
            else -> R.string.cycle_3
        }
    )

    private fun staffColor(e: Employee): Int = STAFF_COLORS[Math.floorMod(e.colorIndex, STAFF_COLORS.size)]

    private fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val WEEKDAYS = arrayOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        val STAFF_COLORS = intArrayOf(
            0xFFFFB300.toInt(), 0xFF26C6DA.toInt(), 0xFFEF5350.toInt(), 0xFF66BB6A.toInt(),
            0xFFAB47BC.toInt(), 0xFFFF7043.toInt(), 0xFF42A5F5.toInt(), 0xFFEC407A.toInt(),
        )
    }
}
