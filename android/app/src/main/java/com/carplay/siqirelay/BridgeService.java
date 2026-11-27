package com.carplay.siqirelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.carplay.siqirelay.carlife.CarLifeLocalReceiver;
import com.carplay.siqirelay.carplay.AutoConnectManager;
import com.carplay.siqirelay.carplay.CarPlayDongleManager;
import com.carplay.siqirelay.carplay.CarPlayVirtualPipeline;
import com.carplay.siqirelay.carplay.DiPlayBridge;
import com.carplay.siqirelay.carplay.DiPlayConfigOptimizer;
import com.carplay.siqirelay.carplay.ScreenStreamer;
import com.carplay.siqirelay.carplay.VirtualDisplayStreamer;
import com.carplay.siqirelay.input.HandlebarKeyBridge;
import com.carplay.siqirelay.kove.KoveBleScanner;
import com.carplay.siqirelay.kove.KoveClusterServer;
import com.shilapi.xcertplay.SiQiRelayBridge;

/**
 * 24/7 Core Gateway Foreground Service.
 * Manages KoveClusterServer, Scheme A VirtualDisplayStreamer (hardware AVC offscreen pipeline)
 * with ScreenStreamer fallback, WiFi low-latency lock, CarPlayDongleManager,
 * and HandlebarKeyBridge.
 */
public class BridgeService extends Service {
    private static final String TAG = "BridgeService";
    private static final String CHANNEL_ID = "carplay_siqi_bridge_channel";
    private static final int NOTIF_ID = 4501;

    public class LocalBinder extends Binder {
        public BridgeService getService() {
            return BridgeService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private static volatile BridgeService instance;

    public static BridgeService getInstance() {
        return instance;
    }

    private KoveClusterServer clusterServer;
    private KoveBleScanner bleScanner;
    private CarPlayDongleManager dongleManager;
    private CarLifeLocalReceiver carLifeReceiver;
    private DiPlayBridge diPlayBridge;
    private VirtualDisplayStreamer virtualDisplayStreamer;
    private ScreenStreamer screenStreamer;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback wifiNetworkCallback;

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public KoveClusterServer getClusterServer() {
        return clusterServer;
    }

    public CarPlayDongleManager getDongleManager() {
        return dongleManager;
    }

    public CarLifeLocalReceiver getCarLifeReceiver() {
        return carLifeReceiver;
    }

    public synchronized void setProjectionSourceMode(int mode) {
        DiPlayConfigOptimizer.setProjectionSourceMode(this, mode);
        if (mode == DiPlayConfigOptimizer.SOURCE_MODE_CARLIFE) {
            if (SiQiRelayBridge.hasSession()) {
                SiQiRelayBridge.stopCarPlaySession(this, null);
            }
            if (clusterServer != null && clusterServer.isAnyClusterActive() && carLifeReceiver != null) {
                carLifeReceiver.start();
            }
        } else {
            if (carLifeReceiver != null) {
                carLifeReceiver.stop();
            }
        }
        if (virtualDisplayStreamer != null) {
            virtualDisplayStreamer.notifySourceModeChanged();
        }
    }

    public synchronized void startCarLifeReceiver() {
        if (carLifeReceiver != null) {
            carLifeReceiver.start();
        }
        if (virtualDisplayStreamer != null) {
            virtualDisplayStreamer.notifySourceModeChanged();
        }
    }

    public synchronized void stopCarLifeReceiver() {
        if (carLifeReceiver != null) {
            carLifeReceiver.stop();
        }
        if (virtualDisplayStreamer != null) {
            virtualDisplayStreamer.notifySourceModeChanged();
        }
    }

    public CarPlayVirtualPipeline getVirtualPipeline() {
        return virtualDisplayStreamer != null ? virtualDisplayStreamer.getPipeline() : null;
    }

    public DiPlayBridge getDiPlayBridge() {
        return diPlayBridge;
    }

    public VirtualDisplayStreamer getVirtualDisplayStreamer() {
        return virtualDisplayStreamer;
    }

    public synchronized void setVirtualScreenMode(boolean enabled) {
        SiQiRelayBridge.setVirtualScreenEnabled(this, enabled);
        if (enabled) {
            if (screenStreamer != null) screenStreamer.stop();
            if (virtualDisplayStreamer != null) virtualDisplayStreamer.start();
            updateNotification("摩投 服务运行中");
        } else {
            if (virtualDisplayStreamer != null) virtualDisplayStreamer.stop();
            if (screenStreamer != null) screenStreamer.start();
            updateNotification("摩投 服务运行中");
        }
    }

    public void notifyBorderPresetUpdated() {
        if (virtualDisplayStreamer != null) {
            virtualDisplayStreamer.updateBorderLayout();
        }
    }

    public void startBleIfPermitted() {
        if (bleScanner != null) {
            bleScanner.start();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification(text));
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        startForegroundSafely();

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarPlaySiQiBridge:StreamingLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(24 * 60 * 60 * 1000L);
            }
        } catch (Exception ignored) {}

        // Acquire Low-Latency Wi-Fi Lock so MIUI/HyperOS does not drop Wi-Fi into DTIM power-save sleep
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int mode = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "CarPlaySiQiBridge:WifiLowLatencyLock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
                Log.i(TAG, "Acquired WifiLock (mode=" + mode + ") to eliminate wireless sleep jitter");
            }
        } catch (Exception e) {
            Log.w(TAG, "WifiLock notice: " + e.getMessage());
        }

        // 1. Start Kove Cluster Socket Servers (17818, 15457, 15456)
        clusterServer = new KoveClusterServer();
        clusterServer.start();

        AutoConnectManager autoConnect = AutoConnectManager.getInstance(this);
        autoConnect.setClusterServer(clusterServer);

        clusterServer.addListener(new KoveClusterServer.ClusterStateListener() {
            @Override
            public void onClusterConnected(String clientIp) {
                if (bleScanner != null) bleScanner.pauseScan();
                if (DiPlayConfigOptimizer.getProjectionSourceMode(BridgeService.this) == DiPlayConfigOptimizer.SOURCE_MODE_CARLIFE) {
                    if (carLifeReceiver != null && !carLifeReceiver.isRunning()) {
                        carLifeReceiver.start();
                    }
                } else {
                    autoConnect.onClusterConnected(clientIp);
                }
            }

            @Override
            public void onClusterDisconnected() {
                if (bleScanner != null && clusterServer != null && !clusterServer.isAnyClusterActive()) {
                    bleScanner.resumeScan();
                }
                autoConnect.onClusterDisconnected();
            }

            @Override
            public void onVideoStreamActive() {
                if (bleScanner != null) bleScanner.pauseScan();
                if (DiPlayConfigOptimizer.getProjectionSourceMode(BridgeService.this) == DiPlayConfigOptimizer.SOURCE_MODE_CARLIFE) {
                    if (carLifeReceiver != null && !carLifeReceiver.isRunning()) {
                        carLifeReceiver.start();
                    }
                } else {
                    autoConnect.onClusterConnected(null);
                }
            }

            @Override
            public void onVideoStreamClosed() {
                if (bleScanner != null && clusterServer != null && !clusterServer.isAnyClusterActive()) {
                    bleScanner.resumeScan();
                }
                autoConnect.onClusterDisconnected();
            }

            @Override
            public void onFrameStreamed(long frameCount, long bytesStreamed) {}
        });

        // Auto-apply DiPlay safe area, large-icon DPI, and low-latency H.264 settings once, then probe auto-connect
        new Thread(() -> {
            DiPlayConfigOptimizer.applyOptimizedConfig(BridgeService.this, false);
            autoConnect.onWifiNetworkAvailable();
        }, "DiPlayOptimizer").start();

        // Monitor Wi-Fi transitions so attaching to remembered cluster Wi-Fi triggers headless CarPlay
        registerWifiAutoConnectCallback(autoConnect);

        // 2. Start Kove BLE Scanner for auto-reconnect
        bleScanner = new KoveBleScanner(this);
        bleScanner.setListener(new KoveBleScanner.BleListener() {
            @Override
            public void onClusterBleConnected(String name, String mac) {
                autoConnect.onClusterBleConnected(name, mac);
            }

            @Override
            public void onClusterBleDisconnected() {}
        });
        bleScanner.start();

        // 3. Start CarPlay USB Dongle Host Manager (optional Route A compatibility) & local CarLife receiver
        dongleManager = new CarPlayDongleManager(this, clusterServer);
        dongleManager.start();
        carLifeReceiver = new CarLifeLocalReceiver(this);

        // 4. Hook Handlebar Key Bridge to Dongle & local CarLife
        HandlebarKeyBridge.getInstance().setDongleManager(dongleManager);
        HandlebarKeyBridge.getInstance().setCarLifeReceiver(carLifeReceiver);

        // 5. Lightweight DiPlay launcher helper (do not poll unused MapEmbedService every 3s)
        try {
            diPlayBridge = new DiPlayBridge(this, clusterServer, null);
        } catch (Exception e) {
            Log.w(TAG, "DiPlay bridge init notice: " + e.getMessage());
        }

        // 6. Start VirtualDisplay Offscreen AVC Pipeline (always enabled by default)
        try {
            SiQiRelayBridge.setVirtualScreenEnabled(this, true);
            virtualDisplayStreamer = new VirtualDisplayStreamer(this, clusterServer);
            virtualDisplayStreamer.setCarLifeReceiver(carLifeReceiver);
            screenStreamer = new ScreenStreamer(this, clusterServer);
            virtualDisplayStreamer.start();
            updateNotification("摩投 服务运行中");
        } catch (Exception e) {
            Log.w(TAG, "Video streamer init notice: " + e.getMessage());
        }

        Log.i(TAG, ">>> CarPlay SiQi Bridge Service fully operational.");
    }

    private void startForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                    startForeground(NOTIF_ID, buildNotification("摩投 服务运行中"), type);
                } else {
                    startForeground(NOTIF_ID, buildNotification("摩投 服务运行中"),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                }
            } else {
                startForeground(NOTIF_ID, buildNotification("摩投 服务运行中"));
            }
        } catch (Exception e) {
            try { startForeground(NOTIF_ID, buildNotification("摩投 服务运行中")); } catch (Exception ignored) {}
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                    CHANNEL_ID, "摩投 服务", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(chan);
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);

        return b.setContentTitle("摩投")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void registerWifiAutoConnectCallback(AutoConnectManager autoConnect) {
        try {
            connectivityManager = getSystemService(ConnectivityManager.class);
            if (connectivityManager == null) return;
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
                    : 0;
            wifiNetworkCallback = new ConnectivityManager.NetworkCallback(flags) {
                @Override
                public void onAvailable(Network network) {
                    autoConnect.onWifiNetworkAvailable();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                    autoConnect.onWifiNetworkAvailable();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                    autoConnect.onWifiNetworkAvailable();
                }

                @Override
                public void onLost(Network network) {
                    autoConnect.onWifiNetworkLost();
                }
            };
            connectivityManager.registerNetworkCallback(request, wifiNetworkCallback);
        } catch (Exception e) {
            Log.w(TAG, "Wi-Fi callback registration notice: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (connectivityManager != null && wifiNetworkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(wifiNetworkCallback); } catch (Exception ignored) {}
            wifiNetworkCallback = null;
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (diPlayBridge != null) diPlayBridge.stop();
        if (carLifeReceiver != null) carLifeReceiver.stop();
        if (virtualDisplayStreamer != null) virtualDisplayStreamer.stop();
        if (screenStreamer != null) screenStreamer.stop();
        if (dongleManager != null) dongleManager.stop();
        if (bleScanner != null) bleScanner.stop();
        if (clusterServer != null) clusterServer.stop();
    }
}
