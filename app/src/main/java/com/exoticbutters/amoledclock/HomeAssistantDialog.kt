package com.exoticbutters.amoledclock

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Home Assistant setup: MQTT broker connection plus the on-device sound
 * library that HA can trigger. Broker fields apply when "Save & connect"
 * is tapped; sound changes apply (and are re-announced to HA) immediately.
 */
object HomeAssistantDialog {

    /**
     * The platform EditText draws a light box on this dialog theme on some
     * OS versions, making white text unreadable, so paint our own.
     */
    fun styleInput(input: EditText) {
        val p = (10 * input.resources.displayMetrics.density).toInt()
        input.setBackgroundColor(Color.rgb(30, 30, 30))
        input.setPadding(p, p, p, p)
        input.setTextColor(Color.WHITE)
        input.setHintTextColor(Color.GRAY)
    }

    fun show(activity: MainActivity, prefs: Prefs, bridge: HomeAssistantBridge, sounds: SoundStore) {
        val density = activity.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun heading(text: String) = TextView(activity).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, pad, 0, pad / 2)
        }.also { root.addView(it) }

        fun note(text: String, color: Int = Color.LTGRAY) = TextView(activity).apply {
            this.text = text
            setTextColor(color)
            textSize = 13f
            setPadding(0, pad / 4, 0, pad / 4)
        }

        fun field(label: String, value: String, inputType: Int): EditText {
            root.addView(TextView(activity).apply {
                text = label
                setTextColor(Color.LTGRAY)
                textSize = 13f
                setPadding(0, pad / 2, 0, 0)
            })
            return EditText(activity).apply {
                setText(value)
                this.inputType = inputType
                setSingleLine(true)
                styleInput(this)
            }.also { root.addView(it) }
        }

        fun button(text: String, onClick: () -> Unit) = TextView(activity).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            setBackgroundColor(Color.rgb(45, 45, 45))
            isClickable = true
            setOnClickListener { onClick() }
        }

        // Same hand-drawn toggle as SettingsDialog, for the same visibility reasons.
        fun toggle(label: String, initial: Boolean): () -> Boolean {
            var state = initial
            val row = TextView(activity).apply {
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, pad / 2, 0, pad / 2)
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            fun render() {
                row.text = "$label: ${if (state) "ON" else "OFF"}  (tap to toggle)"
            }
            render()
            row.setOnClickListener {
                state = !state
                render()
            }
            root.addView(row)
            return { state }
        }

        // ---- MQTT connection ----
        heading(activity.getString(R.string.ha_mqtt_heading))
        val enabled = toggle(activity.getString(R.string.ha_enabled), prefs.mqttEnabled)
        val host = field(activity.getString(R.string.ha_host), prefs.mqttHost, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply {
            hint = "192.168.1.10"
        }
        val port = field(activity.getString(R.string.ha_port), prefs.mqttPort.toString(), InputType.TYPE_CLASS_NUMBER)
        val user = field(activity.getString(R.string.ha_username), prefs.mqttUsername, InputType.TYPE_CLASS_TEXT)
        val pass = field(activity.getString(R.string.ha_password), prefs.mqttPassword, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val tls = toggle(activity.getString(R.string.ha_tls), prefs.mqttTls)
        val deviceName = field(activity.getString(R.string.ha_device_name), prefs.mqttDeviceName, InputType.TYPE_CLASS_TEXT)

        val statusView = note("").apply { setTextColor(Color.rgb(140, 200, 255)) }
        fun renderStatus(s: String) {
            statusView.text = activity.getString(R.string.ha_status, s)
        }
        renderStatus(bridge.status)

        root.addView(button(activity.getString(R.string.ha_save_connect)) {
            prefs.mqttEnabled = enabled()
            prefs.mqttHost = host.text.toString().trim()
            prefs.mqttPort = port.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: Prefs.DEFAULT_MQTT_PORT
            port.setText(prefs.mqttPort.toString())
            prefs.mqttUsername = user.text.toString().trim()
            prefs.mqttPassword = pass.text.toString()
            prefs.mqttTls = tls()
            prefs.mqttDeviceName = deviceName.text.toString().trim()
            bridge.restart()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = pad
            }
        })
        root.addView(statusView)
        root.addView(note(activity.getString(R.string.ha_topic_info, bridge.baseTopic), Color.GRAY))

        // ---- System brightness permission ----
        heading(activity.getString(R.string.ha_brightness_heading))
        val permissionNote = note("")
        root.addView(permissionNote)
        val grantButton = button(activity.getString(R.string.ha_brightness_grant)) { activity.requestSystemSettingsAccess() }
        root.addView(grantButton)
        fun refreshPermission() {
            val granted = HomeAssistantBridge.canWriteSystemSettings(activity)
            permissionNote.text = activity.getString(if (granted) R.string.ha_brightness_granted else R.string.ha_brightness_missing)
            permissionNote.setTextColor(if (granted) Color.LTGRAY else Color.rgb(255, 180, 60))
            grantButton.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
        }
        refreshPermission()

        // ---- Sounds ----
        heading(activity.getString(R.string.ha_sounds_heading))
        root.addView(note(activity.getString(R.string.ha_sounds_info), Color.GRAY))
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        fun refreshSounds() {
            list.removeAllViews()
            val all = sounds.list()
            if (all.isEmpty()) {
                list.addView(note(activity.getString(R.string.ha_no_sounds)))
            }
            for (sound in all) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, pad / 4, 0, pad / 4)
                }
                row.addView(TextView(activity).apply {
                    text = sound.name
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(button(activity.getString(R.string.ha_sound_play)) { activity.previewSound(sound) })
                row.addView(button(activity.getString(R.string.ha_sound_delete)) {
                    AlertDialog.Builder(activity, android.R.style.Theme_Black)
                        .setMessage(activity.getString(R.string.ha_sound_delete_confirm, sound.name))
                        .setPositiveButton(activity.getString(R.string.ha_sound_delete)) { _, _ ->
                            sounds.delete(sound)
                            activity.onSoundsChanged()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = pad / 4
                    }
                })
                list.addView(row)
            }
        }
        refreshSounds()

        root.addView(button(activity.getString(R.string.ha_add_sound)) { activity.pickSound() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = pad / 2
            }
        })

        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_Black)
            .setTitle(activity.getString(R.string.ha_title))
            .setView(ScrollView(activity).apply { addView(root) })
            .setPositiveButton(activity.getString(R.string.settings_close), null)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        bridge.onStatusChanged = { renderStatus(it) }
        activity.soundListListener = { refreshSounds() }
        activity.permissionListener = { refreshPermission() }
        dialog.setOnDismissListener {
            bridge.onStatusChanged = null
            activity.soundListListener = null
            activity.permissionListener = null
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.WHITE)
    }
}
