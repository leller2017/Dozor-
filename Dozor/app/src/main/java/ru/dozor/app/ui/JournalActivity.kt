package ru.dozor.app.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.dozor.app.DozorApp
import ru.dozor.app.R
import ru.dozor.app.data.AlarmEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Журнал срабатываний: время + миниатюра; тап — полные снимки, долгий тап — удалить. */
class JournalActivity : AppCompatActivity() {

    private val app get() = application as DozorApp
    private val adapter = EventAdapter(
        onClick = { startActivity(EventActivity.intent(this, it.id)) },
        onLongClick = { confirmDelete(it) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_journal)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val list = findViewById<RecyclerView>(R.id.list)
        val empty = findViewById<View>(R.id.empty)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.events.version.collect {
                    val items = withContext(Dispatchers.IO) {
                        app.events.list().map { ev ->
                            EventRow(ev, ev.shots.size, Images.decode(ev.thumb, 240))
                        }
                    }
                    adapter.submit(items)
                    empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.journal, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_clear -> {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.clear_journal_q)
                .setPositiveButton(R.string.delete) { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) { app.events.clear() }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun confirmDelete(ev: AlarmEvent) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.delete_event_q)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { app.events.delete(ev.id) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

data class EventRow(val event: AlarmEvent, val shotCount: Int, val thumb: Bitmap?)

class EventAdapter(
    private val onClick: (AlarmEvent) -> Unit,
    private val onLongClick: (AlarmEvent) -> Unit,
) : RecyclerView.Adapter<EventAdapter.VH>() {

    private var items: List<EventRow> = emptyList()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale("ru"))
    private val dateFmt = SimpleDateFormat("d MMMM yyyy, EEEE", Locale("ru"))

    fun submit(newItems: List<EventRow>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val time: TextView = view.findViewById(R.id.time)
        val date: TextView = view.findViewById(R.id.date)
        val sub: TextView = view.findViewById(R.id.sub)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_event, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        val ev = row.event
        holder.thumb.setImageBitmap(row.thumb)
        holder.time.text = timeFmt.format(Date(ev.timestamp))
        holder.date.text = dateFmt.format(Date(ev.timestamp))
        val ctx = holder.itemView.context
        val sub = ctx.getString(R.string.journal_item_sub, row.shotCount, ev.changeRatio * 100)
        holder.sub.text = if (ev.onShift.isBlank()) sub else sub + "\n" + ctx.getString(R.string.journal_on_shift, ev.onShift)
        holder.itemView.setOnClickListener { onClick(ev) }
        holder.itemView.setOnLongClickListener { onLongClick(ev); true }
    }
}
