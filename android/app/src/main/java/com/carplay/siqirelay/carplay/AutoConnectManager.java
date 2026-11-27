package com.carplay.siqirelay.carplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.carplay.siqirelay.kove.KoveClusterServer;
import com.shilapi.xcertplay.SiQiRelayBridge;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orchestrates hands-free, screen-off CarPlay auto-connect driven strictly by Step 1 (cluster link).
 *
 * Triggers headless Scheme A wireless CarPlay ONLY after Step 1 (KoveClusterServer TCP 17818 control
 * or 15456 video stream) succeeds, allowing the Android phone to remain completely screen-off in a
 * pocket while BridgeService accepts the bike cluster and launches CarPlay in the background.
 *
 * Also schedules a debounced auto-disconnect after the bike powers off (both 17818 and 15456 remain
 * down past the grace period) if the session was started automatically.
 */
public class AutoConnectManager {
    private static final String TAG = "AutoConnectManager";
    private static final String PREFS = "carplay_auto_connect_prefs";
    private static final String KEY_AUTO_CONNECT_ENABLED = "auto_connect_enabled";
    private static final String KEY_AUTO_BACKGROUND_ON_LAUNCH = "auto_background_on_launch";
    private static final String KEY_CLUSTER_EVER_CONNECTED = "cluster_ever_connected";
    private static final String KEY_CLUSTER_WIFI_SSID = "cluster_wifi_ssid";
    private static final String KEY_CLUSTER_SUBNET_PREFIX = "cluster_subnet_prefix";
    private static final String KEY_CLUSTER_GATEWAY_IP = "cluster_gateway_ip";
    private static final String KEY_CLUSTER_BLE_MAC = "cluster_ble_mac";
    private static final String KEY_CLUSTER_BLE_NAME = "cluster_ble_name";

    private static final long START_COOLDOWN_MS = 8_000L;
    private static final long RETRY_START_DELAY_MS = 4_000L;
    private static final int MAX_START_RETRIES = 6;
    private static final long CLUSTER_OFF_DISCONNECT_DELAY_MS = 18_000L;

    public interface AutoConnectUiListener {
        void onAutoConnectTriggered(boolean fromAppLaunch);
        void onAutoConnectStateChanged();
    }

    private static volatile AutoConnectManager instance;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AutoConnectWorker");
        t.setDaemon(true);
        return t;
    });

    private volatile AutoConnectUiListener uiListener;
    private volatile KoveClusterServer clusterServer;
    private volatile long lastStartAttemptMs = 0L;
    private volatile int startRetryCount = 0;
    private volatile boolean sessionStartedAutomatically = false;
    private Runnable pendingLeaveDisconnect;
    private Runnable pendingStartRetry;

    private AutoConnectManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static AutoConnectManager getInstance(Context context) {
        if (instance == null) {
            synchronized (AutoConnectManager.class) {
                if (instance == null) {
                    instance = new AutoConnectManager(context);
                }
            }
        }
        return instance;
    }

    public void setClusterServer(KoveClusterServer server) {
        this.clusterServer = server;
    }

    public void setUiListener(AutoConnectUiListener listener) {
        this.uiListener = listener;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isAutoConnectEnabled() {
        return prefs().getBoolean(KEY_AUTO_CONNECT_ENABLED, true);
    }

    public void setAutoConnectEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_AUTO_CONNECT_ENABLED, enabled).apply();
        if (!enabled) {
            cancelPendingDisconnect();
        }
        notifyUiStateChanged();
    }

    public boolean isAutoBackgroundEnabled() {
        return prefs().getBoolean(KEY_AUTO_BACKGROUND_ON_LAUNCH, true);
    }

    public void setAutoBackgroundEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_AUTO_BACKGROUND_ON_LAUNCH, enabled).apply();
    }

    public boolean hasPairedCluster() {
        return prefs().getBoolean(KEY_CLUSTER_EVER_CONNECTED, false);
    }

    public boolean hasPairedIphone() {
        return SiQiRelayBridge.hasPairedIphone(context);
    }

    public String getSavedClusterSsid() {
        return prefs().getString(KEY_CLUSTER_WIFI_SSID, null);
    }

    public String getSavedClusterSubnet() {
        return prefs().getString(KEY_CLUSTER_SUBNET_PREFIX, null);
    }

    public boolean isReadyForAutoConnect() {
        return isAutoConnectEnabled() && hasPairedIphone();
    }

    public void markSessionManual() {
        sessionStartedAutomatically = false;
        cancelPendingDisconnect();
    }

    public String getStatusSummary() {
        if (!isAutoConnectEnabled()) {
            return "自动连接已关闭（可在设置中开启）";
        }
        boolean iphoneOk = hasPairedIphone();
        String iphoneLabel = SiQiRelayBridge.getPairedIphoneLabel(context);
        String hint = SiQiRelayBridge.getLastDiagnosticHint();

        if (SiQiRelayBridge.hasSession()) {
            return "自动连接运行中: " + SiQiRelayBridge.getSessionStage();
        }
        if (iphoneOk) {
            KoveClusterServer srv = clusterServer;
            boolean clusterActive = srv != null && srv.isAnyClusterActive();
            String base = clusterActive
                    ? String.format(Locale.getDefault(),
                    "车机已连接 · 正在后台启动 %s 无线 CarPlay",
                    iphoneLabel != null ? iphoneLabel : "iPhone")
                    : String.format(Locale.getDefault(),
                    "已配对 %s · 第 1 步车机连接成功后后台自动启动（无需亮屏）",
                    iphoneLabel != null ? iphoneLabel : "iPhone");
            return (hint != null && !hint.isEmpty()) ? base + "\n提示: " + hint : base;
        } else {
            return "请先点击下方「配对 / 选择 iPhone」，此后第 1 步连接车机后将自动后台启动 CarPlay";
        }
    }

    /**
     * Records a successful Step-1 cluster TCP/video connection and immediately starts
     * headless wireless CarPlay in the background without waking the phone display.
     */
    public void onClusterConnected(String clientIp) {
        cancelPendingDisconnect();
        WifiSnapshot wifi = captureCurrentWifiSnapshot();
        String clientPrefix = extractIpv4Prefix(clientIp);
        String chosenPrefix = (wifi.subnetPrefix != null) ? wifi.subnetPrefix : clientPrefix;

        SharedPreferences.Editor ed = prefs().edit().putBoolean(KEY_CLUSTER_EVER_CONNECTED, true);
        if (wifi.ssid != null) {
            ed.putString(KEY_CLUSTER_WIFI_SSID, wifi.ssid);
        }
        if (chosenPrefix != null) {
            ed.putString(KEY_CLUSTER_SUBNET_PREFIX, chosenPrefix);
        }
        if (wifi.gatewayIp != null) {
            ed.putString(KEY_CLUSTER_GATEWAY_IP, wifi.gatewayIp);
        }
        ed.apply();

        Log.i(TAG, "Step-1 cluster connected (ip=" + clientIp
                + ", ssid=" + wifi.ssid + ", subnet=" + chosenPrefix + ")");
        notifyUiStateChanged();

        if (DiPlayConfigOptimizer.getProjectionSourceMode(context) == DiPlayConfigOptimizer.SOURCE_MODE_CARLIFE) {
            Log.d(TAG, "Skipping wireless CarPlay auto-connect because Local CarLife mode is active");
            return;
        }
        if (isAutoConnectEnabled() && hasPairedIphone()) {
            startRetryCount = 0;
            triggerAutoStart("cluster_connected", false);
        }
    }

    public void onClusterBleConnected(String name, String mac) {
        if (mac == null || mac.isEmpty()) return;
        prefs().edit()
                .putString(KEY_CLUSTER_BLE_MAC, mac)
                .putString(KEY_CLUSTER_BLE_NAME, name != null ? name : "")
                .apply();
        Log.d(TAG, "Cluster BLE seen (" + name + " / " + mac + "); waiting for Step-1 TCP cluster connection before starting CarPlay");
    }

    public void onClusterDisconnected() {
        scheduleAutoDisconnectIfLeftBike("cluster_disconnected");
    }

    public void onWifiNetworkAvailable() {
        KoveClusterServer srv = clusterServer;
        if (srv != null && srv.isAnyClusterActive()) {
            cancelPendingDisconnect();
        }
        // Do not start wireless CarPlay on Wi-Fi attach alone; wait for Step-1 cluster TCP connection
        // so the phone can stay completely screen-off and never interferes with cluster handshake.
    }

    public void onWifiNetworkLost() {
        scheduleAutoDisconnectIfLeftBike("wifi_lost");
    }

    /**
     * Called when MainActivity starts/resumes/receives permissions.
     * Triggers auto-start ONLY if Step-1 cluster link is already active.
     */
    public void evaluateOnAppLaunch() {
        notifyUiStateChanged();
        if (DiPlayConfigOptimizer.getProjectionSourceMode(context) == DiPlayConfigOptimizer.SOURCE_MODE_CARLIFE) {
            return;
        }
        if (!isAutoConnectEnabled() || !hasPairedIphone()) {
            Log.d(TAG, "Auto-connect waiting (enabled=" + isAutoConnectEnabled()
                    + ", iphone=" + hasPairedIphone() + ")");
            return;
        }
        if (SiQiRelayBridge.hasSession()) {
            return;
        }
        KoveClusterServer srv = clusterServer;
        if (srv != null && srv.isAnyClusterActive()) {
            triggerAutoStart("app_launch_cluster_active", true);
        } else {
            SiQiRelayBridge.appendDiagLog(context, TAG,
                    "Waiting for Step-1 cluster TCP connection before auto-starting wireless CarPlay");
        }
    }

    private void triggerAutoStart(String reason, boolean fromAppLaunch) {
        if (!SiQiRelayBridge.isVirtualScreenEnabled(context)) {
            Log.d(TAG, "Skipping headless auto-start: Scheme A virtual screen is disabled");
            return;
        }
        if (SiQiRelayBridge.hasSession()) {
            cancelPendingStartRetry();
            startRetryCount = 0;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (this) {
            if (now - lastStartAttemptMs < START_COOLDOWN_MS) {
                return;
            }
            lastStartAttemptMs = now;
        }
        cancelPendingStartRetry();

        worker.execute(() -> {
            if (SiQiRelayBridge.hasSession()) return;
            DiPlayConfigOptimizer.applyOptimizedConfig(context, false, false);
            boolean started = SiQiRelayBridge.startHeadlessCarPlay(context);
            SiQiRelayBridge.appendDiagLog(context, TAG,
                    "Auto-start headless CarPlay reason=" + reason + " started=" + started
                            + (started ? "" : " err=" + SiQiRelayBridge.getLastDiagnosticHint()));
            if (started) {
                startRetryCount = 0;
                sessionStartedAutomatically = true;
                AutoConnectUiListener l = uiListener;
                if (l != null) {
                    mainHandler.post(() -> {
                        AutoConnectUiListener latest = uiListener;
                        if (latest != null) latest.onAutoConnectTriggered(fromAppLaunch);
                    });
                }
            } else {
                // Allow prompt retry once permissions/BT settle instead of stalling for full cooldown.
                lastStartAttemptMs = 0L;
                notifyUiStateChanged();
                scheduleRetryIfStillNearBike(reason);
            }
        });
    }

    private synchronized void scheduleRetryIfStillNearBike(String reason) {
        if (!isAutoConnectEnabled() || !hasPairedIphone() || SiQiRelayBridge.hasSession()) return;
        if (startRetryCount >= MAX_START_RETRIES) return;
        if (pendingStartRetry != null) return;
        startRetryCount++;
        final int attempt = startRetryCount;
        pendingStartRetry = () -> {
            pendingStartRetry = null;
            if (!isAutoConnectEnabled() || !hasPairedIphone() || SiQiRelayBridge.hasSession()) return;
            KoveClusterServer srv = clusterServer;
            if (srv != null && srv.isAnyClusterActive()) {
                triggerAutoStart("retry#" + attempt + "_" + reason, false);
            }
        };
        mainHandler.postDelayed(pendingStartRetry, RETRY_START_DELAY_MS);
    }

    private synchronized void cancelPendingStartRetry() {
        if (pendingStartRetry != null) {
            mainHandler.removeCallbacks(pendingStartRetry);
            pendingStartRetry = null;
        }
    }

    private synchronized void scheduleAutoDisconnectIfLeftBike(String reason) {
        if (!sessionStartedAutomatically || !isAutoConnectEnabled() || !SiQiRelayBridge.hasSession()) {
            return;
        }
        cancelPendingDisconnect();
        pendingLeaveDisconnect = () -> {
            KoveClusterServer srv = clusterServer;
            if (srv != null && srv.isAnyClusterActive()) {
                return;
            }
            if (sessionStartedAutomatically && SiQiRelayBridge.hasSession()) {
                Log.i(TAG, "Step-1 cluster disconnected (" + reason + ") -> stopping auto-started CarPlay cleanly");
                sessionStartedAutomatically = false;
                SiQiRelayBridge.stopCarPlaySession(context, null);
            }
        };
        mainHandler.postDelayed(pendingLeaveDisconnect, CLUSTER_OFF_DISCONNECT_DELAY_MS);
    }

    private synchronized void cancelPendingDisconnect() {
        if (pendingLeaveDisconnect != null) {
            mainHandler.removeCallbacks(pendingLeaveDisconnect);
            pendingLeaveDisconnect = null;
        }
    }

    private void notifyUiStateChanged() {
        AutoConnectUiListener l = uiListener;
        if (l != null) {
            mainHandler.post(() -> {
                AutoConnectUiListener latest = uiListener;
                if (latest != null) latest.onAutoConnectStateChanged();
            });
        }
    }

    /**
     * Checks whether the phone is currently attached to the remembered cluster Wi-Fi.
     * Uses SSID first, falling back to gateway/subnet prefix when Location redacts SSID.
     * Never triggers when the device reports a different readable Wi-Fi SSID (e.g. home Wi-Fi).
     */
    public boolean isOnTrustedClusterWifi() {
        List<WifiSnapshot> snapshots = captureAllWifiSnapshots();
        if (snapshots.isEmpty()) {
            return false;
        }

        String savedSsid = getSavedClusterSsid();
        String savedPrefix = getSavedClusterSubnet();
        String savedGateway = prefs().getString(KEY_CLUSTER_GATEWAY_IP, null);

        // Pass 1: exact SSID match across all active Wi-Fi interfaces (avoids P2P hotspot shadowing bike Wi-Fi)
        if (savedSsid != null && !savedSsid.isEmpty()) {
            boolean sawAnyReadableSsid = false;
            for (WifiSnapshot snap : snapshots) {
                if (snap.ssid != null) {
                    sawAnyReadableSsid = true;
                    if (savedSsid.equals(snap.ssid)) {
                        return true;
                    }
                }
            }
            // If every active Wi-Fi interface reported a different readable SSID (e.g. home Wi-Fi), do not false-trigger
            if (sawAnyReadableSsid && snapshots.size() == 1) {
                return false;
            }
        }

        // Pass 2: gateway or IPv4 subnet fallback (for when Android Location permission redacts SSID)
        for (WifiSnapshot snap : snapshots) {
            if (snap.ssid != null && savedSsid != null && !savedSsid.isEmpty() && !savedSsid.equals(snap.ssid)) {
                continue;
            }
            if (savedGateway != null && savedGateway.equals(snap.gatewayIp)) {
                return true;
            }
            if (savedPrefix != null && savedPrefix.equals(snap.subnetPrefix)) {
                return true;
            }
        }
        return false;
    }

    private WifiSnapshot captureCurrentWifiSnapshot() {
        List<WifiSnapshot> all = captureAllWifiSnapshots();
        if (!all.isEmpty()) {
            // Prefer an interface with a non-P2P subnet (not 192.168.49.) when recording the bike network
            for (WifiSnapshot snap : all) {
                if (snap.subnetPrefix != null && !snap.subnetPrefix.startsWith("192.168.49.")) {
                    return snap;
                }
            }
            return all.get(0);
        }
        return new WifiSnapshot();
    }

    private List<WifiSnapshot> captureAllWifiSnapshots() {
        List<WifiSnapshot> result = new ArrayList<>();
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            WifiManager wm = context.getApplicationContext().getSystemService(WifiManager.class);
            if (cm != null) {
                Network[] networks = cm.getAllNetworks();
                for (Network net : networks) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                    if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        continue;
                    }
                    WifiSnapshot snap = new WifiSnapshot();
                    snap.hasWifiTransport = true;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Object ti = caps.getTransportInfo();
                        if (ti instanceof WifiInfo) {
                            snap.ssid = cleanSsid(((WifiInfo) ti).getSSID());
                        }
                    }
                    LinkProperties lp = cm.getLinkProperties(net);
                    if (lp != null) {
                        for (LinkAddress la : lp.getLinkAddresses()) {
                            InetAddress addr = la.getAddress();
                            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                snap.subnetPrefix = extractIpv4Prefix(addr.getHostAddress());
                                break;
                            }
                        }
                        if (lp.getRoutes() != null) {
                            for (RouteInfo route : lp.getRoutes()) {
                                InetAddress gw = route.getGateway();
                                if (route.isDefaultRoute() && gw instanceof Inet4Address && !gw.isAnyLocalAddress()) {
                                    snap.gatewayIp = gw.getHostAddress();
                                    break;
                                }
                            }
                        }
                    }
                    result.add(snap);
                }
            }
            if (wm != null) {
                String fallbackSsid = null;
                @SuppressWarnings("deprecation")
                WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    fallbackSsid = cleanSsid(info.getSSID());
                }
                String fallbackGateway = null;
                @SuppressWarnings("deprecation")
                DhcpInfo dhcp = wm.getDhcpInfo();
                if (dhcp != null && dhcp.gateway != 0) {
                    fallbackGateway = formatIntIp(dhcp.gateway);
                }
                if (result.isEmpty() && (fallbackSsid != null || fallbackGateway != null)) {
                    WifiSnapshot snap = new WifiSnapshot();
                    snap.hasWifiTransport = true;
                    snap.ssid = fallbackSsid;
                    snap.gatewayIp = fallbackGateway;
                    snap.subnetPrefix = extractIpv4Prefix(fallbackGateway);
                    result.add(snap);
                } else {
                    for (WifiSnapshot snap : result) {
                        if (snap.ssid == null && fallbackSsid != null
                                && (snap.subnetPrefix == null || !snap.subnetPrefix.startsWith("192.168.49."))) {
                            snap.ssid = fallbackSsid;
                        }
                        if (snap.gatewayIp == null && fallbackGateway != null
                                && (snap.subnetPrefix == null || fallbackGateway.startsWith(snap.subnetPrefix))) {
                            snap.gatewayIp = fallbackGateway;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return result;
    }

    private static String cleanSsid(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.isEmpty() || "<unknown ssid>".equalsIgnoreCase(s)) {
            return null;
        }
        return s;
    }

    private static String extractIpv4Prefix(String ip) {
        if (ip == null) return null;
        int lastDot = ip.lastIndexOf('.');
        return lastDot > 0 ? ip.substring(0, lastDot + 1) : null;
    }

    private static String formatIntIp(int ip) {
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
    }

    private static final class WifiSnapshot {
        boolean hasWifiTransport;
        String ssid;
        String subnetPrefix;
        String gatewayIp;
    }
}
