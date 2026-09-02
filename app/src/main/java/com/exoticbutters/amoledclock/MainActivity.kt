package com.exoticbutters.amoledclock

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager

class MainActivity : Activity() {

    private lateinit var clockView: ParticleClockView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.decorView.systemUiVisibility = immersiveFlags()
        window.decorView.setOnSystemUiVisibilityChangeListener {
            window.decorView.systemUiVisibility = immersiveFlags()
        }

        clockView = ParticleClockView(this)
        clockView.setOnLongClickListener {
            SettingsDialog.show(this, Prefs(this)) { clockView.applySettings() }
            true
        }
        setContentView(clockView)
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
    }

    override fun onPause() {
        super.onPause()
        clockView.stop()
    }
}
