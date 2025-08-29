package com.graincamera

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.viewpager2.widget.ViewPager2
import androidx.recyclerview.widget.RecyclerView
import com.graincamera.gl.FilmSim
import java.util.Locale

class FilmSelectionActivity : ComponentActivity() {
    private lateinit var viewPager: ViewPager2
    private lateinit var currentFilmName: TextView
    private var currentPosition = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_film_selection)

        viewPager = findViewById(R.id.filmPager)
        currentFilmName = findViewById(R.id.currentFilmName)
        
        // Set up ViewPager2
        viewPager.adapter = FilmPagerAdapter(FilmSim.values().toList())
        viewPager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        
        // Set initial position to current film
        val currentFilm = FilmSettingsStore.getSelectedFilm(this)
        val initialPosition = FilmSim.values().indexOfFirst { it.name == currentFilm }
        if (initialPosition >= 0) {
            currentPosition = initialPosition
            viewPager.setCurrentItem(initialPosition, false)
            updateFilmName(initialPosition)
        }
        
        // Handle page changes
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPosition = position
                val selectedFilm = FilmSim.values()[position]
                FilmSettingsStore.setSelectedFilm(this@FilmSelectionActivity, selectedFilm.name)
                updateFilmName(position)
                updateSettingsForFilm(selectedFilm.name)
            }
        })

        // Prev/Next buttons
        findViewById<ImageButton>(R.id.btnPrevFilm)?.setOnClickListener {
            val prev = (currentPosition - 1 + FilmSim.values().size) % FilmSim.values().size
            viewPager.setCurrentItem(prev, true)
        }
        findViewById<ImageButton>(R.id.btnNextFilm)?.setOnClickListener {
            val next = (currentPosition + 1) % FilmSim.values().size
            viewPager.setCurrentItem(next, true)
        }

        fun onSeekChanged() {
            val film = FilmSim.values()[currentPosition]
            val halation = findViewById<SeekBar>(R.id.seekHalation).progress / 100f
            val bloom = findViewById<SeekBar>(R.id.seekBloom).progress / 100f
            val grain = findViewById<SeekBar>(R.id.seekGrain).progress / 100f
            val grainSize = 1f + (findViewById<SeekBar>(R.id.seekGrainSize).progress / 100f) * 1f
            val grainRoughness = findViewById<SeekBar>(R.id.seekGrainRoughness).progress / 100f
            val exposure = (findViewById<SeekBar>(R.id.seekExposure).progress / 100f) * 4f - 2f // -2..+2 stops
            val contrast = 0.5f + (findViewById<SeekBar>(R.id.seekContrast).progress / 100f) // 0.5..1.5
            val saturationAdj = 1.2f * (findViewById<SeekBar>(R.id.seekSaturation).progress / 100f) // 0..1.2
            val temperature = (findViewById<SeekBar>(R.id.seekTemperature).progress / 100f) * 2f - 1f // -1..+1

            findViewById<TextView>(R.id.valueHalation).text = String.format(Locale.US, "%.0f", halation * 100f)
            findViewById<TextView>(R.id.valueBloom).text = String.format(Locale.US, "%.0f", bloom * 100f)
            findViewById<TextView>(R.id.valueGrain).text = String.format(Locale.US, "%.0f", grain * 100f)
            findViewById<TextView>(R.id.valueGrainSize).text = String.format(Locale.US, "%.1f px", grainSize)
            findViewById<TextView>(R.id.valueGrainRoughness).text = String.format(Locale.US, "%.0f", grainRoughness * 100f)
            findViewById<TextView>(R.id.valueExposure).text = String.format(Locale.US, "%.1f stops", exposure)
            findViewById<TextView>(R.id.valueContrast).text = String.format(Locale.US, "%.0f%%", contrast * 100f)
            findViewById<TextView>(R.id.valueSaturation).text = String.format(Locale.US, "%.0f%%", saturationAdj * 100f)
            findViewById<TextView>(R.id.valueTemperature).text = String.format(Locale.US, "%.0f", temperature * 100f)

            FilmSettingsStore.saveSettingsForFilm(this, film.name, halation, bloom, grain, grainSize, grainRoughness, exposure, contrast, saturationAdj, temperature)
        }

        findViewById<SeekBar>(R.id.seekHalation).setOnSeekBarChangeListener(SimpleSeekPublic { onSeekChanged() })
        findViewById<SeekBar>(R.id.seekBloom).setOnSeekBarChangeListener(SimpleSeekPublic { onSeekChanged() })
        findViewById<SeekBar>(R.id.seekGrain).setOnSeekBarChangeListener(SimpleSeekPublic { onSeekChanged() })
        findViewById<SeekBar>(R.id.seekGrainSize).setOnSeekBarChangeListener(SimpleSeekPublic { onSeekChanged() })
        findViewById<SeekBar>(R.id.seekGrainRoughness).setOnSeekBarChangeListener(SimpleSeekPublic { onSeekChanged() })
        findViewById<SeekBar>(R.id.seekExposure).setOnSeekBarChangeListener(SimpleSeekPublic { onSeekChanged() })
        findViewById<SeekBar>(R.id.seekContrast).setOnSeekBarChangeListener(SimpleSeekPublic { onSeekChanged() })
        findViewById<SeekBar>(R.id.seekSaturation).setOnSeekBarChangeListener(SimpleSeekPublic { onSeekChanged() })
        findViewById<SeekBar>(R.id.seekTemperature).setOnSeekBarChangeListener(SimpleSeekPublic { onSeekChanged() })

        // Initialize with current film settings
        updateSettingsForFilm(currentFilm)

        findViewById<View>(R.id.doneBtn).setOnClickListener { finish() }
    }
    
    private fun updateFilmName(position: Int) {
        val film = FilmSim.values()[position]
        currentFilmName.text = film.displayName
    }
    
    private fun updateSettingsForFilm(filmName: String) {
        val settings = FilmSettingsStore.getSettingsForFilm(this, filmName)
        findViewById<SeekBar>(R.id.seekHalation).progress = (settings.halation * 100).toInt()
        findViewById<SeekBar>(R.id.seekBloom).progress = (settings.bloom * 100).toInt()
        findViewById<SeekBar>(R.id.seekGrain).progress = (settings.grain * 100).toInt()
        val clampedGs = settings.grainSize.coerceIn(1f, 2f)
        findViewById<SeekBar>(R.id.seekGrainSize).progress = (((clampedGs - 1f) / 1f) * 100f).toInt().coerceIn(0, 100)
        findViewById<SeekBar>(R.id.seekGrainRoughness).progress = (settings.grainRoughness * 100).toInt()
        findViewById<SeekBar>(R.id.seekExposure).progress = (((settings.exposure + 2f) / 4f) * 100f).toInt().coerceIn(0, 100)
        findViewById<SeekBar>(R.id.seekContrast).progress = (((settings.contrast - 0.5f) / 1f) * 100f).toInt().coerceIn(0, 100)
        findViewById<SeekBar>(R.id.seekSaturation).progress = ((settings.saturationAdj / 1.2f) * 100).toInt().coerceIn(0, 100)
        findViewById<SeekBar>(R.id.seekTemperature).progress = (((settings.temperature + 1f) / 2f) * 100f).toInt().coerceIn(0, 100)

        findViewById<TextView>(R.id.valueHalation).text = String.format(Locale.US, "%.0f", settings.halation * 100f)
        findViewById<TextView>(R.id.valueBloom).text = String.format(Locale.US, "%.0f", settings.bloom * 100f)
        findViewById<TextView>(R.id.valueGrain).text = String.format(Locale.US, "%.0f", settings.grain * 100f)
        findViewById<TextView>(R.id.valueGrainSize).text = String.format(Locale.US, "%.1f px", clampedGs)
        findViewById<TextView>(R.id.valueGrainRoughness).text = String.format(Locale.US, "%.0f", settings.grainRoughness * 100f)
        findViewById<TextView>(R.id.valueExposure).text = String.format(Locale.US, "%.1f stops", settings.exposure)
        findViewById<TextView>(R.id.valueContrast).text = String.format(Locale.US, "%.0f%%", settings.contrast * 100f)
        findViewById<TextView>(R.id.valueSaturation).text = String.format(Locale.US, "%.0f%%", settings.saturationAdj * 100f)
        findViewById<TextView>(R.id.valueTemperature).text = String.format(Locale.US, "%.0f", settings.temperature * 100f)
    }
}

private class FilmPagerAdapter(
    private val films: List<FilmSim>
) : RecyclerView.Adapter<FilmPagerAdapter.FilmViewHolder>() {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilmViewHolder {
        val container = LinearLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(16, 16, 16, 16)
        }
        
        val filmBox = LinearLayout(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            background = parent.context.getDrawable(R.drawable.film_strip_box)
            gravity = android.view.Gravity.CENTER
        }
        
        val filmName = TextView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            gravity = android.view.Gravity.CENTER
        }
        
        filmBox.addView(filmName)
        container.addView(filmBox)
        
        return FilmViewHolder(container, filmName, filmBox)
    }
    
    override fun getItemCount(): Int = films.size
    
    override fun onBindViewHolder(holder: FilmViewHolder, position: Int) {
        val film = films[position]
        holder.filmName.text = film.displayName
        
        // Check if this is the currently selected film
        val isSelected = FilmSettingsStore.getSelectedFilm(holder.itemView.context) == film.name
        holder.filmBox.background = holder.itemView.context.getDrawable(
            if (isSelected) R.drawable.film_strip_selected else R.drawable.film_strip_box
        )
    }
    
    class FilmViewHolder(
        itemView: View,
        val filmName: TextView,
        val filmBox: LinearLayout
    ) : RecyclerView.ViewHolder(itemView)
}

object FilmSettingsStore {
    private const val KEY_SELECTED = "selected_film"
    private const val KEY_RULE_OF_THIRDS = "rule_of_thirds"
    private fun key(film: String) = "film_settings_" + film
    data class Settings(val halation: Float, val bloom: Float, val grain: Float, val grainSize: Float, val grainRoughness: Float, val exposure: Float, val contrast: Float, val saturationAdj: Float, val temperature: Float)
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
        val h = d.getFloat(k + "_h", 0.2f).coerceAtMost(0.5f)
        val b = d.getFloat(k + "_b", 0.3f).coerceAtMost(0.5f)
        val g = d.getFloat(k + "_g", 0.15f)
        val gs = d.getFloat(k + "_gs", 1.5f).coerceIn(1f, 2f)
        val gr = d.getFloat(k + "_gr", 0.5f)
        val ex = d.getFloat(k + "_ex", 0f).coerceIn(-2f, 2f)
        val ct = d.getFloat(k + "_ct", 1.0f).coerceIn(0.5f, 1.5f)
        val sa = d.getFloat(k + "_sa", 1.0f).coerceIn(0f, 2f)
        val tp = d.getFloat(k + "_tp", 0.0f).coerceIn(-1f, 1f)
        return Settings(h, b, g, gs, gr, ex, ct, sa, tp)
    }
    fun saveSettingsForFilm(ctx: android.content.Context, film: String, h: Float, b: Float, g: Float, gs: Float, gr: Float, ex: Float, ct: Float, sa: Float, tp: Float) {
        val d = android.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val k = key(film)
        d.edit()
            .putFloat(k + "_h", h.coerceAtMost(0.5f))
            .putFloat(k + "_b", b.coerceAtMost(0.5f))
            .putFloat(k + "_g", g)
            .putFloat(k + "_gs", gs.coerceIn(1f, 2f))
            .putFloat(k + "_gr", gr)
            .putFloat(k + "_ex", ex.coerceIn(-2f, 2f))
            .putFloat(k + "_ct", ct.coerceIn(0.5f, 1.5f))
            .putFloat(k + "_sa", sa.coerceIn(0f, 2f))
            .putFloat(k + "_tp", tp.coerceIn(-1f, 1f))
            .apply()
    }

    fun getRuleOfThirds(ctx: android.content.Context): Boolean {
        val d = android.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        return d.getBoolean(KEY_RULE_OF_THIRDS, false)
    }

    fun setRuleOfThirds(ctx: android.content.Context, value: Boolean) {
        val d = android.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        d.edit().putBoolean(KEY_RULE_OF_THIRDS, value).apply()
    }
}

