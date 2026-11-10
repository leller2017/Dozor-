package ru.dozor.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.dozor.app.DozorApp
import ru.dozor.app.R
import ru.dozor.app.data.AlarmEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Полные снимки одного срабатывания, листаются свайпом. */
class EventActivity : AppCompatActivity() {

    private val app get() = application as DozorApp
    private var event: AlarmEvent? = null
    private var shots: List<File> = emptyList()
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val id = intent.getStringExtra(EXTRA_ID)
        val ev = id?.let { app.events.get(it) }
        if (ev == null) {
            finish()
            return
        }
        event = ev
        title = SimpleDateFormat("dd.MM.yyyy  HH:mm:ss", Locale("ru")).format(Date(ev.timestamp))

        val pager = findViewById<RecyclerView>(R.id.pager)
        val counter = findViewById<TextView>(R.id.counter)
        val lm = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        pager.layoutManager = lm
        val snap = PagerSnapHelper()
        snap.attachToRecyclerView(pager)

        shots = ev.shots
        val width = resources.displayMetrics.widthPixels
        pager.adapter = ShotAdapter(shots, width)
        updateCounter(counter)

        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val v = snap.findSnapView(lm) ?: return
                    current = lm.getPosition(v)
                    updateCounter(counter)
                }
            }
        })
    }

    private fun updateCounter(counter: TextView) {
        counter.text = if (shots.isEmpty()) "" else getString(R.string.shot_counter, current + 1, shots.size)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.event, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_share -> { share(); true }
        R.id.action_delete -> {
            val ev = event
            if (ev != null) {
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.delete_event_q)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { app.events.delete(ev.id) }
                            finish()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun share() {
        val file = shots.getOrNull(current) ?: return
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.files", file)
        }.getOrNull() ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private class ShotAdapter(private val files: List<File>, private val reqWidth: Int) :
        RecyclerView.Adapter<ShotAdapter.VH>() {

        class VH(val image: ImageView) : RecyclerView.ViewHolder(image)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_shot, parent, false) as ImageView
            v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            return VH(v)
        }

        override fun getItemCount() = files.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.image.setImageBitmap(Images.decode(files[position], reqWidth))
        }
    }

    companion object {
        private const val EXTRA_ID = "event_id"
        fun intent(context: Context, id: String): Intent =
            Intent(context, EventActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
