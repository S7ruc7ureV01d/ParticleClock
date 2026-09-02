package com.exoticbutters.amoledclock

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * A small settings sheet toggled by a long-press on the clock. Every
 * change is written straight to [Prefs] and applied live via [onChanged].
 */
object SettingsDialog {

    fun show(context: Context, prefs: Prefs, onChanged: () -> Unit) {
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun addSlider(
            container: LinearLayout,
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
            container.addView(title)

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
            container.addView(seekBar)
        }

        // ---- Auto-move toggle + burn-in warning ----
        // A plain tappable label rather than a Switch: the stock Switch's
        // track/thumb tint is invisible against this dialog's black theme
        // on several OS versions, so we draw our own state explicitly.
        val autoMoveRow = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 0, pad / 2)
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        fun autoMoveLabelFor(on: Boolean) =
            "${context.getString(R.string.settings_auto_move)}: ${if (on) "ON" else "OFF"}  (tap to toggle)"
        autoMoveRow.text = autoMoveLabelFor(prefs.autoMove)
        root.addView(autoMoveRow)

        val warningText = TextView(context).apply {
            text = context.getString(R.string.settings_auto_move_warning)
            setTextColor(Color.rgb(255, 180, 60))
            textSize = 13f
            setPadding(0, 0, 0, pad / 2)
            visibility = if (prefs.autoMove) View.GONE else View.VISIBLE
        }
        root.addView(warningText)

        // ---- Move interval (only meaningful while auto-move is on) ----
        val moveIntervalContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (prefs.autoMove) View.VISIBLE else View.GONE
        }
        addSlider(
            moveIntervalContainer,
            context.getString(R.string.settings_move_interval),
            Prefs.MOVE_INTERVAL_MIN_SECONDS, Prefs.MOVE_INTERVAL_MAX_SECONDS, prefs.moveIntervalSeconds, "s"
        ) { value ->
            prefs.moveIntervalSeconds = value
            onChanged()
        }
        root.addView(moveIntervalContainer)

        autoMoveRow.setOnClickListener {
            val checked = !prefs.autoMove
            prefs.autoMove = checked
            autoMoveRow.text = autoMoveLabelFor(checked)
            warningText.visibility = if (checked) View.GONE else View.VISIBLE
            moveIntervalContainer.visibility = if (checked) View.VISIBLE else View.GONE
            onChanged()
        }

        addSlider(
            root,
            context.getString(R.string.settings_clock_size),
            Prefs.CLOCK_SIZE_MIN, Prefs.CLOCK_SIZE_MAX, prefs.clockSizeSp, "sp"
        ) { value ->
            prefs.clockSizeSp = value
            onChanged()
        }

        addSlider(
            root,
            context.getString(R.string.settings_particle_count),
            Prefs.PARTICLE_COUNT_MIN, Prefs.PARTICLE_COUNT_MAX, prefs.particleCount
        ) { value ->
            prefs.particleCount = value
            onChanged()
        }

        addSlider(
            root,
            context.getString(R.string.settings_particle_size),
            Prefs.PARTICLE_SIZE_TENTHS_MIN, Prefs.PARTICLE_SIZE_TENTHS_MAX, prefs.particleSizeTenthsDp
        ) { value ->
            prefs.particleSizeTenthsDp = value
            onChanged()
        }

        addSlider(
            root,
            context.getString(R.string.settings_red_chance),
            0, 100, prefs.redChancePercent, "%"
        ) { value ->
            prefs.redChancePercent = value
            onChanged()
        }

        addSlider(
            root,
            context.getString(R.string.settings_red_brightness),
            0, 255, prefs.redBrightness
        ) { value ->
            prefs.redBrightness = value
            onChanged()
        }

        val scroll = ScrollView(context).apply { addView(root) }

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_Black)
            .setTitle(context.getString(R.string.settings_title))
            .setView(scroll)
            .setPositiveButton(context.getString(R.string.settings_close), null)
            .create()

        dialog.window?.let { window ->
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.WHITE)
    }
}
