// SPDX-License-Identifier: GPL-3.0-or-later
package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.airplay.forTransport
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.MediaAudioBuffer
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs an offscreen DiPlay CarPlay session inside [DiPlaySessionService] so iPhone CarPlay can
 * decode straight to the virtual-display H.264 pipeline (`15456`) without keeping
 * [CarPlayHostActivity] in the physical foreground. Opening [CarPlayHostActivity] later adopts
 * this live session via [CarPlayBackgroundSession], and closing it hands ownership back here.
 */
internal object HeadlessCarPlaySession {
    private const val TAG = "HeadlessCarPlay"
    private const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 3_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val teardownExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "headless-carplay-teardown").apply { isDaemon = true }
    }
    private val generation = AtomicInteger(0)
    private val retryBudget = WirelessStartupRetryBudget()

    @Volatile
    private var pendingReconnect: Runnable? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var activeSession: AirPlaySession? = null

    @Volatile
    private var lastFailureReason: String? = null

    fun start(context: Context): Boolean {
        val app = context.applicationContext
        appContext = app

        if (CarPlayBackgroundSession.hasSession()) {
            val snap = CarPlayBackgroundSession.snapshot()
            if (snap != null && !snap.controller.isClosed()) {
                SiQiRelayBridge.applyVirtualMirrors(snap.sink)
                SiQiRelayBridge.notifySessionStage(
                    if (CarPlayBackgroundSession.active) "CarPlay 运行中" else "正在等待 iPhone 握手...",
                )
                return true
            }
            CarPlayBackgroundSession.clear()
        }

        val wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(app)
        val hotspotMode = AirPlayPersistence.loadWirelessHotspotMode(app)
        if (wirelessEnabled) {
            val missing = WirelessPermissions.required(hotspotMode, Build.VERSION.SDK_INT)
                .filter { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                val names = missing.joinToString(",") { it.substringAfterLast('.') }
                SiQiRelayBridge.notifySessionError("缺少权限: $names")
                SiQiRelayBridge.notifySessionStage("请在手机授权附近设备/蓝牙权限 ($names)")
                SiQiRelayBridge.appendDiagLog(app, TAG, "Blocked start: missing permissions=$names")
                return false
            }
        } else {
            val vpnNeeded = runCatching { CarPlayVpnService.prepare(app) != null }.getOrDefault(true)
            if (vpnNeeded) {
                SiQiRelayBridge.notifySessionError("有线模式需要 VPN 授权")
                SiQiRelayBridge.notifySessionStage("有线模式需先打开预览页授予 VPN")
                SiQiRelayBridge.appendDiagLog(app, TAG, "Blocked start: VPN consent required for wired mode")
                return false
            }
        }

        val mfiTarget = AirPlayPersistence.loadMfiTarget(app)
        runCatching { DiPlayBootstrap.ensure(app, mfiTarget) }.onFailure {
            Log.w(TAG, "DiPlayBootstrap failed: ${it.message}")
            SiQiRelayBridge.notifySessionError("MFi 初始化失败: ${it.message}")
            SiQiRelayBridge.notifySessionStage("MFi 认证初始化失败")
            SiQiRelayBridge.appendDiagLog(app, TAG, "Blocked start: DiPlayBootstrap failed: ${it.message}")
            return false
        }

        cancelScheduledReconnect()
        val gen = generation.incrementAndGet()
        val (baseW, baseH) = resolveLandscapeDisplaySize(app)
        val identity = AirPlayPersistence.loadIdentity(app)
        val runtimeConfig = createRuntimeConfig(app, identity, wirelessEnabled)
        val airPlayConfig = createAirPlayConfig(app, identity, baseW, baseH)
            .forTransport(runtimeConfig.transport == CarPlayTransport.WIRELESS)

        val disableAudio = AirPlayPersistence.loadCarBluetoothAudio(app)
        val micAvailable = !disableAudio &&
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val locationProvider = if (runtimeConfig.locationReportingEnabled) AndroidCarPlayLocationProvider(app) else null
        val fps = AirPlayPersistence.loadFps(app)
        val smoothVideo = AirPlayPersistence.loadSmoothVideo(app)

        val sink = AndroidMediaSink(
            surface = null,
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            preferSoftwareHevcDecoder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(app),
            advancedAudioChannelMapping = false,
            audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(app),
            audioFocusAutoYield = AirPlayPersistence.loadAudioFocusAutoYield(app),
            mediaChannel = AirPlayPersistence.loadMediaAudioChannel(app),
            navigationChannel = AirPlayPersistence.loadNavigationAudioChannel(app),
            navigationAttenuationDb = AirPlayPersistence.loadNavigationAudioAttenuationDb(app),
            callAttenuationDb = AirPlayPersistence.loadCallAudioAttenuationDb(app),
            context = app,
            navigationStreamType = AirPlayPersistence.loadNavigationStreamType(app),
            onScreenStreamActiveChanged = { type, active ->
                if (type == SiQiRelayBridge.MAIN_SCREEN_TYPE) {
                    SiQiRelayBridge.notifyMainStreamActive(active)
                }
            },
            mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(app, wireless = wirelessEnabled),
            callBufferMillis = MediaAudioBuffer.callMillis(wireless = wirelessEnabled),
            onMediaAudioChanged = CarPlayMediaKeys::onMediaAudioChanged,
            callEchoCancellation = AirPlayPersistence.loadCallEchoCancellation(app),
            callVoiceFilter = AirPlayPersistence.loadCallVoiceFilter(app),
            callSpeakerphone = { AirPlayPersistence.loadCallSpeakerphone(app) },
            callMediaOutput = { AirPlayPersistence.loadCallMediaOutput(app) },
            videoPacingDelayMillis = if (smoothVideo) smoothVideoDelayMillis(fps) else 0,
            mainVideoFrameRate = fps,
            vendorLowLatencyDecoder = AirPlayPersistence.loadLowLatencyDecoder(app),
            mtkDecoderTuningEnabled = AirPlayPersistence.loadMtkDecoderTuning(app),
            navigationPromptAudioFocus = AirPlayPersistence.loadNavigationPromptAudioFocus(app),
        )
        SiQiRelayBridge.applyVirtualMirrors(sink)

        val media = CarPlayMediaEngine(sink = sink, microphoneEnabled = micAvailable)
        val pairings = AirPlayPersistence.loadPairings(app) { id, key ->
            AirPlayPersistence.savePairing(app, id, key)
        }
        val controller = CarPlayController(
            context = app,
            config = runtimeConfig,
            airPlayConfig = airPlayConfig,
            identity = identity,
            pairings = pairings,
            listener = createSessionListener(app, gen),
            media = media,
            reportStatus = createStatusReporter(app, gen),
            loadPairRecord = { pk -> AirPlayPersistence.loadLockdownRecord(app, pk) },
            loadNewestPairRecord = { AirPlayPersistence.loadNewestLockdownRecord(app) },
            savePairRecord = { rec -> AirPlayPersistence.saveLockdownRecord(app, rec) },
            clearPairRecord = { pk -> AirPlayPersistence.clearLockdownRecord(app, pk) },
            locationProvider = locationProvider,
        )

        val sessionDisplay = CarPlaySessionDisplay(
            width = airPlayConfig.main.widthPixels,
            height = airPlayConfig.main.heightPixels,
            rotation = 1,
            hideTopBar = AirPlayPersistence.loadHideTopBar(app),
            hideBottomBar = AirPlayPersistence.loadHideBottomBar(app),
            windowWidth = baseW,
            windowHeight = baseH,
        )

        CarPlayMediaKeys.attach(app, controller)
        CarPlayBackgroundSession.store(controller, sink, baseW, baseH, this, sessionDisplay) { completion ->
            stopInternal(completion = completion)
        }

        return try {
            // Optional secondary FGS: if Android 14/15 denies starting DiPlaySessionService from
            // background/lockscreen (e.g. ForegroundServiceStartNotAllowedException), BridgeService
            // already keeps this process alive so we must never abort CarPlay bring-up.
            runCatching {
                ContextCompat.startForegroundService(app, Intent(app, DiPlaySessionService::class.java))
            }.onFailure { err ->
                SiQiRelayBridge.appendDiagLog(
                    app,
                    TAG,
                    "DiPlaySessionService background start skipped (${err.javaClass.simpleName}): continuing under BridgeService",
                )
            }
            val savedPhone = DiPlayPreferences.phoneAddress(app)?.let { DiPlayPreferences.phoneName(app) }
            val retrySuffix = lastFailureReason?.let { " (上次: ${summarizeFailure(it)})" }.orEmpty()
            SiQiRelayBridge.notifySessionStage(
                if (savedPhone != null) "正在连接 $savedPhone...$retrySuffix"
                else "正在启动无线 CarPlay...$retrySuffix",
            )
            controller.start()
            SiQiRelayBridge.appendDiagLog(
                app,
                TAG,
                "Started headless CarPlay gen=$gen mode=$hotspotMode wireless=$wirelessEnabled " +
                    "phone=${savedPhone ?: "auto"} canvas=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels}",
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start headless CarPlay: ${t.message}", t)
            SiQiRelayBridge.notifySessionError(t.message ?: t.javaClass.simpleName)
            SiQiRelayBridge.appendDiagLog(app, TAG, "Start exception: ${t.javaClass.simpleName}: ${t.message}")
            stopInternal(stopForegroundService = false) {}
            SiQiRelayBridge.notifySessionStage("启动失败: ${t.message}")
            false
        }
    }

    /**
     * Transfers background ownership from a closing preview [CarPlayHostActivity] back to
     * [HeadlessCarPlaySession] so auto-reconnect and status tracking remain uninterrupted.
     */
    fun reattachAsOwner(context: Context) {
        val app = context.applicationContext
        appContext = app
        val snap = CarPlayBackgroundSession.snapshot() ?: return
        if (snap.controller.isClosed()) return
        val gen = generation.incrementAndGet()
        activeSession = snap.controller.activeAirPlaySession()
        CarPlayBackgroundSession.store(
            snap.controller,
            snap.sink,
            snap.width,
            snap.height,
            this,
            snap.display,
        ) { completion -> stopInternal(completion = completion) }
        snap.controller.attachUi(createSessionListener(app, gen), createStatusReporter(app, gen))
        snap.sink.setScreenStreamActiveChangedListener { type, active ->
            if (type == SiQiRelayBridge.MAIN_SCREEN_TYPE) {
                SiQiRelayBridge.notifyMainStreamActive(active)
            }
        }
        SiQiRelayBridge.applyVirtualMirrors(snap.sink)
        SiQiRelayBridge.notifySessionStage(
            if (CarPlayBackgroundSession.active) "CarPlay 后台运行中" else "正在等待 iPhone 握手...",
        )
        Log.i(TAG, "Reattached background CarPlay ownership to HeadlessCarPlaySession gen=$gen")
    }

    fun cancelScheduledReconnect() {
        val task = pendingReconnect ?: return
        pendingReconnect = null
        mainHandler.removeCallbacks(task)
    }

    private fun stopInternal(
        stopForegroundService: Boolean = true,
        restartContext: Context? = null,
        completion: () -> Unit = {},
    ) {
        cancelScheduledReconnect()
        val stopGen = generation.incrementAndGet()
        activeSession = null
        val snap = CarPlayBackgroundSession.snapshot()
        val oldController = snap?.controller
        val oldSink = snap?.sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController)
        SiQiRelayBridge.notifyMainStreamActive(false)
        teardownExecutor.execute {
            runCatching { oldController?.close() }
            runCatching { oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) }
            runCatching { oldSink?.close() }
            if (stopForegroundService) {
                appContext?.let { app ->
                    runCatching { app.stopService(Intent(app, DiPlaySessionService::class.java)) }
                }
            }
            mainHandler.post {
                completion()
                if (restartContext != null && generation.get() == stopGen && !CarPlayBackgroundSession.hasSession()) {
                    start(restartContext)
                }
            }
        }
    }

    private fun scheduleReconnect(app: Context, gen: Int, reason: String) {
        if (!CarPlayBackgroundSession.isOwner(this) || gen != generation.get()) return
        if (pendingReconnect != null) return
        val delay = retryBudget.nextDelayMillis() ?: 8_000L
        val shortReason = summarizeFailure(reason)
        lastFailureReason = shortReason
        SiQiRelayBridge.notifySessionError(shortReason)
        SiQiRelayBridge.appendDiagLog(app, TAG, "Headless reconnect in ${delay}ms: $reason")
        SiQiRelayBridge.notifySessionStage("等待重试 (${delay / 1000}s): $shortReason")
        lateinit var task: Runnable
        task = Runnable {
            if (pendingReconnect !== task) return@Runnable
            pendingReconnect = null
            if (!CarPlayBackgroundSession.isOwner(this) || gen != generation.get()) return@Runnable
            // Keep DiPlaySessionService running across reconnects so Android 15 background FGS rules
            // cannot block re-creating the session after an idle disconnect or BT retry.
            stopInternal(stopForegroundService = false, restartContext = app)
        }
        pendingReconnect = task
        mainHandler.postDelayed(task, delay)
    }

    private fun createSessionListener(app: Context, gen: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                mainHandler.post {
                    if (gen != generation.get() || !CarPlayBackgroundSession.isOwner(HeadlessCarPlaySession)) return@post
                    activeSession = session
                    lastFailureReason = null
                    CarPlayBackgroundSession.active = true
                    SiQiRelayBridge.notifySessionError(null)
                    SiQiRelayBridge.recordIphoneConnected(app)
                    SiQiRelayBridge.appendDiagLog(app, TAG, "iPhone wireless CarPlay session active")
                    SiQiRelayBridge.notifySessionStage("iPhone 无线 CarPlay 已连接")
                }
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                mainHandler.post {
                    if (gen != generation.get() || !CarPlayBackgroundSession.isOwner(HeadlessCarPlaySession)) return@post
                    if (activeSession == null) {
                        activeSession = session
                    }
                    lastFailureReason = null
                    SiQiRelayBridge.notifySessionError(null)
                    val firstFrame = retryBudget.firstFrame(session, SystemClock.elapsedRealtime())
                    if (firstFrame) {
                        SiQiRelayBridge.notifyMainStreamActive(true)
                        SiQiRelayBridge.appendDiagLog(app, TAG, "First CarPlay frame rendered onto virtual mirror")
                        SiQiRelayBridge.notifySessionStage("CarPlay 画面投屏中")
                        mainHandler.postDelayed({
                            if (gen == generation.get() && CarPlayBackgroundSession.isOwner(HeadlessCarPlaySession)) {
                                retryBudget.resetIfStable(session, SystemClock.elapsedRealtime())
                            }
                        }, WirelessStartupPolicy.STABLE_SESSION_MILLIS)
                    }
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                mainHandler.post {
                    val active = activeSession
                    if (active != null && active !== session) {
                        Log.i(TAG, "Ignoring ended non-active AirPlay connection; keeping live session")
                        return@post
                    }
                    if (gen != generation.get() || !CarPlayBackgroundSession.isOwner(HeadlessCarPlaySession)) return@post
                    activeSession = null
                    retryBudget.disconnected()
                    CarPlayBackgroundSession.active = false
                    SiQiRelayBridge.notifyMainStreamActive(false)
                    SiQiRelayBridge.appendDiagLog(app, TAG, "AirPlay session ended -> scheduling reconnect")
                    scheduleReconnect(app, gen, "会话已结束")
                }
            }

            override fun onTransportError(message: String) {
                mainHandler.post {
                    if (gen != generation.get() || !CarPlayBackgroundSession.isOwner(HeadlessCarPlaySession)) return@post
                    retryBudget.disconnected()
                    CarPlayBackgroundSession.active = false
                    SiQiRelayBridge.notifyMainStreamActive(false)
                    SiQiRelayBridge.appendDiagLog(app, TAG, "Transport error: $message")
                    scheduleReconnect(app, gen, message)
                }
            }

            override fun onHostUiRequested(session: AirPlaySession) {
                mainHandler.post {
                    app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { intent ->
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { app.startActivity(intent) }
                    }
                }
            }

            override val wantsTrace: Boolean get() = false

            override fun onDebugLog(message: String) {
                if (gen != generation.get()) return
                if (message.startsWith(CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX) ||
                    message.contains("wireless", ignoreCase = true) ||
                    message.contains("Bluetooth", ignoreCase = true) ||
                    message.contains("P2P", ignoreCase = true) ||
                    message.contains("failed", ignoreCase = true)
                ) {
                    SiQiRelayBridge.appendDiagLog(app, TAG, message)
                }
            }
        }

    private fun createStatusReporter(app: Context, gen: Int): (CarPlayStatus) -> Unit = { status ->
        mainHandler.post {
            if (gen != generation.get() || !CarPlayBackgroundSession.isOwner(HeadlessCarPlaySession)) return@post
            val savedPhone = DiPlayPreferences.phoneAddress(app)?.let { DiPlayPreferences.phoneName(app) }
            val retryNote = lastFailureReason?.let { " · 上次: $it" }.orEmpty()
            when (status) {
                is CarPlayStatus.DiscoveringMfi ->
                    SiQiRelayBridge.notifySessionStage("正在准备 MFi 离线认证...")
                is CarPlayStatus.MfiReady ->
                    SiQiRelayBridge.notifySessionStage("MFi 就绪，准备无线握手...")
                is CarPlayStatus.StartingHotspot -> {
                    CarPlayBackgroundSession.hotspotReady = null
                    SiQiRelayBridge.notifySessionStage("正在建立无线连接...$retryNote")
                }
                is CarPlayStatus.HotspotReady -> {
                    CarPlayBackgroundSession.hotspotReady = status
                    SiQiRelayBridge.notifySessionStage("无线通道就绪，等待 ${savedPhone ?: "iPhone"} 接入...")
                }
                is CarPlayStatus.WaitingForPairedIphone ->
                    // CarPlayController emits WaitingForPairedIphone unconditionally before reading saved phoneAddress.
                    SiQiRelayBridge.notifySessionStage(
                        if (savedPhone != null) "准备蓝牙唤醒 $savedPhone...$retryNote"
                        else "请在 DiPlay 向导中选择已配对 iPhone",
                    )
                is CarPlayStatus.ConnectingBluetooth ->
                    SiQiRelayBridge.notifySessionStage("正在通过蓝牙唤醒 ${savedPhone ?: "iPhone"}...$retryNote")
                is CarPlayStatus.AttachingNetwork ->
                    SiQiRelayBridge.notifySessionStage("正在绑定无线 AirPlay 服务...")
                is CarPlayStatus.RunningWireless, is CarPlayStatus.WirelessActive, is CarPlayStatus.WirelessActiveFallback -> {
                    lastFailureReason = null
                    SiQiRelayBridge.notifySessionError(null)
                    SiQiRelayBridge.notifySessionStage("蓝牙/热点已就绪，等待 ${savedPhone ?: "iPhone"} 接入 AirPlay...")
                }
                is CarPlayStatus.ControlEnded -> {
                    retryBudget.disconnected()
                    CarPlayBackgroundSession.active = false
                    scheduleReconnect(app, gen, "蓝牙/iAP2 控制通道中断")
                }
                is CarPlayStatus.Failed -> {
                    retryBudget.disconnected()
                    CarPlayBackgroundSession.active = false
                    scheduleReconnect(app, gen, status.message)
                }
                else -> Unit
            }
        }
    }

    private fun summarizeFailure(raw: String): String {
        val msg = raw.trim()
        return when {
            msg.contains("RFCOMM", ignoreCase = true) && msg.contains("Timed out", ignoreCase = true) ->
                "蓝牙唤醒 iPhone 超时 (请确认 iPhone 蓝牙开启)"
            msg.contains("Bluetooth", ignoreCase = true) && msg.contains("off", ignoreCase = true) ->
                "手机蓝牙未开启"
            msg.contains("No AirPlay TCP", ignoreCase = true) ->
                "iPhone 未连上手机热点 (AirPlay 超时)"
            msg.contains("P2P", ignoreCase = true) && msg.contains("BUSY", ignoreCase = true) ->
                "Wi-Fi Direct 正忙"
            msg.contains("P2P", ignoreCase = true) || msg.contains("hotspot", ignoreCase = true) ->
                "无线热点启动异常: ${msg.take(48)}"
            else -> msg.take(64)
        }
    }

    private fun resolveLandscapeDisplaySize(context: Context): Pair<Int, Int> {
        if (SiQiRelayBridge.isVirtualScreenEnabled(context)) {
            return even(SiQiRelayBridge.getVirtualBaseWidth(context)) to even(SiQiRelayBridge.getVirtualBaseHeight(context))
        }
        val wm = context.getSystemService(WindowManager::class.java)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(dm)
        val w = if (dm.widthPixels > 0) maxOf(dm.widthPixels, dm.heightPixels) else 2400
        val h = if (dm.heightPixels > 0) minOf(dm.widthPixels, dm.heightPixels) else 1080
        return even(w) to even(h)
    }

    private fun even(v: Int): Int = (v / 2 * 2).coerceAtLeast(2)

    private fun createRuntimeConfig(
        context: Context,
        identity: AirPlayIdentity,
        wirelessEnabled: Boolean,
    ): CarPlayRuntimeConfig {
        val mfiTarget = AirPlayPersistence.loadMfiTarget(context)
        val devId = DiPlayBootstrap.deviceId(identity)
        val cleanId = devId.replace(":", "")
        val locGranted = AirPlayPersistence.loadLocationReportingEnabled(context) &&
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return CarPlayRuntimeConfig(
            mfiTarget = mfiTarget,
            ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) listOf(UsbDeviceId(0x1a86, 0x5512)) else emptyList(),
            ch341MfiResetGpio = null,
            linuxI2cPath = if (mfiTarget == MfiTarget.I2C) AirPlayPersistence.loadMfiI2cPath(context).trim() else null,
            remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(context).trim().takeIf { it.isNotEmpty() },
            remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(context).takeIf { it.isNotEmpty() },
            identification = Iap2IdentificationConfig(
                name = "DiPlay",
                modelIdentifier = AirPlayPersistence.loadModel(context).trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL },
                manufacturer = AirPlayPersistence.loadManufacturer(context).trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER },
                serialNumber = "DIPLAY-$cleanId",
                firmwareVersion = "0.1.0",
                hardwareVersion = "1.0",
                carPlayUsbInterfaceNumber = 3,
                locationInformationEnabled = locGranted,
            ),
            label = "DiPlay",
            hostName = "diplay-${cleanId.lowercase()}",
            hostMac = devId.split(":").map { it.toInt(16).toByte() }.toByteArray(),
            wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(context),
            transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
            wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(context),
            wifiP2pPreferredChannel = AirPlayPersistence.loadWifiP2pPreferredChannel(context),
            manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(context),
            manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(context),
            manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(context),
            manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(context),
            manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(context),
            manualHotspotInterface = AirPlayPersistence.loadManualHotspotInterface(context),
            existingWifiSsid = AirPlayPersistence.loadExistingWifiSsid(context),
            existingWifiPassphrase = AirPlayPersistence.loadExistingWifiPassphrase(context),
            existingWifiInterface = AirPlayPersistence.loadExistingWifiInterface(context).trim().takeIf { it.isNotEmpty() },
            locationReportingEnabled = locGranted,
            availableCurrentMilliAmps = AirPlayPersistence.loadUsbChargingCurrent(context).milliAmps,
        )
    }

    private fun createAirPlayConfig(
        context: Context,
        identity: AirPlayIdentity,
        width: Int,
        height: Int,
    ): AirPlayConfig {
        val widthMm = AirPlayPersistence.loadWidthPhysicalMm(context)
        val basis = AirPlayPersistence.loadPhysicalSizeBasis(context)
        val physical = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = width,
            currentHeightPixels = height,
            maximumWidthPixels = width,
            maximumHeightPixels = height,
            referenceMillimeters = widthMm,
            basis = basis,
        )
        val fps = AirPlayPersistence.loadFps(context)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = width,
            heightPixels = height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
            primaryInputDevice = 1,
        )
        val scalePercent = AirPlayPersistence.loadDisplayScalePercent(context)
        val scaledDisplay = CarPlayDisplayScale.applyPercent(baseDisplay, scalePercent)
        val disableSafeArea = AirPlayPersistence.loadMainDisplayDisableSafeArea(context)
        val savedSafeArea = if (disableSafeArea) null else (
            AirPlayPersistence.loadSafeAreaRect(context, width, height)
                ?: if (SiQiRelayBridge.isVirtualScreenEnabled(context)) {
                    val safeTop = SiQiRelayBridge.getVirtualSafeTop(context).coerceIn(0, height / 2)
                    val safeBottom = SiQiRelayBridge.getVirtualSafeBottom(context).coerceIn(safeTop + 1, height)
                    SafeAreaRect(left = 0, top = safeTop, right = width, bottom = safeBottom)
                } else {
                    null
                }
            )
        val display = scaledDisplay.copy(
            safeArea = if (disableSafeArea) null else AirPlaySafeArea.toInsets(
                mapping = savedSafeArea,
                activityWidthPixels = width,
                activityHeightPixels = height,
                displayWidthPixels = scaledDisplay.widthPixels,
                displayHeightPixels = scaledDisplay.heightPixels,
            ),
            safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(context),
            mainDisplayCornerMasks = if (disableSafeArea) true else null,
            mainDisplayDisableSafeArea = disableSafeArea,
            useIphoneCornerMask = false,
        )
        val devId = DiPlayBootstrap.deviceId(identity)
        val disableAudio = AirPlayPersistence.loadCarBluetoothAudio(context)
        val micGranted = !disableAudio &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hevcUsable = HevcSupport.usable(
            requested = AirPlayPersistence.loadHevcEnabled(context),
            softwareChosen = AirPlayPersistence.loadHevcSoftwareDecoderEnabled(context),
            decoders = HevcSupport.decoders(),
        )
        return AirPlayConfig(
            deviceName = "DiPlay",
            deviceId = devId,
            btMac = DiPlayBluetooth.localAddress(context) ?: devId,
            sourceVersion = "950.7.1",
            main = display,
            rightHandDrive = AirPlayPersistence.loadRightHandDrive(context),
            hevc = hevcUsable,
            microphone = micGranted,
            microphoneOpus = com.shilapi.xcertplay.media.OpusEncoderSupport.isAvailable(),
            manufacturer = AirPlayPersistence.loadManufacturer(context).trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER },
            model = AirPlayPersistence.loadModel(context).trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL },
            oemLabel = AirPlayPersistence.loadOemLabel(context),
            icons = listOfNotNull(loadAirPlayIcon(context)),
            mainBufferedAudio = AirPlayPersistence.loadMainBufferedAudio(context),
            speechWithout8And16Khz = AirPlayPersistence.loadSpeechWithout8And16Khz(context),
            callAtCallRate = AirPlayPersistence.loadCallAtCallRate(context),
            declareAutomaticAppearance = AirPlayPersistence.loadDeclareAutomaticAppearance(context),
            disableAudioOutput = disableAudio,
            mediaSeekKeys = AirPlayPersistence.loadWheelLongPressSeek(context),
        )
    }

    private fun loadAirPlayIcon(context: Context): AirPlayIcon? {
        val custom = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(context)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (custom != null) {
            AirPlayIconSizing.fit(custom)?.let { (sz, bytes) -> return AirPlayIcon(sz, sz, bytes) }
        }
        val rawBytes = runCatching {
            context.resources.openRawResource(CarBrandBadge.fromKey(AirPlayPersistence.loadCarBrand(context)).iconRawRes)
                .use { it.readBytes() }
        }.getOrNull() ?: return null
        return AirPlayIconSizing.fit(rawBytes)?.let { (sz, bytes) -> AirPlayIcon(sz, sz, bytes) }
    }
}
