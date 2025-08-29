package com.graincamera

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.graincamera.gl.FilmSim

class FilmSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_film_selection)

        val list: RecyclerView = findViewById(R.id.filmList)
        list.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        list.adapter = FilmAdapter(FilmSim.values().toList()) { selected ->
            FilmSettingsStore.setSelectedFilm(this, selected.name)
            // Load per-film settings into the sliders
            val settings = FilmSettingsStore.getSettingsForFilm(this, selected.name)
            findViewById<SeekBar>(R.id.seekHalation).progress = (settings.halation * 100).toInt()
            findViewById<SeekBar>(R.id.seekBloom).progress = (settings.bloom * 100).toInt()
            findViewById<SeekBar>(R.id.seekGrain).progress = (settings.grain * 100).toInt()
        }

        fun onSeekChanged() {
            val film = FilmSettingsStore.getSelectedFilm(this)
            val halation = findViewById<SeekBar>(R.id.seekHalation).progress / 100f
            val bloom = findViewById<SeekBar>(R.id.seekBloom).progress / 100f
            val grain = findViewById<SeekBar>(R.id.seekGrain).progress / 100f
            FilmSettingsStore.saveSettingsForFilm(this, film, halation, bloom, grain)
        }

        findViewById<SeekBar>(R.id.seekHalation).setOnSeekBarChangeListener(SimpleSeekPublic { onSeekChanged() })
        findViewById<SeekBar>(R.id.seekBloom).setOnSeekBarChangeListener(SimpleSeekPublic { onSeekChanged() })
        findViewById<SeekBar>(R.id.seekGrain).setOnSeekBarChangeListener(SimpleSeekPublic { onSeekChanged() })

        // Initialize with current film
        val currentFilm = FilmSettingsStore.getSelectedFilm(this)
        val idx = FilmSim.values().indexOfFirst { it.name == currentFilm }
        if (idx >= 0) list.scrollToPosition(idx)
        val s = FilmSettingsStore.getSettingsForFilm(this, currentFilm)
        findViewById<SeekBar>(R.id.seekHalation).progress = (s.halation * 100).toInt()
        findViewById<SeekBar>(R.id.seekBloom).progress = (s.bloom * 100).toInt()
        findViewById<SeekBar>(R.id.seekGrain).progress = (s.grain * 100).toInt()

        findViewById<View>(R.id.doneBtn).setOnClickListener { finish() }
    }
}

private class FilmAdapter(
    private val films: List<FilmSim>,
    private val onSelect: (FilmSim) -> Unit
) : RecyclerView.Adapter<FilmVH>() {
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): FilmVH {
        val tv = TextView(parent.context)
        tv.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        tv.setPadding(32, 24, 32, 24)
        tv.setTextColor(0xFFFFFFFF.toInt())
        return FilmVH(tv)
    }
    override fun getItemCount(): Int = films.size
    override fun onBindViewHolder(holder: FilmVH, position: Int) {
        val item = films[position]
        holder.text.text = item.displayName
        holder.text.setOnClickListener { onSelect(item) }
    }
}

private class FilmVH(val text: TextView) : RecyclerView.ViewHolder(text)

object FilmSettingsStore {
    private const val KEY_SELECTED = "selected_film"
    private fun key(film: String) = "film_settings_" + film
    data class Settings(val halation: Float, val bloom: Float, val grain: Float)
    fun getSelectedFilm(ctx: android.content.Context): String {
        val d = android.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        return d.getString(KEY_SELECTED, com.graincamera.gl.FilmSim.PROVIA.name) ?: com.graincamera.gl.FilmSim.PROVIA.name
    }
    fun setSelectedFilm(ctx: android.content.Context, film: String) {
        val d = android.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        d.edit().putString(KEY_SELECTED, film).apply()
    }
    fun getSettingsForFilm(ctx: android.content.Context, film: String): Settings {
        val d = android.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val k = key(film)
        val h = d.getFloat(k + "_h", 0.2f)
        val b = d.getFloat(k + "_b", 0.3f)
        val g = d.getFloat(k + "_g", 0.15f)
        return Settings(h, b, g)
    }
    fun saveSettingsForFilm(ctx: android.content.Context, film: String, h: Float, b: Float, g: Float) {
        val d = android.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val k = key(film)
        d.edit().putFloat(k + "_h", h).putFloat(k + "_b", b).putFloat(k + "_g", g).apply()
    }
}

