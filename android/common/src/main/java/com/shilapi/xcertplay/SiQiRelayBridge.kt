// SPDX-License-Identifier: GPL-3.0-or-later
package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process bridge exposing embedded DiPlay session state, virtual-display mirror surface
 * attachment, headless CarPlay session lifecycle, and UI launcher controls to CarPlaySiQiRelay.
 */
object SiQiRelayBridge {
    private const val PREFS = "xcertplay_airplay"
    private const val KEY_VIRTUAL_SCREEN_ENABLED = "siqi_virtual_screen_enabled"
    private const val KEY_IPHONE_CONNECTED_BEFORE = "siqi_iphone_connected_before"
    private const val KEY_VIRTUAL_BASE_WIDTH = "siqi_virtual_base_width"
    private const val KEY_VIRTUAL_BASE_HEIGHT = "siqi_virtual_base_height"
    private const val KEY_VIRTUAL_SAFE_TOP = "siqi_virtual_safe_top"
    private const val KEY_VIRTUAL_SAFE_BOTTOM = "siqi_virtual_safe_bottom"
    internal const val DEFAULT_VIRTUAL_BASE_WIDTH = 2560
    internal const val DEFAULT_VIRTUAL_BASE_HEIGHT = 1120
    internal const val DEFAULT_VIRTUAL_SAFE_TOP = 0
    internal const val DEFAULT_VIRTUAL_SAFE_BOTTOM = 1120
    internal const val MAIN_SCREEN_TYPE = 110
    private const val VIRTUAL_MIRROR_KEY = "kove_virtual_display"

    interface VirtualRelayListener {
        fun onSessionStageChanged(stage: String, active: Boolean) {}
        fun onMainVideoStreamChanged(active: Boolean) {}
    }

    @Volatile
    private var fallbackKeyHandler: ((KeyEvent) -> Boolean)? = null

    private val surfaceLock = Any()
    @Volatile
    private var virtualMainSurface: Surface? = null

    @Volatile
    private var mainVideoStreamActive = false

    @Volatile
    private var sessionStageText = "待机中"

    @Volatile
    private var lastFailureHint: String? = null

    private val diagFileLock = Any()
    private const val DIAG_LOG_FILE = "autoconnect_diag.log"
    private const val MAX_DIAG_BYTES = 64 * 1024L

    private val listeners = CopyOnWriteArrayList<VirtualRelayListener>()

    @JvmStatic
    fun addVirtualRelayListener(listener: VirtualRelayListener?) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    @JvmStatic
    fun removeVirtualRelayListener(listener: VirtualRelayListener?) {
        if (listener != null) listeners.remove(listener)
    }

    @JvmStatic
    fun isVirtualScreenEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VIRTUAL_SCREEN_ENABLED, true)

    @JvmStatic
    fun setVirtualScreenEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VIRTUAL_SCREEN_ENABLED, enabled)
            .apply()
    }

    @JvmStatic
    fun setVirtualDisplayProfile(context: Context, baseWidth: Int, baseHeight: Int, safeTop: Int, safeBottom: Int) {
        val w = (baseWidth / 2 * 2).coerceAtLeast(640)
        val h = (baseHeight / 2 * 2).coerceAtLeast(360)
        val top = safeTop.coerceIn(0, h / 2)
        val bottom = safeBottom.coerceIn(top + 1, h)
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VIRTUAL_BASE_WIDTH, w)
            .putInt(KEY_VIRTUAL_BASE_HEIGHT, h)
            .putInt(KEY_VIRTUAL_SAFE_TOP, top)
            .putInt(KEY_VIRTUAL_SAFE_BOTTOM, bottom)
            .commit()
    }

    @JvmStatic
    fun getVirtualBaseWidth(context: Context): Int =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_VIRTUAL_BASE_WIDTH, DEFAULT_VIRTUAL_BASE_WIDTH)
            .coerceAtLeast(640)

    @JvmStatic
    fun getVirtualBaseHeight(context: Context): Int =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_VIRTUAL_BASE_HEIGHT, DEFAULT_VIRTUAL_BASE_HEIGHT)
            .coerceAtLeast(360)

    @JvmStatic
    fun getVirtualSafeTop(context: Context): Int =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_VIRTUAL_SAFE_TOP, DEFAULT_VIRTUAL_SAFE_TOP)

    @JvmStatic
    fun getVirtualSafeBottom(context: Context): Int =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_VIRTUAL_SAFE_BOTTOM, DEFAULT_VIRTUAL_SAFE_BOTTOM)

    @JvmStatic
    fun isVirtualScreenActive(context: Context): Boolean =
        isVirtualScreenEnabled(context) && virtualMainSurface != null

    /**
     * Attaches or clears the offscreen virtual-display surface for CarPlay primary video (stream 110).
     * Because it binds via [AndroidMediaSink.setMirrorSurface], minimizing or locking the phone's
     * physical display never tears down the cluster encoder pipeline.
     */
    @JvmStatic
    fun setVirtualMainSurface(surface: Surface?) {
        val valid = surface?.takeIf { it.isValid }
        synchronized(surfaceLock) {
            virtualMainSurface = valid
            CarPlayBackgroundSession.snapshot()?.sink?.setMirrorSurface(MAIN_SCREEN_TYPE, VIRTUAL_MIRROR_KEY, valid)
        }
    }

    @JvmStatic
    fun applyVirtualMirrors(sink: AndroidMediaSink) {
        val surface = synchronized(surfaceLock) { virtualMainSurface?.takeIf { it.isValid } }
        if (surface != null) {
            sink.setMirrorSurface(MAIN_SCREEN_TYPE, VIRTUAL_MIRROR_KEY, surface)
        }
    }

    @JvmStatic
    fun notifyMainStreamActive(active: Boolean) {
        if (mainVideoStreamActive == active) return
        mainVideoStreamActive = active
        for (l in listeners) {
            runCatching { l.onMainVideoStreamChanged(active) }
        }
    }

    @JvmStatic
    fun notifySessionStage(stage: String) {
        sessionStageText = stage
        val active = isSessionActive()
        for (l in listeners) {
            runCatching { l.onSessionStageChanged(stage, active) }
        }
    }

    @JvmStatic
    fun notifySessionError(hint: String?) {
        lastFailureHint = hint?.trim()?.takeIf { it.isNotEmpty() }
    }

    @JvmStatic
    fun getLastDiagnosticHint(): String? = lastFailureHint

    @JvmStatic
    fun appendDiagLog(context: Context?, tag: String, message: String) {
        Log.i(tag, message)
        val app = context?.applicationContext ?: return
        val line = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date()) + " [" + tag + "] " + message + "\n"
        synchronized(diagFileLock) {
            runCatching {
                val file = File(app.filesDir, DIAG_LOG_FILE)
                if (file.exists() && file.length() > MAX_DIAG_BYTES) {
                    val tail = file.readText().takeLast(32 * 1024)
                    file.writeText(tail + line)
                } else {
                    file.appendText(line)
                }
            }
        }
    }

    @JvmStatic
    fun getMissingWirelessPermissions(context: Context): Array<String> {
        val app = context.applicationContext
        if (!AirPlayPersistence.loadWirelessEnabled(app)) return emptyArray()
        val mode = AirPlayPersistence.loadWirelessHotspotMode(app)
        return WirelessPermissions.required(mode, Build.VERSION.SDK_INT)
            .filter { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()
    }

    @JvmStatic
    fun isMainVideoActive(): Boolean = mainVideoStreamActive

    @JvmStatic
    fun getSessionStage(): String = sessionStageText

    @JvmStatic
    fun isSessionActive(): Boolean = CarPlayBackgroundSession.active

    @JvmStatic
    fun hasSession(): Boolean = CarPlayBackgroundSession.hasSession()

    @JvmStatic
    fun recordIphoneConnected(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IPHONE_CONNECTED_BEFORE, true)
            .apply()
    }

    @JvmStatic
    fun hasPairedIphone(context: Context): Boolean {
        val app = context.applicationContext
        if (CarPlayBackgroundSession.active) return true
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_IPHONE_CONNECTED_BEFORE, false)) return true
        if (!DiPlayPreferences.phoneAddress(app).isNullOrBlank()) return true
        if (!prefs.getStringSet("pairing_ids", emptySet()).isNullOrEmpty()) return true
        return AirPlayPersistence.loadNewestLockdownRecord(app) != null
    }

    @JvmStatic
    fun getPairedIphoneLabel(context: Context): String? {
        val app = context.applicationContext
        val addr = DiPlayPreferences.phoneAddress(app)
        if (!addr.isNullOrBlank()) {
            return DiPlayPreferences.phoneName(app)
        }
        return if (hasPairedIphone(app)) "已配对 iPhone" else null
    }

    @JvmStatic
    fun isHeadlessModeRunning(): Boolean =
        CarPlayBackgroundSession.hasSession() && CarPlayScreen.current() == null

    @JvmStatic
    fun getHotspotSsid(): String? = CarPlayBackgroundSession.hotspotReady?.ssid

    @JvmStatic
    fun startHeadlessCarPlay(context: Context): Boolean =
        HeadlessCarPlaySession.start(context.applicationContext)

    @JvmStatic
    fun stopCarPlaySession(context: Context, onStopped: Runnable? = null) {
        mainVideoStreamActive = false
        HeadlessCarPlaySession.cancelScheduledReconnect()
        val current = CarPlayScreen.current()
        if (CarPlayBackgroundSession.hasSession()) {
            CarPlayBackgroundSession.stop {
                current?.finish()
                notifySessionStage("已断开")
                onStopped?.run()
            }
        } else {
            current?.finish()
            notifySessionStage("待机中")
            onStopped?.run()
        }
    }

    @JvmStatic
    fun restartIfRunning(context: Context) {
        val app = context.applicationContext
        val current = CarPlayScreen.current()
        val hadSession = CarPlayBackgroundSession.hasSession()
        if (current == null && !hadSession) return
        val wasHeadless = current == null
        current?.finish()
        if (hadSession) {
            HeadlessCarPlaySession.cancelScheduledReconnect()
            CarPlayBackgroundSession.stop {
                if (current != null) {
                    launchCarPlayHost(app)
                } else if (wasHeadless) {
                    HeadlessCarPlaySession.start(app)
                }
            }
        }
    }

    @JvmStatic
    fun stopAndRestartCarPlay(context: Context) {
        val app = context.applicationContext
        val current = CarPlayScreen.current()
        val wasHeadless = current == null && CarPlayBackgroundSession.hasSession()
        HeadlessCarPlaySession.cancelScheduledReconnect()
        current?.finish()
        CarPlayBackgroundSession.stop {
            if (wasHeadless) {
                HeadlessCarPlaySession.start(app)
            } else {
                launchCarPlayHost(app)
            }
        }
    }

    @JvmStatic
    fun launchCarPlayHost(context: Context) {
        val intent = CarPlayScreen.intent(context).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    @JvmStatic
    fun launchDiPlayHome(context: Context, page: String = "home") {
        val intent = Intent(context, DiPlayActivity::class.java).apply {
            putExtra("page", page)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        context.startActivity(intent)
    }

    @JvmStatic
    fun sendKnobWheel(delta: Int): Boolean =
        CarPlayBackgroundSession.snapshot()?.controller?.sendKnob(AirPlayKnobState(wheel = delta), true) == true

    @JvmStatic
    fun sendKnobSelect(): Boolean =
        CarPlayBackgroundSession.snapshot()?.controller?.sendKnob(AirPlayKnobState(select = true), true) == true

    @JvmStatic
    fun sendKnobBack(): Boolean =
        CarPlayBackgroundSession.snapshot()?.controller?.sendKnob(AirPlayKnobState(back = true), true) == true

    @JvmStatic
    fun requestSiri(): Boolean =
        CarPlayBackgroundSession.snapshot()?.controller?.requestSiri() == true

    @JvmStatic
    fun sendMediaNext(): Boolean =
        CarPlayBackgroundSession.snapshot()?.controller?.sendMediaButton(CarPlayMediaButton.NEXT) == true

    @JvmStatic
    fun sendMediaPrev(): Boolean =
        CarPlayBackgroundSession.snapshot()?.controller?.sendMediaButton(CarPlayMediaButton.PREVIOUS) == true

    @JvmStatic
    fun sendMediaPlayPause(): Boolean =
        CarPlayBackgroundSession.snapshot()?.controller?.sendMediaButton(CarPlayMediaButton.PLAY_PAUSE) == true

    @JvmStatic
    fun dispatchRemoteKey(event: KeyEvent): Boolean =
        CarPlayRemoteKeys.dispatch(event, CarPlayBackgroundSession.snapshot()?.controller)

    fun interface ExternalKeyHandler {
        fun onKeyEvent(event: KeyEvent): Boolean
    }

    @JvmStatic
    fun setFallbackKeyHandler(handler: ExternalKeyHandler?) {
        fallbackKeyHandler = if (handler == null) null else { ev -> handler.onKeyEvent(ev) }
    }

    @JvmStatic
    fun dispatchFallbackHandlebarKey(event: KeyEvent): Boolean =
        fallbackKeyHandler?.invoke(event) == true

    @JvmStatic
    fun isGlobalWheelKeyServiceConnected(): Boolean =
        WheelKeyService.connected()
}
