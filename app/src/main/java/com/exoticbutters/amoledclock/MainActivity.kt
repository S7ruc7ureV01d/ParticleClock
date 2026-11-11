package com.exoticbutters.amoledclock

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var clockView: ParticleClockView
    private lateinit var prefs: Prefs
    private lateinit var sounds: SoundStore
    private lateinit var bridge: HomeAssistantBridge

    /** Set while the Home Assistant screen is open, so it can redraw its sound list. */
    var soundListListener: (() -> Unit)? = null

    /** Set while the Home Assistant screen is open, so it can refresh the permission row. */
    var permissionListener: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        sounds = SoundStore(this)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.decorView.systemUiVisibility = immersiveFlags()
        window.decorView.setOnSystemUiVisibilityChangeListener {
            window.decorView.systemUiVisibility = immersiveFlags()
        }
        applyScreenBrightness(prefs.screenBrightnessPercent)

        // Lives as long as the activity (not paused in onPause), so HA keeps
        // control while e.g. the settings dialog is covering the clock.
        bridge = HomeAssistantBridge(this) { applyScreenBrightness(it) }
        bridge.restart()

        clockView = ParticleClockView(this)
        clockView.onRequestSettings = {
            SettingsDialog.show(this, prefs, { clockView.applySettings() }) {
                HomeAssistantDialog.show(this, prefs, bridge, sounds)
            }
        }
        setContentView(clockView)
    }

    override fun onDestroy() {
        bridge.shutdown()
        super.onDestroy()
    }

    /**
     * 0-100 overrides this window's brightness (the fallback while system
     * brightness can't be written); negative follows the system setting.
     */
    private fun applyScreenBrightness(percent: Int) {
        val lp = window.attributes
        lp.screenBrightness = if (percent < 0) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            percent.coerceIn(0, 100) / 100f
        }
        window.attributes = lp
    }

    // ---- Sound library ----

    fun pickSound() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(intent, REQUEST_PICK_SOUND)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.ha_no_file_picker, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_SOUND || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        promptSoundName(uri)
    }

    private fun promptSoundName(uri: Uri) {
        val input = EditText(this).apply {
            setText(sounds.suggestedName(uri))
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            HomeAssistantDialog.styleInput(this)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this, android.R.style.Theme_Black)
            .setTitle(R.string.ha_sound_name_title)
            .setMessage(R.string.ha_sound_name_message)
            .setView(container)
            .setPositiveButton(R.string.ha_sound_save) { _, _ -> importSound(uri, input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importSound(uri: Uri, name: String) {
        Thread {
            val error = try {
                sounds.import(uri, name)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                if (error != null) {
                    Toast.makeText(this, getString(R.string.ha_import_failed, error), Toast.LENGTH_LONG).show()
                } else {
                    onSoundsChanged()
                }
            }
        }.start()
    }

    fun previewSound(sound: SoundStore.Sound) = bridge.playSound(sound)

    fun onSoundsChanged() {
        soundListListener?.invoke()
        bridge.soundsChanged()
    }

    private fun immersiveFlags(): Int {
        var flags = View.SYSTEM_UI_FLAG_LOW_PROFILE or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (Build.VERSION.SDK_INT >= 14) {
            flags = flags or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        if (Build.VERSION.SDK_INT >= 19) {
            flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        return flags
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = immersiveFlags()
        }
    }

    override fun onResume() {
        super.onResume()
        clockView.start()
        // Back from the "modify system settings" screen with access granted:
        // drop the window-brightness fallback so the system value shows.
        if (HomeAssistantBridge.canWriteSystemSettings(this) && prefs.screenBrightnessPercent >= 0) {
            prefs.screenBrightnessPercent = -1
            applyScreenBrightness(-1)
            bridge.brightnessControlChanged()
        }
        permissionListener?.invoke()
    }

    /** Opens the system screen where the user lets this app change system brightness. */
    fun requestSystemSettingsAccess() {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.ha_no_settings_screen, Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        clockView.stop()
    }

    companion object {
        private const val REQUEST_PICK_SOUND = 1001
    }
}
