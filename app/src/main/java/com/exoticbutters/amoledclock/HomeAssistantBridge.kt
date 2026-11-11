package com.exoticbutters.amoledclock

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt

/**
 * Connects the clock to Home Assistant over MQTT and announces itself via
 * MQTT discovery as a device with:
 *  - number "Screen brightness" (0-100 % of the system brightness; falls
 *    back to this app's window brightness until WRITE_SETTINGS is granted)
 *  - switch "Auto brightness" (the system's adaptive brightness)
 *  - number "Volume" (0-100 % of the media stream)
 *  - button "Play <name>" for every sound in [SoundStore]
 *  - button "Stop sound"
 *
 * Besides the buttons, publishing a sound's name to `<base>/sound/play`
 * plays it, which is handy from an `mqtt.publish` action in a script.
 *
 * All MQTT work runs on one background thread, so connects and disconnects
 * can never overlap; anything touching the screen or audio player is
 * handed to the main thread.
 */
class HomeAssistantBridge(
    context: Context,
    /**
     * Called on the main thread with the window brightness override to use:
     * 0-100 while falling back to window brightness, -1 to clear it.
     */
    private val applyBrightness: (Int) -> Unit
) {
    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)
    private val sounds = SoundStore(appContext)
    private val player = SoundPlayer()
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    /** The live client. Written only on [io]; read on the main thread to drop stale messages. */
    @Volatile
    private var client: MqttAsyncClient? = null
    private var retryDelayMs = RETRY_MIN_MS
    private var lastPublishedVolume = -1
    private var lastPublishedBrightness = -1
    private var lastPublishedAuto: Boolean? = null

    /** Human-readable connection state, for the settings screen. */
    @Volatile
    var status: String = "Disabled"
        private set

    /** Called on the main thread whenever [status] changes. */
    var onStatusChanged: ((String) -> Unit)? = null

    private val deviceId = prefs.mqttDeviceId
    val baseTopic = "particleclock/$deviceId"
    private val availabilityTopic = "$baseTopic/availability"
    private val brightnessSetTopic = "$baseTopic/brightness/set"
    private val brightnessStateTopic = "$baseTopic/brightness/state"
    private val autoBrightnessSetTopic = "$baseTopic/auto_brightness/set"
    private val autoBrightnessStateTopic = "$baseTopic/auto_brightness/state"
    private val volumeSetTopic = "$baseTopic/volume/set"
    private val volumeStateTopic = "$baseTopic/volume/state"
    private val soundPlayTopic = "$baseTopic/sound/play"
    private val soundStopTopic = "$baseTopic/sound/stop"

    /**
     * Volume keys, the brightness slider, adaptive brightness and other apps
     * change these too (all are backed by system settings); keep HA in sync.
     */
    private val settingsObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            onIo {
                if (volumePercent() != lastPublishedVolume) publishVolumeState()
                if (brightnessPercent() != lastPublishedBrightness) publishBrightnessState()
                if (isAutoBrightness() != lastPublishedAuto) publishAutoBrightnessState()
            }
        }
    }

    // ---- Lifecycle ----

    /** (Re)connects using whatever is currently saved in [Prefs]. */
    fun restart() {
        onIo {
            disconnectCurrent()
            connectFromPrefs()
        }
    }

    fun shutdown() {
        main.removeCallbacksAndMessages(null)
        try {
            appContext.contentResolver.unregisterContentObserver(settingsObserver)
        } catch (_: Exception) {
        }
        player.stop()
        onIo { disconnectCurrent() }
        io.shutdown()
    }

    /** Re-announces the sound buttons after sounds were added or removed. */
    fun soundsChanged() {
        onIo { if (client?.isConnected == true) publishDiscovery() }
    }

    /** Re-sends brightness state, e.g. after the system-brightness permission was granted. */
    fun brightnessControlChanged() {
        onIo {
            publishBrightnessState()
            publishAutoBrightnessState()
        }
    }

    /** Plays a library sound locally (e.g. a preview from the settings screen). Main thread. */
    fun playSound(sound: SoundStore.Sound) = player.play(sound.file)

    private fun connectFromPrefs() {
        val host = prefs.mqttHost.trim()
        if (!prefs.mqttEnabled) {
            setStatus("Disabled")
            return
        }
        if (host.isEmpty()) {
            setStatus("No broker host set")
            return
        }
        val uri = "${if (prefs.mqttTls) "ssl" else "tcp"}://$host:${prefs.mqttPort}"
        val c = try {
            MqttAsyncClient(uri, deviceId, MemoryPersistence())
        } catch (e: Exception) {
            setStatus("Invalid broker address: ${e.message}")
            return
        }
        c.setCallback(Callback(c))
        client = c
        retryDelayMs = RETRY_MIN_MS
        main.post {
            appContext.contentResolver.unregisterContentObserver(settingsObserver)
            appContext.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver)
        }
        connect(c)
    }

    private fun connectOptions() = MqttConnectOptions().apply {
        isCleanSession = true
        // Reconnects are handled here instead: Paho's own reconnect timer
        // survives disconnect() and could resurrect a replaced client.
        isAutomaticReconnect = false
        connectionTimeout = 10
        keepAliveInterval = 30
        maxInflight = 100 // discovery for many sounds is a burst of QoS 1 messages
        val user = prefs.mqttUsername
        if (user.isNotEmpty()) {
            userName = user
            password = prefs.mqttPassword.toCharArray()
        }
        if (prefs.mqttTls && Build.VERSION.SDK_INT < 24) {
            // Paho's built-in hostname check needs an API 24 method; use the platform verifier instead.
            setHttpsHostnameVerificationEnabled(false)
            setSSLHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier())
        }
        setWill(availabilityTopic, PAYLOAD_OFFLINE.toByteArray(), 1, true)
    }

    private fun connect(c: MqttAsyncClient) {
        if (client !== c) return
        setStatus("Connecting to ${c.serverURI}…")
        try {
            c.connect(connectOptions(), null, object : IMqttActionListener {
                override fun onSuccess(token: IMqttToken?) {
                    onIo {
                        if (client === c) onConnected(c) else closeQuietly(c)
                    }
                }

                override fun onFailure(token: IMqttToken?, e: Throwable?) {
                    onIo {
                        if (client === c) {
                            setStatus("Connection failed: ${describe(e)}")
                            scheduleRetry(c)
                        }
                    }
                }
            })
        } catch (e: MqttException) {
            setStatus("Connection failed: ${describe(e)}")
            scheduleRetry(c)
        }
    }

    private fun scheduleRetry(c: MqttAsyncClient) {
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
        main.postDelayed({
            onIo { if (client === c && !c.isConnected) connect(c) }
        }, delay)
    }

    private fun disconnectCurrent() {
        val c = client ?: return
        client = null
        try {
            if (c.isConnected) {
                // A clean disconnect doesn't trigger the will, so say goodbye ourselves.
                c.publish(availabilityTopic, PAYLOAD_OFFLINE.toByteArray(), 1, true).waitForCompletion(2_000)
                c.disconnect(1_000).waitForCompletion(3_000)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect failed", e)
        }
        closeQuietly(c)
        setStatus("Disconnected")
    }

    private fun closeQuietly(c: MqttAsyncClient) {
        try {
            c.disconnectForcibly(0, 500, false)
        } catch (_: Exception) {
        }
        try {
            c.close(true)
        } catch (_: Exception) {
        }
    }

    private inner class Callback(private val c: MqttAsyncClient) : MqttCallback {
        override fun connectionLost(cause: Throwable?) {
            onIo {
                if (client === c) {
                    setStatus("Connection lost (${describe(cause)}), reconnecting…")
                    scheduleRetry(c)
                }
            }
        }

        override fun messageArrived(topic: String, message: MqttMessage) {
            val payload = String(message.payload, Charsets.UTF_8).trim()
            main.post { if (client === c) handleCommand(topic, payload) }
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
    }

    private fun onConnected(c: MqttAsyncClient) {
        retryDelayMs = RETRY_MIN_MS
        try {
            val topics = arrayOf(brightnessSetTopic, autoBrightnessSetTopic, volumeSetTopic, soundPlayTopic, soundStopTopic, HA_STATUS_TOPIC)
            c.subscribe(topics, IntArray(topics.size) { 1 })
        } catch (e: MqttException) {
            Log.w(TAG, "Subscribe failed", e)
        }
        publish(availabilityTopic, PAYLOAD_ONLINE)
        publishDiscovery()
        publishBrightnessState()
        publishAutoBrightnessState()
        publishVolumeState()
        setStatus("Connected to ${c.serverURI}")
    }

    // ---- Commands from Home Assistant (main thread) ----

    private fun handleCommand(topic: String, payload: String) {
        when (topic) {
            brightnessSetTopic -> payload.toFloatOrNull()?.let { value ->
                setBrightnessPercent(value.roundToInt().coerceIn(0, 100))
                onIo {
                    publishBrightnessState()
                    publishAutoBrightnessState()
                }
            }
            autoBrightnessSetTopic -> {
                when (payload.uppercase()) {
                    "ON" -> setAutoBrightness(true)
                    "OFF" -> setAutoBrightness(false)
                }
                onIo {
                    publishAutoBrightnessState()
                    publishBrightnessState()
                }
            }
            volumeSetTopic -> payload.toFloatOrNull()?.let { value ->
                setVolumePercent(value.roundToInt().coerceIn(0, 100))
                onIo { publishVolumeState() }
            }
            soundPlayTopic -> {
                val sound = sounds.find(payload)
                if (sound != null) player.play(sound.file) else Log.w(TAG, "No sound named \"$payload\"")
            }
            soundStopTopic -> player.stop()
            HA_STATUS_TOPIC -> if (payload == "online") {
                // Home Assistant restarted: re-announce so entities come back immediately.
                onIo {
                    if (client?.isConnected == true) {
                        publish(availabilityTopic, PAYLOAD_ONLINE)
                        publishDiscovery()
                        publishBrightnessState()
                        publishAutoBrightnessState()
                        publishVolumeState()
                    }
                }
            }
        }
    }

    private fun setBrightnessPercent(percent: Int) {
        if (canWriteSystemSettings(appContext)) {
            try {
                val resolver = appContext.contentResolver
                // Adaptive brightness would immediately override a manual value.
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (percent * MAX_SYSTEM_BRIGHTNESS / 100f).roundToInt())
                if (prefs.screenBrightnessPercent >= 0) {
                    prefs.screenBrightnessPercent = -1
                    applyBrightness(-1)
                }
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "System brightness change refused, using window brightness", e)
            }
        }
        prefs.screenBrightnessPercent = percent
        applyBrightness(percent)
    }

    private fun setAutoBrightness(on: Boolean) {
        if (!canWriteSystemSettings(appContext)) {
            Log.w(TAG, "Auto brightness needs the modify-system-settings permission")
            return
        }
        try {
            Settings.System.putInt(
                appContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (on) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            // A window override would hide what adaptive brightness does.
            if (on && prefs.screenBrightnessPercent >= 0) {
                prefs.screenBrightnessPercent = -1
                applyBrightness(-1)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Auto brightness change refused", e)
        }
    }

    private fun setVolumePercent(percent: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent * max / 100f).roundToInt(), 0)
        } catch (e: SecurityException) {
            // Thrown when Do Not Disturb forbids volume changes.
            Log.w(TAG, "Volume change refused", e)
        }
    }

    // ---- State & discovery publishing (io thread) ----

    private fun volumePercent(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
    }

    private fun brightnessPercent(): Int {
        val set = prefs.screenBrightnessPercent
        if (set >= 0) return set
        val system = try {
            Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {
            128
        }
        return (system.coerceIn(0, MAX_SYSTEM_BRIGHTNESS) * 100f / MAX_SYSTEM_BRIGHTNESS).roundToInt()
    }

    private fun isAutoBrightness(): Boolean = try {
        Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    } catch (e: Exception) {
        false
    }

    private fun publishBrightnessState() {
        val b = brightnessPercent()
        lastPublishedBrightness = b
        publish(brightnessStateTopic, b.toString())
    }

    private fun publishAutoBrightnessState() {
        val auto = isAutoBrightness()
        lastPublishedAuto = auto
        publish(autoBrightnessStateTopic, if (auto) "ON" else "OFF")
    }

    private fun publishVolumeState() {
        val v = volumePercent()
        lastPublishedVolume = v
        publish(volumeStateTopic, v.toString())
    }

    private fun publishDiscovery() {
        val device = JSONObject()
            .put("identifiers", JSONArray().put(deviceId))
            .put("name", prefs.mqttDeviceName)
            .put("manufacturer", "Particle Clock")
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("sw_version", appVersion())

        fun entity(name: String, objectId: String, icon: String) = JSONObject()
            .put("name", name)
            .put("unique_id", "${deviceId}_$objectId")
            .put("icon", icon)
            .put("availability_topic", availabilityTopic)
            .put("device", device)

        publish(
            configTopic("number", "screen_brightness"),
            entity("Screen brightness", "screen_brightness", "mdi:brightness-6")
                .put("command_topic", brightnessSetTopic)
                .put("state_topic", brightnessStateTopic)
                .put("min", 0).put("max", 100).put("step", 1)
                .put("unit_of_measurement", "%")
                .put("mode", "slider")
                .toString()
        )
        publish(
            configTopic("switch", "auto_brightness"),
            entity("Auto brightness", "auto_brightness", "mdi:brightness-auto")
                .put("command_topic", autoBrightnessSetTopic)
                .put("state_topic", autoBrightnessStateTopic)
                .toString()
        )
        publish(
            configTopic("number", "volume"),
            entity("Volume", "volume", "mdi:volume-high")
                .put("command_topic", volumeSetTopic)
                .put("state_topic", volumeStateTopic)
                .put("min", 0).put("max", 100).put("step", 1)
                .put("unit_of_measurement", "%")
                .put("mode", "slider")
                .toString()
        )
        publish(
            configTopic("button", "stop_sound"),
            entity("Stop sound", "stop_sound", "mdi:stop-circle-outline")
                .put("command_topic", soundStopTopic)
                .toString()
        )

        val current = sounds.list().distinctBy { it.slug }
        for (sound in current) {
            publish(
                configTopic("button", "sound_${sound.slug}"),
                entity("Play ${sound.name}", "sound_${sound.slug}", "mdi:play-circle-outline")
                    .put("command_topic", soundPlayTopic)
                    .put("payload_press", sound.name)
                    .toString()
            )
        }
        // An empty retained config tells HA to delete the entity.
        val currentSlugs = current.map { it.slug }.toSet()
        for (gone in prefs.mqttPublishedSoundSlugs - currentSlugs) {
            publish(configTopic("button", "sound_$gone"), "")
        }
        prefs.mqttPublishedSoundSlugs = currentSlugs
    }

    private fun configTopic(component: String, objectId: String) =
        "$DISCOVERY_PREFIX/$component/$deviceId/$objectId/config"

    private fun publish(topic: String, payload: String) {
        val c = client ?: return
        if (!c.isConnected) return
        try {
            c.publish(topic, payload.toByteArray(Charsets.UTF_8), 1, true)
        } catch (e: MqttException) {
            Log.w(TAG, "Publish to $topic failed", e)
        }
    }

    // ---- Helpers ----

    private fun onIo(block: () -> Unit) {
        try {
            io.execute {
                try {
                    block()
                } catch (e: Exception) {
                    Log.w(TAG, "MQTT task failed", e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Shut down; nothing left to do.
        }
    }

    private fun setStatus(text: String) {
        status = text
        main.post { onStatusChanged?.invoke(text) }
    }

    private fun describe(e: Throwable?): String {
        if (e == null) return "unknown error"
        val cause = e.cause
        return when {
            e is MqttException && e.reasonCode == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() -> "bad username or password"
            e is MqttException && e.reasonCode == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt() -> "not authorized"
            cause != null -> cause.message ?: cause.javaClass.simpleName
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    private fun appVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    companion object {
        private const val TAG = "HomeAssistantBridge"
        private const val DISCOVERY_PREFIX = "homeassistant"
        private const val HA_STATUS_TOPIC = "homeassistant/status"
        private const val PAYLOAD_ONLINE = "online"
        private const val PAYLOAD_OFFLINE = "offline"
        private const val RETRY_MIN_MS = 2_000L
        private const val RETRY_MAX_MS = 60_000L
        /** Settings.System.SCREEN_BRIGHTNESS is 0-255 on stock Android. */
        private const val MAX_SYSTEM_BRIGHTNESS = 255

        /** WRITE_SETTINGS is a special permission on Android 6+, granted from a system screen. */
        fun canWriteSystemSettings(context: Context): Boolean =
            Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(context)
    }
}
