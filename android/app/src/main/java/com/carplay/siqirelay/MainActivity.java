package com.carplay.siqirelay;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.carplay.siqirelay.carplay.AutoConnectManager;
import com.carplay.siqirelay.carplay.DiPlayConfigOptimizer;
import com.carplay.siqirelay.input.BluetoothControllerHelper;
import com.carplay.siqirelay.input.HandlebarKeyBridge;
import com.carplay.siqirelay.kove.KoveClusterServer;
import com.carplay.siqirelay.ui.DiPlayStyle;
import com.shilapi.xcertplay.SiQiRelayBridge;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Minimalist main entry for 思骑CarPlay aligned with DiPlay's visual language.
 * Keeps only the 3 rider workflows on home: Cluster connection, iPhone CarPlay,
 * and Bluetooth handlebar key controller. All tuning & utilities live in [SettingsActivity].
 */
public class MainActivity extends Activity {
    private static final int REQ_RUNTIME_PERMS = 1001;

    private TextView tvHeaderStatus;
    private TextView tvClusterConn;
    private TextView tvClusterDesc;
    private TextView tvVideoStreamStatus;
    private TextView tvStreamStats;

    private TextView tvIphoneSession;
    private TextView tvAutoConnectStatus;
    private TextView tvAudioRouteChip;

    private TextView tvControllerName;
    private TextView tvControllerBadge;
    private TextView tvKeyLog;

    private Button btnOpenSettings;
    private Button btnStartVirtualCarplay;
    private Button btnOpenDiplaySetup;
    private Button btnEnterCarplay;
    private Button btnPairController;
    private Button btnEnableGlobalKeys;

    private KoveClusterServer.ClusterStateListener uiClusterListener;
    private HandlebarKeyBridge.OnKeyEventListener handlebarKeyListener;
    private InputManager inputManager;
    private DiPlayStyle.Palette palette;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private volatile boolean userInteracted = false;
    private volatile long activityOpenedElapsedMs = 0L;
    private Runnable pendingAutoMinimize;

    private final InputManager.InputDeviceListener inputDeviceListener = new InputManager.InputDeviceListener() {
        @Override
        public void onInputDeviceAdded(int deviceId) {
            runOnUiThread(() -> updateControllerUi());
        }

        @Override
        public void onInputDeviceRemoved(int deviceId) {
            runOnUiThread(() -> updateControllerUi());
        }

        @Override
        public void onInputDeviceChanged(int deviceId) {
            runOnUiThread(() -> updateControllerUi());
        }
    };

    private final AutoConnectManager.AutoConnectUiListener autoConnectUiListener =
            new AutoConnectManager.AutoConnectUiListener() {
                @Override
                public void onAutoConnectTriggered(boolean fromAppLaunch) {
                    updateIphoneUi();
                    boolean openedRecently = (SystemClock.elapsedRealtime() - activityOpenedElapsedMs) < 15_000L;
                    if ((fromAppLaunch || openedRecently)
                            && !userInteracted
                            && !isFinishing()
                            && AutoConnectManager.getInstance(MainActivity.this).isAutoBackgroundEnabled()
                            && SiQiRelayBridge.isVirtualScreenEnabled(MainActivity.this)) {
                        Toast.makeText(
                                MainActivity.this,
                                "已触发自动连接，CarPlay 后台推流中",
                                Toast.LENGTH_SHORT
                        ).show();
                        scheduleAutoMinimize();
                    }
                }

                @Override
                public void onAutoConnectStateChanged() {
                    updateIphoneUi();
                }
            };

    private final SiQiRelayBridge.VirtualRelayListener virtualRelayListener = new SiQiRelayBridge.VirtualRelayListener() {
        @Override
        public void onSessionStageChanged(String stage, boolean active) {
            runOnUiThread(() -> updateIphoneUi());
        }

        @Override
        public void onMainVideoStreamChanged(boolean active) {
            runOnUiThread(() -> updateIphoneUi());
        }
    };

    private BridgeService bridgeService;
    private boolean isBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            bridgeService = ((BridgeService.LocalBinder) binder).getService();
            isBound = true;
            setupServiceListeners();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bridgeService = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Never wake or light up the physical screen when launched in background/lockscreen;
        // BridgeService handles Step-1 cluster attach and Step-2 headless CarPlay completely offscreen.
        if (!SiQiRelayBridge.isVirtualScreenEnabled(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        setContentView(R.layout.activity_main);
        palette = DiPlayStyle.palette(this);
        DiPlayStyle.applyWindowChrome(this, findViewById(R.id.root_scroll));
        activityOpenedElapsedMs = SystemClock.elapsedRealtime();

        initViews();
        applyDiPlayTheme();
        bindActions();
        requestRequiredRuntimePermissions();

        inputManager = getSystemService(InputManager.class);
        AutoConnectManager.getInstance(this).setUiListener(autoConnectUiListener);

        Intent sIntent = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(sIntent);
        } else {
            startService(sIntent);
        }
        bindService(sIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        userInteracted = false;
        activityOpenedElapsedMs = SystemClock.elapsedRealtime();
        cancelAutoMinimize();
        if (bridgeService != null) {
            AutoConnectManager.getInstance(this).evaluateOnAppLaunch();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        palette = DiPlayStyle.palette(this);
        DiPlayStyle.applyWindowChrome(this, findViewById(R.id.root_scroll));
        applyDiPlayTheme();
        refreshAllUi();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        userInteracted = true;
        cancelAutoMinimize();
    }

    @Override
    protected void onResume() {
        super.onResume();
        SiQiRelayBridge.addVirtualRelayListener(virtualRelayListener);
        AutoConnectManager.getInstance(this).setUiListener(autoConnectUiListener);
        if (inputManager != null) {
            inputManager.registerInputDeviceListener(inputDeviceListener, uiHandler);
        }
        refreshAllUi();
        if (bridgeService != null) {
            AutoConnectManager.getInstance(this).evaluateOnAppLaunch();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        SiQiRelayBridge.removeVirtualRelayListener(virtualRelayListener);
        if (inputManager != null) {
            inputManager.unregisterInputDeviceListener(inputDeviceListener);
        }
    }

    private void initViews() {
        tvHeaderStatus = findViewById(R.id.tv_header_status);
        btnOpenSettings = findViewById(R.id.btn_open_settings);

        tvClusterConn = findViewById(R.id.tv_cluster_conn);
        tvClusterDesc = findViewById(R.id.tv_cluster_desc);
        tvVideoStreamStatus = findViewById(R.id.tv_video_stream_status);
        tvStreamStats = findViewById(R.id.tv_stream_stats);

        tvIphoneSession = findViewById(R.id.tv_iphone_session);
        tvAutoConnectStatus = findViewById(R.id.tv_auto_connect_status);
        tvAudioRouteChip = findViewById(R.id.tv_audio_route_chip);
        btnStartVirtualCarplay = findViewById(R.id.btn_start_virtual_carplay);
        btnOpenDiplaySetup = findViewById(R.id.btn_open_diplay_setup);
        btnEnterCarplay = findViewById(R.id.btn_enter_carplay);

        tvControllerName = findViewById(R.id.tv_controller_name);
        tvControllerBadge = findViewById(R.id.tv_controller_badge);
        tvKeyLog = findViewById(R.id.tv_key_log);
        btnPairController = findViewById(R.id.btn_pair_controller);
        btnEnableGlobalKeys = findViewById(R.id.btn_enable_global_keys);
    }

    private void applyDiPlayTheme() {
        DiPlayStyle.styleCard(findViewById(R.id.card_cluster), palette);
        DiPlayStyle.styleCard(findViewById(R.id.card_iphone), palette);
        DiPlayStyle.styleCard(findViewById(R.id.card_controller), palette);

        setColor(R.id.tv_app_title, palette.textPrimary);
        setColor(R.id.tv_app_subtitle, palette.textSecondary);
        setColor(R.id.tv_kicker_cluster, palette.accent);
        setColor(R.id.tv_kicker_iphone, palette.accent);
        setColor(R.id.tv_kicker_controller, palette.accent);
        setColor(R.id.tv_cluster_desc, palette.textSecondary);
        setColor(R.id.tv_stream_stats, palette.textSecondary);
        setColor(R.id.tv_auto_connect_status, palette.textSecondary);
        setColor(R.id.tv_footer_note, palette.textSecondary);

        DiPlayStyle.styleSecondaryButton(btnOpenSettings, palette);
        DiPlayStyle.stylePrimaryButton(btnStartVirtualCarplay, palette);
        DiPlayStyle.styleSecondaryButton(btnOpenDiplaySetup, palette);
        DiPlayStyle.styleSecondaryButton(btnEnterCarplay, palette);
        DiPlayStyle.styleSecondaryButton(btnPairController, palette);
        DiPlayStyle.styleSecondaryButton(btnEnableGlobalKeys, palette);

        DiPlayStyle.styleChip(tvHeaderStatus, palette, palette.success);
    }

    private void setColor(int id, int color) {
        TextView tv = findViewById(id);
        if (tv != null) tv.setTextColor(color);
    }

    private void bindActions() {
        btnOpenSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        tvAudioRouteChip.setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
        });

        btnStartVirtualCarplay.setOnClickListener(v -> {
            AutoConnectManager.getInstance(this).markSessionManual();
            if (SiQiRelayBridge.hasSession()) {
                SiQiRelayBridge.stopCarPlaySession(this, () -> runOnUiThread(this::updateIphoneUi));
                Toast.makeText(this, "正在断开 CarPlay...", Toast.LENGTH_SHORT).show();
            } else {
                btnStartVirtualCarplay.setEnabled(false);
                btnStartVirtualCarplay.setText("正在启动无线 CarPlay...");
                new Thread(() -> {
                    DiPlayConfigOptimizer.applyOptimizedConfig(MainActivity.this, false);
                    boolean started = SiQiRelayBridge.startHeadlessCarPlay(MainActivity.this);
                    runOnUiThread(() -> {
                        btnStartVirtualCarplay.setEnabled(true);
                        updateIphoneUi();
                        if (!started) {
                            Toast.makeText(
                                    MainActivity.this,
                                    "未启动：请检查权限或先配对 iPhone",
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    });
                }, "StartHeadlessCarPlay").start();
            }
        });

        btnOpenDiplaySetup.setOnClickListener(v -> new Thread(() -> {
            DiPlayConfigOptimizer.applyOptimizedConfig(MainActivity.this, false);
            runOnUiThread(() -> {
                try {
                    SiQiRelayBridge.launchDiPlayHome(MainActivity.this, "home");
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开 DiPlay 向导: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }, "DiPlaySetupLaunch").start());

        btnEnterCarplay.setOnClickListener(v -> {
            AutoConnectManager.getInstance(this).markSessionManual();
            new Thread(() -> {
                DiPlayConfigOptimizer.applyOptimizedConfig(MainActivity.this, false);
                runOnUiThread(() -> {
                    if (bridgeService != null && bridgeService.getDiPlayBridge() != null) {
                        bridgeService.getDiPlayBridge().launchCarPlayHost();
                    } else {
                        try {
                            SiQiRelayBridge.launchCarPlayHost(MainActivity.this);
                        } catch (Exception ignored) {}
                    }
                });
            }, "LaunchCarPlayHost").start();
        });

        btnPairController.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "无法打开系统蓝牙设置", Toast.LENGTH_SHORT).show();
            }
        });

        btnEnableGlobalKeys.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(this, "请在无障碍服务中开启「方向盘/蓝牙按键」", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show();
            }
        });

        handlebarKeyListener = (action, keyCode, isLongPress) -> runOnUiThread(() -> {
            if (tvKeyLog != null) {
                tvKeyLog.setText("最近按键: " + action.title + (isLongPress ? " · 呼出 Siri" : ""));
                tvKeyLog.setTextColor(palette.accent);
            }
        });
        HandlebarKeyBridge.getInstance().addListener(handlebarKeyListener);
    }

    private void refreshAllUi() {
        updateClusterUi();
        updateIphoneUi();
        updateControllerUi();
    }

    private void updateClusterUi() {
        KoveClusterServer srv = bridgeService != null ? bridgeService.getClusterServer() : null;
        boolean ctrlConnected = srv != null && srv.isClusterControlConnected();
        boolean videoActive = srv != null && srv.isVideoStreamingActive();

        if (ctrlConnected || videoActive) {
            tvClusterConn.setText("车机仪表已连接");
            tvClusterConn.setTextColor(palette.success);
        } else {
            tvClusterConn.setText("等待车机仪表连接");
            tvClusterConn.setTextColor(palette.textPrimary);
        }

        if (videoActive) {
            tvVideoStreamStatus.setText("投屏中");
            DiPlayStyle.styleChip(tvVideoStreamStatus, palette, palette.success);
            tvClusterDesc.setText("已建立无线投屏通道，仪表画面实时同步中");
        } else if (ctrlConnected) {
            tvVideoStreamStatus.setText("已连接");
            DiPlayStyle.styleChip(tvVideoStreamStatus, palette, palette.accent);
            tvClusterDesc.setText("已与车机建立互联，可在仪表端打开全屏投屏");
        } else {
            tvVideoStreamStatus.setText("待机");
            DiPlayStyle.styleChip(tvVideoStreamStatus, palette, palette.textSecondary);
            boolean wifiReady = findWifiIpv4Address() != null;
            tvClusterDesc.setText(wifiReady
                    ? "手机 Wi-Fi 已就绪，请在车机仪表打开投屏互联"
                    : "请先开启车辆电源并将手机连入车机 Wi-Fi");
        }
    }

    private void updateIphoneUi() {
        boolean headsetAudio = DiPlayConfigOptimizer.isHeadsetAudioDefault(this);
        tvAudioRouteChip.setText(headsetAudio ? "音频: 蓝牙耳机" : "音频: 手机播放");
        DiPlayStyle.styleChip(tvAudioRouteChip, palette, headsetAudio ? palette.accent : palette.warning);

        boolean hasSess = SiQiRelayBridge.hasSession();
        boolean active = SiQiRelayBridge.isSessionActive();
        boolean mainVideo = SiQiRelayBridge.isMainVideoActive();
        String stage = SiQiRelayBridge.getSessionStage();
        String hint = SiQiRelayBridge.getLastDiagnosticHint();

        if (hasSess) {
            btnStartVirtualCarplay.setText("断开无线 CarPlay");
            DiPlayStyle.styleDangerButton(btnStartVirtualCarplay, palette);
        } else {
            btnStartVirtualCarplay.setText("启动无线 CarPlay");
            DiPlayStyle.stylePrimaryButton(btnStartVirtualCarplay, palette);
        }

        if (mainVideo) {
            tvIphoneSession.setText("CarPlay 正在向仪表投屏");
            tvIphoneSession.setTextColor(palette.success);
        } else if (active) {
            tvIphoneSession.setText("已连接 iPhone CarPlay");
            tvIphoneSession.setTextColor(palette.success);
        } else if (hasSess) {
            tvIphoneSession.setText(stage != null ? stage : "正在等待 iPhone 握手...");
            tvIphoneSession.setTextColor(palette.warning);
        } else if (hint != null && !hint.isEmpty()) {
            tvIphoneSession.setText("等待 iPhone · " + hint);
            tvIphoneSession.setTextColor(palette.warning);
        } else {
            String label = SiQiRelayBridge.getPairedIphoneLabel(this);
            tvIphoneSession.setText(label != null ? "已配对: " + label : "未配对 iPhone");
            tvIphoneSession.setTextColor(palette.textPrimary);
        }

        tvAutoConnectStatus.setText(AutoConnectManager.getInstance(this).getStatusSummary());
        tvAutoConnectStatus.setTextColor(palette.textSecondary);
    }

    private void updateControllerUi() {
        BluetoothControllerHelper.Snapshot snap = BluetoothControllerHelper.inspect(this);
        if (snap.connected) {
            tvControllerName.setText(snap.controllerName != null ? snap.controllerName : "蓝牙按键控制器");
            tvControllerName.setTextColor(palette.success);
            tvControllerBadge.setText(snap.globalKeyServiceActive ? "全局监听中" : "前台可用");
            DiPlayStyle.styleChip(tvControllerBadge, palette, palette.success);
        } else if (snap.pairedCandidateCount > 0) {
            tvControllerName.setText("已配对蓝牙外设 (待按下唤醒)");
            tvControllerName.setTextColor(palette.textPrimary);
            tvControllerBadge.setText("待唤醒");
            DiPlayStyle.styleChip(tvControllerBadge, palette, palette.accent);
        } else {
            tvControllerName.setText("未检测到蓝牙按键控制器");
            tvControllerName.setTextColor(palette.textPrimary);
            tvControllerBadge.setText("未连接");
            DiPlayStyle.styleChip(tvControllerBadge, palette, palette.textSecondary);
        }

        if (snap.globalKeyServiceActive) {
            btnEnableGlobalKeys.setVisibility(View.GONE);
        } else {
            btnEnableGlobalKeys.setVisibility(View.VISIBLE);
        }
    }

    private void setupServiceListeners() {
        if (bridgeService == null) return;
        if (tvHeaderStatus != null) {
            tvHeaderStatus.setText("服务在线");
            DiPlayStyle.styleChip(tvHeaderStatus, palette, palette.success);
        }

        KoveClusterServer server = bridgeService.getClusterServer();
        if (server != null) {
            if (uiClusterListener != null) {
                server.removeListener(uiClusterListener);
            }
            uiClusterListener = new KoveClusterServer.ClusterStateListener() {
                @Override
                public void onClusterConnected(String clientIp) {
                    runOnUiThread(() -> updateClusterUi());
                }

                @Override
                public void onClusterDisconnected() {
                    runOnUiThread(() -> updateClusterUi());
                }

                @Override
                public void onVideoStreamActive() {
                    runOnUiThread(() -> updateClusterUi());
                }

                @Override
                public void onVideoStreamClosed() {
                    runOnUiThread(() -> updateClusterUi());
                }

                @Override
                public void onFrameStreamed(long frameCount, long bytesStreamed) {}
            };
            server.addListener(uiClusterListener);
        }

        updateClusterUi();
        AutoConnectManager.getInstance(this).evaluateOnAppLaunch();
    }

    private void scheduleAutoMinimize() {
        cancelAutoMinimize();
        if (SiQiRelayBridge.getMissingWirelessPermissions(this).length > 0) {
            return;
        }
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) {
            return;
        }
        pendingAutoMinimize = () -> {
            if (isFinishing() || userInteracted || !SiQiRelayBridge.hasSession()) {
                return;
            }
            KeyguardManager curKm = getSystemService(KeyguardManager.class);
            if (curKm != null && curKm.isKeyguardLocked()) {
                return;
            }
            if (SiQiRelayBridge.getMissingWirelessPermissions(this).length == 0) {
                moveTaskToBack(true);
            }
        };
        uiHandler.postDelayed(pendingAutoMinimize, 6000L);
    }

    private void cancelAutoMinimize() {
        if (pendingAutoMinimize != null) {
            uiHandler.removeCallbacks(pendingAutoMinimize);
            pendingAutoMinimize = null;
        }
    }

    private void requestRequiredRuntimePermissions() {
        List<String> missing = new ArrayList<>();
        String[] candidates;
        if (Build.VERSION.SDK_INT >= 33) {
            candidates = new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        } else if (Build.VERSION.SDK_INT >= 31) {
            candidates = new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else {
            candidates = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
        for (String perm : candidates) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_RUNTIME_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RUNTIME_PERMS) {
            if (bridgeService != null) {
                bridgeService.startBleIfPermitted();
                AutoConnectManager.getInstance(this).evaluateOnAppLaunch();
            }
            refreshAllUi();
        }
    }

    private String findWifiIpv4Address() {
        String secondaryCandidate = null;
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName();
                if (name == null) continue;
                boolean primary = name.startsWith("wlan") || name.startsWith("ap");
                boolean secondary = name.startsWith("swlan") || name.startsWith("p2p");
                if (!primary && !secondary) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String host = addr.getHostAddress();
                        if (primary) return host;
                        if (secondaryCandidate == null) secondaryCandidate = host;
                    }
                }
            }
        } catch (Exception ignored) {}
        return secondaryCandidate;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (HandlebarKeyBridge.getInstance().onKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelAutoMinimize();
        AutoConnectManager.getInstance(this).setUiListener(null);
        if (handlebarKeyListener != null) {
            HandlebarKeyBridge.getInstance().removeListener(handlebarKeyListener);
            handlebarKeyListener = null;
        }
        if (bridgeService != null && bridgeService.getClusterServer() != null && uiClusterListener != null) {
            bridgeService.getClusterServer().removeListener(uiClusterListener);
        }
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}
