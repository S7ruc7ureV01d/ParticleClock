package com.exoticbutters.amoledclock

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
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

        // A plain tappable label rather than a Switch: the stock Switch's
        // track/thumb tint is invisible against this dialog's black theme
        // on several OS versions, so we draw our own state explicitly.
        fun addToggle(
            container: LinearLayout,
            label: String,
            initial: Boolean,
            onToggle: (Boolean) -> Unit
        ): TextView {
            val row = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, pad / 2, 0, pad / 2)
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            fun labelFor(on: Boolean) = "$label: ${if (on) "ON" else "OFF"}  (tap to toggle)"
            var state = initial
            row.text = labelFor(state)
            row.setOnClickListener {
                state = !state
                row.text = labelFor(state)
                onToggle(state)
            }
            container.addView(row)
            return row
        }

        fun addColorPicker(container: LinearLayout, label: String, initialColor: Int, onColor: (Int) -> Unit) {
            val title = TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, pad, 0, pad / 4)
            }
            container.addView(title)

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val swatchSize = (28 * density).toInt()
            val preview = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(swatchSize, swatchSize).apply {
                    rightMargin = pad / 2
                }
                setBackgroundColor(initialColor)
            }
            row.addView(preview)

            val sliderColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(sliderColumn)
            container.addView(row)

            var r = Color.red(initialColor)
            var g = Color.green(initialColor)
            var b = Color.blue(initialColor)

            fun emit() {
                val c = Color.rgb(r, g, b)
                preview.setBackgroundColor(c)
                onColor(c)
            }

            fun addChannel(name: String, initial: Int, setVal: (Int) -> Unit) {
                val channelLabel = TextView(context).apply {
                    text = "$name: $initial"
                    setTextColor(Color.LTGRAY)
                    textSize = 12f
                }
                sliderColumn.addView(channelLabel)
                val sb = SeekBar(context).apply {
                    max = 255
                    progress = initial
                }
                sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                        channelLabel.text = "$name: $progress"
                        if (fromUser) {
                            setVal(progress)
                            emit()
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
                sliderColumn.addView(sb)
            }

            addChannel("R", r) { r = it }
            addChannel("G", g) { g = it }
            addChannel("B", b) { b = it }
        }

        fun addChoiceRow(
            container: LinearLayout,
            label: String,
            options: List<String>,
            selectedIndex: Int,
            onSelect: (Int) -> Unit
        ) {
            val title = TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, pad, 0, pad / 4)
            }
            container.addView(title)

            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val buttons = mutableListOf<TextView>()

            fun refresh(selected: Int) {
                buttons.forEachIndexed { idx, tv ->
                    tv.setBackgroundColor(if (idx == selected) Color.rgb(90, 90, 30) else Color.rgb(45, 45, 45))
                }
            }

            options.forEachIndexed { index, opt ->
                val btn = TextView(context).apply {
                    text = opt
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) leftMargin = pad / 4
                    }
                }
                btn.setOnClickListener {
                    refresh(index)
                    onSelect(index)
                }
                buttons.add(btn)
                row.addView(btn)
            }
            refresh(selectedIndex)
            container.addView(row)
        }

        // ---- Auto-move + burn-in warning ----
        val warningText = TextView(context).apply {
            text = context.getString(R.string.settings_auto_move_warning)
            setTextColor(Color.rgb(255, 180, 60))
            textSize = 13f
            setPadding(0, 0, 0, pad / 2)
            visibility = if (prefs.autoMove) View.GONE else View.VISIBLE
        }

        val moveIntervalContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (prefs.autoMove) View.VISIBLE else View.GONE
        }

        addToggle(root, context.getString(R.string.settings_auto_move), prefs.autoMove) { checked ->
            prefs.autoMove = checked
            warningText.visibility = if (checked) View.GONE else View.VISIBLE
            moveIntervalContainer.visibility = if (checked) View.VISIBLE else View.GONE
            onChanged()
        }
        root.addView(warningText)

        addSlider(
            moveIntervalContainer,
            context.getString(R.string.settings_move_interval),
            Prefs.MOVE_INTERVAL_MIN_SECONDS, Prefs.MOVE_INTERVAL_MAX_SECONDS, prefs.moveIntervalSeconds, "s"
        ) { value ->
            prefs.moveIntervalSeconds = value
            onChanged()
        }
        root.addView(moveIntervalContainer)

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
            context.getString(R.string.settings_particle_speed),
            Prefs.PARTICLE_SPEED_MIN_PERCENT, Prefs.PARTICLE_SPEED_MAX_PERCENT, prefs.particleSpeedPercent, "%"
        ) { value ->
            prefs.particleSpeedPercent = value
            onChanged()
        }

        addSlider(
            root,
            context.getString(R.string.settings_particle_brightness),
            0, 255, prefs.particleBrightness
        ) { value ->
            prefs.particleBrightness = value
            onChanged()
        }

        // ---- Particle color ----
        val particleColorContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (prefs.randomParticleColors) View.GONE else View.VISIBLE
        }

        addToggle(root, context.getString(R.string.settings_random_colors), prefs.randomParticleColors) { checked ->
            prefs.randomParticleColors = checked
            particleColorContainer.visibility = if (checked) View.GONE else View.VISIBLE
            onChanged()
        }

        addColorPicker(particleColorContainer, context.getString(R.string.settings_particle_color), prefs.particleColor) { color ->
            prefs.particleColor = color
            onChanged()
        }
        root.addView(particleColorContainer)

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

        addSlider(
            root,
            context.getString(R.string.settings_line_width),
            Prefs.LINE_WIDTH_TENTHS_MIN, Prefs.LINE_WIDTH_TENTHS_MAX, prefs.lineWidthTenthsDp
        ) { value ->
            prefs.lineWidthTenthsDp = value
            onChanged()
        }

        // ---- Line color mode ----
        val lineColorContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (prefs.lineColorMode == Prefs.LINE_MODE_CUSTOM) View.VISIBLE else View.GONE
        }

        addChoiceRow(
            root,
            context.getString(R.string.settings_line_color_mode),
            listOf(
                context.getString(R.string.settings_line_mode_default),
                context.getString(R.string.settings_line_mode_custom),
                context.getString(R.string.settings_line_mode_gradient)
            ),
            prefs.lineColorMode
        ) { index ->
            prefs.lineColorMode = index
            lineColorContainer.visibility = if (index == Prefs.LINE_MODE_CUSTOM) View.VISIBLE else View.GONE
            onChanged()
        }

        addColorPicker(lineColorContainer, context.getString(R.string.settings_line_color), prefs.lineColor) { color ->
            prefs.lineColor = color
            onChanged()
        }
        root.addView(lineColorContainer)

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
