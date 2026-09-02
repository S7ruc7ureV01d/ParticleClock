package com.exoticbutters.amoledclock

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * A small settings sheet toggled by a long-press on the clock. Every
 * change is written straight to [Prefs] and applied live via [onChanged].
 */
object SettingsDialog {

    fun show(context: Context, prefs: Prefs, onChanged: () -> Unit) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun addSlider(
            label: String,
            min: Int,
            max: Int,
            current: Int,
            unit: String = "",
            onValue: (Int) -> Unit
        ) {
            val title = TextView(context).apply {
                text = "$label: $current$unit"
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, pad, 0, 0)
            }
            root.addView(title)

            val range = max - min
            val seekBar = SeekBar(context).apply {
                this.max = range
                progress = current - min
            }
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = min + progress
                    title.text = "$label: $value$unit"
                    if (fromUser) onValue(value)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            root.addView(seekBar)
        }

        addSlider(
            context.getString(R.string.settings_clock_size),
            Prefs.CLOCK_SIZE_MIN, Prefs.CLOCK_SIZE_MAX, prefs.clockSizeSp, "sp"
        ) { value ->
            prefs.clockSizeSp = value
            onChanged()
        }

        addSlider(
            context.getString(R.string.settings_particle_count),
            Prefs.PARTICLE_COUNT_MIN, Prefs.PARTICLE_COUNT_MAX, prefs.particleCount
        ) { value ->
            prefs.particleCount = value
            onChanged()
        }

        addSlider(
            context.getString(R.string.settings_red_chance),
            0, 100, prefs.redChancePercent, "%"
        ) { value ->
            prefs.redChancePercent = value
            onChanged()
        }

        addSlider(
            context.getString(R.string.settings_red_brightness),
            0, 255, prefs.redBrightness
        ) { value ->
            prefs.redBrightness = value
            onChanged()
        }

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_Black)
            .setTitle(context.getString(R.string.settings_title))
            .setView(root)
            .setPositiveButton(context.getString(R.string.settings_close), null)
            .create()

        dialog.window?.let { window ->
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.WHITE)
    }
}
