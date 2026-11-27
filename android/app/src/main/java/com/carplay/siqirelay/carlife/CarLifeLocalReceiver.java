package com.carplay.siqirelay.carlife;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

import com.carplay.siqirelay.carplay.DiPlayConfigOptimizer;
import com.carplay.siqirelay.input.HandlebarKeyBridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Built-in local Baidu CarLife Head-Unit (HU) receiver engine.
 * Supports dual independent hardware decoders (cluster VirtualDisplay + touchable phone preview),
 * ordered touch/key event forwarding (tap, drag, two-finger zoom, home/back/voice),
 * and handlebar key control.
 */
public class CarLifeLocalReceiver {
    private static final String TAG = "CarLifeLocalReceiver";
    private static final String[] CARLIFE_PACKAGES = new String[]{
            "com.baidu.carlife",
            "com.baidu.carlife.xiaomi",
            "com.baidu.carlife.honor",
            "com.baidu.carlife.vivo",
            "com.miui.carlink",
            "com.xiaomi.carlink",
            "com.miui.carwith",
            "com.xiaomi.carwith"
    };

    public enum State {
        STOPPED,
        DISCOVERING,
        HANDSHAKING,
        STREAMING
    }

    public interface Listener {
        void onStateChanged(State state, String detail);
        void onVideoFrame(long frameCount, int width, int height);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CarLifeVideoDecoder clusterDecoder = new CarLifeVideoDecoder("cluster");
    private final CarLifeVideoDecoder previewDecoder = new CarLifeVideoDecoder("preview");
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService inputExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CarLifeInputOrdered");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile State state = State.STOPPED;
    private volatile String statusText = "本机 CarLife 未启动";
    private volatile String peerHost = "127.0.0.1";
    private volatile String discoveredLanHost = null;
    private volatile int negotiatedWidth = 1280;
    private volatile int negotiatedHeight = 560;
    private volatile long decodedFrames = 0L;
    private volatile long videoPacketsReceived = 0L;
    private volatile int nextProtocolMajor = 1; // try 1.0 first, auto-fallback to 3.0 if MD rejects

    private final Object keyframeCacheLock = new Object();
    private byte[] latestSps;
    private byte[] latestPps;
    private byte[] latestIdrFrame;

    private long lastMoveSentMs = 0L;
    private int lastMoveX = Integer.MIN_VALUE;
    private int lastMoveY = Integer.MIN_VALUE;

    private DatagramSocket udpBeaconSocket;
    private Thread udpThread;
    private Thread probeThread;
    private Thread heartbeatThread;

    private Socket cmdSocket;
    private OutputStream cmdOut;
    private Socket videoSocket;
    private OutputStream videoOut;
    private Socket touchSocket;
    private OutputStream touchOut;
    private final Socket[] auxSockets = new Socket[4];

    private boolean huInfoSent = false;
    private boolean videoInitSent = false;
    private boolean featureReplySent = false;

    public CarLifeLocalReceiver(Context context) {
        this.context = context.getApplicationContext();
        CarLifeVideoDecoder.Callback frameCb = new CarLifeVideoDecoder.Callback() {
            @Override
            public void onFirstFrameRendered(int width, int height) {
                negotiatedWidth = width;
                negotiatedHeight = height;
                updateState(State.STREAMING, "本机 CarLife 投屏中（" + width + "×" + height + "）");
                notifyFrame(Math.max(1L, decodedFrames), width, height);
            }

            @Override
            public void onFrameDecoded(long frameCount) {
                if (frameCount > decodedFrames) {
                    decodedFrames = frameCount;
                }
                if (frameCount == 1 || frameCount % 30 == 0) {
                    notifyFrame(decodedFrames, negotiatedWidth, negotiatedHeight);
                }
            }
        };
        this.clusterDecoder.setCallback(frameCb);
        this.previewDecoder.setCallback(frameCb);
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public State getState() {
        return state;
    }

    public String getStatusText() {
        return statusText;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isConnected() {
        return state == State.HANDSHAKING || state == State.STREAMING;
    }

    public long getDecodedFrames() {
        return Math.max(decodedFrames, videoPacketsReceived);
    }

    public int getNegotiatedWidth() {
        return negotiatedWidth;
    }

    public int getNegotiatedHeight() {
        return negotiatedHeight;
    }

    public boolean hasReceivedVideo() {
        return videoPacketsReceived > 0;
    }

    public void attachOutputSurface(Surface surface) {
        int[] canvas = DiPlayConfigOptimizer.getEffectiveCarLifeCanvasSize(context);
        clusterDecoder.configureDimensions(canvas[0], canvas[1]);
        clusterDecoder.attachSurface(surface);
        if (surface != null) {
            byte[] sps, pps, idr;
            synchronized (keyframeCacheLock) {
                sps = latestSps;
                pps = latestPps;
                idr = latestIdrFrame;
            }
            if (sps != null && pps != null && idr != null) {
                clusterDecoder.bootstrapWithCachedFrame(sps, pps, idr);
            }
            if (isConnected()) {
                requestKeyframe();
            }
        }
    }

    public void attachPreviewSurface(Surface surface) {
        previewDecoder.configureDimensions(negotiatedWidth, negotiatedHeight);
        previewDecoder.attachSurface(surface);
        if (surface != null) {
            byte[] sps, pps, idr;
            synchronized (keyframeCacheLock) {
                sps = latestSps;
                pps = latestPps;
                idr = latestIdrFrame;
            }
            if (sps != null && pps != null && idr != null) {
                previewDecoder.bootstrapWithCachedFrame(sps, pps, idr);
            }
            if (isConnected()) {
                requestKeyframe();
            }
        }
    }

    public void detachPreviewSurface(Surface expected) {
        if (expected == null || previewDecoder.getOutputSurface() == expected) {
            previewDecoder.attachSurface(null);
        }
    }

    public synchronized void start() {
        if (running.getAndSet(true)) return;
        int[] canvas = DiPlayConfigOptimizer.getEffectiveCarLifeCanvasSize(context);
        negotiatedWidth = canvas[0];
        negotiatedHeight = canvas[1];
        clusterDecoder.configureDimensions(negotiatedWidth, negotiatedHeight);
        previewDecoder.configureDimensions(negotiatedWidth, negotiatedHeight);
        decodedFrames = 0L;
        videoPacketsReceived = 0L;
        synchronized (keyframeCacheLock) {
            latestSps = null;
            latestPps = null;
            latestIdrFrame = null;
        }

        updateState(State.DISCOVERING, "正在等待本机 CarLife+ 接入（127.0.0.1:7240）...");
        startUdpDiscoveryListener();
        startConnectLoop();
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        closeUdp();
        closeSessionSockets();
        clusterDecoder.reset();
        previewDecoder.reset();
        decodedFrames = 0L;
        videoPacketsReceived = 0L;
        synchronized (keyframeCacheLock) {
            latestSps = null;
            latestPps = null;
            latestIdrFrame = null;
        }
        updateState(State.STOPPED, "本机 CarLife 已停止");
    }

    public void requestKeyframe() {
        if (!running.get()) return;
        final OutputStream out = cmdOut;
        if (out == null) return;
        inputExecutor.execute(() -> {
            try {
                CarLifeProtocol.writeMessage(out, CarLifeProtocol.CH_CMD, CarLifeProtocol.MSG_CMD_VIDEO_ENCODER_RESET, null);
                CarLifeProtocol.writeMessage(out, CarLifeProtocol.CH_CMD, CarLifeProtocol.MSG_CMD_VIDEO_ENCODER_START, null);
            } catch (Exception ignored) {}
        });
    }

    public void sendHomeKey() {
        sendRawHardKeyCode(3); // KEYCODE_HOME
    }

    public void sendBackKey() {
        sendRawHardKeyCode(4); // KEYCODE_BACK
    }

    public void sendVoiceKey() {
        sendRawHardKeyCode(231); // KEYCODE_VOICE_ASSIST
    }

    private void sendRawHardKeyCode(final int keyCode) {
        if (!isConnected()) return;
        final OutputStream out = touchOut;
        if (out == null) return;
        inputExecutor.execute(() -> {
            try {
                byte[] payload = new CarLifeProtocol.ProtoWriter()
                        .writeInt(1, keyCode)
                        .toByteArray();
                CarLifeProtocol.writeMessage(out, CarLifeProtocol.CH_TOUCH,
                        CarLifeProtocol.MSG_TOUCH_CAR_HARD_KEY_CODE, payload);
            } catch (Exception e) {
                Log.w(TAG, "sendRawHardKeyCode error: " + e.getMessage());
            }
        });
    }

    public void sendHardKey(HandlebarKeyBridge.KeyAction action, boolean isLongPress) {
        if (!isConnected() || action == null) return;
        final int carlifeKeyCode;
        switch (action) {
            case UP:
                carlifeKeyCode = 19; // KEYCODE_DPAD_UP
                break;
            case DOWN:
                carlifeKeyCode = 20; // KEYCODE_DPAD_DOWN
                break;
            case CONFIRM:
                carlifeKeyCode = isLongPress ? 231 : 23; // KEYCODE_VOICE_ASSIST / KEYCODE_DPAD_CENTER
                break;
            case BACK:
                carlifeKeyCode = 4; // KEYCODE_BACK
                break;
            case SIRI:
                carlifeKeyCode = 231; // KEYCODE_VOICE_ASSIST
                break;
            case NEXT:
                carlifeKeyCode = 87; // KEYCODE_MEDIA_NEXT
                break;
            case PREV:
                carlifeKeyCode = 88; // KEYCODE_MEDIA_PREVIOUS
                break;
            default:
                return;
        }
        sendRawHardKeyCode(carlifeKeyCode);
    }

    public void sendTouch(int action, int x, int y) {
        final OutputStream out = touchOut;
        if (!isConnected() || out == null) return;
        inputExecutor.execute(() -> {
            try {
                byte[] payload = new CarLifeProtocol.ProtoWriter()
                        .writeInt(1, action)
                        .writeInt(2, x)
                        .writeInt(3, y)
                        .toByteArray();
                CarLifeProtocol.writeMessage(out, CarLifeProtocol.CH_TOUCH,
                        CarLifeProtocol.MSG_TOUCH_ACTION, payload);
            } catch (Exception ignored) {}
        });
    }

    /**
     * Maps Android MotionEvent coordinates inside the preview TextureView into CarLife
     * canvas coordinates and dispatches them cleanly on the ordered single-thread writer.
     */
    public void sendTouchEvent(MotionEvent event, int viewWidth, int viewHeight) {
        if (event == null || !isConnected() || viewWidth <= 0 || viewHeight <= 0) return;
        final OutputStream out = touchOut;
        if (out == null) return;

        final int w = Math.max(1, negotiatedWidth);
        final int h = Math.max(1, negotiatedHeight);
        final int action = event.getAction();
        final int masked = event.getActionMasked();

        int x0 = clampCoord((event.getX(0) / viewWidth) * w, w);
        int y0 = clampCoord((event.getY(0) / viewHeight) * h, h);

        if (masked == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastMoveSentMs < 12L
                    && Math.abs(x0 - lastMoveX) < 2
                    && Math.abs(y0 - lastMoveY) < 2) {
                return;
            }
            lastMoveSentMs = now;
            lastMoveX = x0;
            lastMoveY = y0;
        } else if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
            lastMoveSentMs = 0L;
            lastMoveX = Integer.MIN_VALUE;
            lastMoveY = Integer.MIN_VALUE;
        }

        final boolean hasSecondPointer = event.getPointerCount() > 1;
        final int x1 = hasSecondPointer ? clampCoord((event.getX(1) / viewWidth) * w, w) : 0;
        final int y1 = hasSecondPointer ? clampCoord((event.getY(1) / viewHeight) * h, h) : 0;

        inputExecutor.execute(() -> {
            try {
                CarLifeProtocol.ProtoWriter writer = new CarLifeProtocol.ProtoWriter()
                        .writeInt(1, action)
                        .writeInt(2, x0)
                        .writeInt(3, y0);
                if (hasSecondPointer) {
                    writer.writeInt(4, x1).writeInt(5, y1);
                }
                CarLifeProtocol.writeMessage(out, CarLifeProtocol.CH_TOUCH,
                        CarLifeProtocol.MSG_TOUCH_ACTION, writer.toByteArray());
            } catch (Exception ignored) {}
        });
    }

    private static int clampCoord(float raw, int maxExclusive) {
        int val = Math.round(raw);
        return Math.max(0, Math.min(maxExclusive - 1, val));
    }

    private void startUdpDiscoveryListener() {
        udpThread = new Thread(() -> {
            try {
                DatagramSocket ds = new DatagramSocket(CarLifeProtocol.UDP_BEACON_PORT);
                ds.setSoTimeout(1500);
                udpBeaconSocket = ds;
                byte[] buf = new byte[1024];
                while (running.get()) {
                    try {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        ds.receive(pkt);
                        InetAddress addr = pkt.getAddress();
                        if (addr != null) {
                            String host = addr.getHostAddress();
                            if (host != null && !host.isEmpty()) {
                                discoveredLanHost = host;
                                Log.d(TAG, "UDP 7999 beacon seen from " + host);
                            }
                        }
                    } catch (java.net.SocketTimeoutException ignored) {
                    } catch (Exception e) {
                        if (running.get()) Log.d(TAG, "UDP 7999 notice: " + e.getMessage());
                        break;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "UDP 7999 bind skipped: " + e.getMessage());
            } finally {
                closeUdp();
            }
        }, "CarLifeUdpDiscovery");
        udpThread.setDaemon(true);
        udpThread.start();
    }

    private void startConnectLoop() {
        probeThread = new Thread(() -> {
            while (running.get()) {
                try {
                    sendUdpQuery("127.0.0.1");
                    String candidate = tryConnectControl();
                    if (candidate == null) {
                        Thread.sleep(1500);
                        continue;
                    }
                    peerHost = candidate;
                    runSession(candidate);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    if (running.get()) {
                        Log.w(TAG, "CarLife probe cycle notice: " + e.getMessage());
                    }
                } finally {
                    closeSessionSockets();
                    if (running.get() && state != State.DISCOVERING) {
                        updateState(State.DISCOVERING, "CarLife 连接已断开，等待重新接入...");
                    }
                }
                if (running.get()) {
                    try {
                        Thread.sleep(1200);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
        }, "CarLifeSessionProbe");
        probeThread.setDaemon(true);
        probeThread.start();
    }

    private void sendUdpQuery(String host) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            byte[] payload = "{\"carlifehost\":\"query\"}".getBytes(StandardCharsets.UTF_8);
            InetAddress addr = InetAddress.getByName(host);
            s.send(new DatagramPacket(payload, payload.length, addr, CarLifeProtocol.UDP_QUERY_PORT));
        } catch (Exception ignored) {
        } finally {
            if (s != null) {
                try { s.close(); } catch (Exception ignored) {}
            }
        }
    }

    private String tryConnectControl() {
        String[] hosts = (discoveredLanHost != null && !"127.0.0.1".equals(discoveredLanHost))
                ? new String[]{"127.0.0.1", discoveredLanHost}
                : new String[]{"127.0.0.1"};
        for (String h : hosts) {
            Socket sock = null;
            try {
                sock = new Socket();
                sock.connect(new InetSocketAddress(h, CarLifeProtocol.PORTS[0]), 900);
                sock.setTcpNoDelay(true);
                cmdSocket = sock;
                cmdOut = sock.getOutputStream();
                return h;
            } catch (Exception ignored) {
                if (sock != null) {
                    try { sock.close(); } catch (Exception ignored2) {}
                }
            }
        }
        return null;
    }

    private void runSession(String host) throws Exception {
        huInfoSent = false;
        videoInitSent = false;
        featureReplySent = false;
        decodedFrames = 0L;
        updateState(State.HANDSHAKING, "已连接 " + host + ":7240，正在 CarLife 握手...");

        // Connect Video & Touch + auxiliary drain channels cleanly
        connectMediaChannels(host);
        startVideoReader();
        startHeartbeatLoop();

        int major = nextProtocolMajor;
        byte[] verPayload = new CarLifeProtocol.ProtoWriter()
                .writeInt(1, major)
                .writeInt(2, 0)
                .toByteArray();
        CarLifeProtocol.writeMessage(cmdOut, CarLifeProtocol.CH_CMD,
                CarLifeProtocol.MSG_CMD_HU_PROTOCOL_VERSION, verPayload);
        Log.i(TAG, "Sent HU_PROTOCOL_VERSION major=" + major + " to " + host);

        InputStream in = cmdSocket.getInputStream();
        while (running.get() && cmdSocket != null && !cmdSocket.isClosed()) {
            CarLifeProtocol.Message msg = CarLifeProtocol.readMessage(in, CarLifeProtocol.CH_CMD);
            if (msg == null) break;
            handleControlMessage(msg);
        }
    }

    private void connectMediaChannels(String host) {
        // Channel 2: Video (8240)
        try {
            Socket vSock = new Socket();
            vSock.connect(new InetSocketAddress(host, CarLifeProtocol.PORTS[1]), 1500);
            vSock.setTcpNoDelay(true);
            videoSocket = vSock;
            videoOut = vSock.getOutputStream();
        } catch (Exception e) {
            Log.w(TAG, "Video channel 8240 connect notice: " + e.getMessage());
        }

        // Channel 6: Touch / Hard Key (9340)
        try {
            Socket tSock = new Socket();
            tSock.connect(new InetSocketAddress(host, CarLifeProtocol.PORTS[5]), 1200);
            tSock.setTcpNoDelay(true);
            touchSocket = tSock;
            touchOut = tSock.getOutputStream();
        } catch (Exception ignored) {}

        // Auxiliary channels 3 (Media 9240), 4 (TTS 9241), 5 (VR 9242), 7 (Data 9440):
        // connect and drain silently so phone audio stays routed to Bluetooth helmet headset.
        int[] auxPorts = {
                CarLifeProtocol.PORTS[2],
                CarLifeProtocol.PORTS[3],
                CarLifeProtocol.PORTS[4],
                CarLifeProtocol.PORTS[6]
        };
        int[] auxChannels = {
                CarLifeProtocol.CH_MEDIA,
                CarLifeProtocol.CH_TTS,
                CarLifeProtocol.CH_VR,
                CarLifeProtocol.CH_DATA
        };
        for (int i = 0; i < auxPorts.length; i++) {
            final int idx = i;
            final int port = auxPorts[i];
            final int ch = auxChannels[i];
            Thread t = new Thread(() -> {
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(host, port), 1000);
                    auxSockets[idx] = s;
                    InputStream ais = s.getInputStream();
                    while (running.get() && !s.isClosed()) {
                        if (CarLifeProtocol.readMessage(ais, ch) == null) break;
                    }
                } catch (Exception ignored) {}
            }, "CarLifeAuxDrain-" + ch);
            t.setDaemon(true);
            t.start();
        }
    }

    private void startVideoReader() {
        Socket vSock = videoSocket;
        if (vSock == null) return;
        Thread videoThread = new Thread(() -> {
            try {
                InputStream vin = vSock.getInputStream();
                while (running.get() && videoSocket == vSock && !vSock.isClosed()) {
                    CarLifeProtocol.Message msg = CarLifeProtocol.readMessage(vin, CarLifeProtocol.CH_VIDEO);
                    if (msg == null) break;
                    if (msg.id == CarLifeProtocol.MSG_VIDEO_DATA) {
                        handleVideoFrame(msg.payload);
                    } else if (msg.id == CarLifeProtocol.MSG_CMD_VIDEO_ENCODER_INIT_DONE) {
                        handleVideoEncoderInitDone(msg.payload);
                    }
                }
            } catch (Exception e) {
                if (running.get()) Log.w(TAG, "CarLife video channel ended: " + e.getMessage());
            }
        }, "CarLifeVideoReader");
        videoThread.setDaemon(true);
        videoThread.start();
    }

    private void handleVideoFrame(byte[] payload) {
        if (payload == null || payload.length == 0) return;
        cacheKeyframeIfPresent(payload);
        videoPacketsReceived++;
        if (videoPacketsReceived == 1) {
            updateState(State.STREAMING, "本机 CarLife 投屏中（" + negotiatedWidth + "×" + negotiatedHeight + "）");
            notifyFrame(1L, negotiatedWidth, negotiatedHeight);
        } else if (videoPacketsReceived % 30 == 0) {
            notifyFrame(getDecodedFrames(), negotiatedWidth, negotiatedHeight);
        }
        clusterDecoder.feedAnnexB(payload);
        previewDecoder.feedAnnexB(payload);
    }

    private void cacheKeyframeIfPresent(byte[] data) {
        byte[] sps = null;
        byte[] pps = null;
        boolean hasIdr = false;
        int i = 0;
        int naluStart = -1;
        while (i <= data.length) {
            boolean atEnd = (i == data.length);
            boolean is4 = !atEnd && i <= data.length - 4 && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1;
            boolean is3 = !atEnd && !is4 && i <= data.length - 3 && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1;
            if (atEnd || is4 || is3) {
                if (naluStart >= 0 && i > naluStart) {
                    int hdrOff = (naluStart + 3 < i && data[naluStart + 2] == 0) ? 4 : 3;
                    if (naluStart + hdrOff < i) {
                        int type = data[naluStart + hdrOff] & 0x1F;
                        if (type == 7) sps = Arrays.copyOfRange(data, naluStart, i);
                        else if (type == 8) pps = Arrays.copyOfRange(data, naluStart, i);
                        else if (type == 5) hasIdr = true;
                    }
                }
                if (atEnd) break;
                naluStart = i;
                i += is4 ? 4 : 3;
            } else {
                i++;
            }
        }
        if (sps != null || pps != null || hasIdr) {
            synchronized (keyframeCacheLock) {
                if (sps != null) latestSps = sps;
                if (pps != null) latestPps = pps;
                if (hasIdr) latestIdrFrame = Arrays.copyOf(data, data.length);
            }
        }
    }

    private void startHeartbeatLoop() {
        heartbeatThread = new Thread(() -> {
            while (running.get() && cmdSocket != null && !cmdSocket.isClosed()) {
                try {
                    Thread.sleep(2000);
                    OutputStream vOut = videoOut;
                    if (vOut != null) {
                        CarLifeProtocol.writeMessage(vOut, CarLifeProtocol.CH_VIDEO,
                                CarLifeProtocol.MSG_VIDEO_HEARTBEAT, null);
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception ignored) {
                    break;
                }
            }
        }, "CarLifeHeartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void handleControlMessage(CarLifeProtocol.Message msg) throws IOException {
        CarLifeProtocol.ProtoReader p = msg.payload.length > 0
                ? CarLifeProtocol.ProtoReader.parse(msg.payload)
                : null;

        switch (msg.id) {
            case CarLifeProtocol.MSG_CMD_MD_PROTOCOL_VERSION_MATCH_STATUS: {
                int match = p != null ? p.getInt(1, 0) : 0;
                int mdVer = p != null ? p.getInt(2, 1) : 1;
                if (match != 1) {
                    // Flip between 1 and 3 on next reconnect attempt for broad CarLife+ compatibility
                    nextProtocolMajor = (nextProtocolMajor == 1) ? 3 : 1;
                    Log.w(TAG, "MD rejected protocol version (mdVer=" + mdVer + "), next attempt major=" + nextProtocolMajor);
                    closeSessionSockets();
                    return;
                }
                sendHuInfoAndMaybeAuth(mdVer);
                break;
            }
            case CarLifeProtocol.MSG_CMD_MD_INFO: {
                if (nextProtocolMajor >= 3) {
                    byte[] authReq = new CarLifeProtocol.ProtoWriter()
                            .writeString(1, "2.0;motou_hu_local")
                            .toByteArray();
                    CarLifeProtocol.writeMessage(cmdOut, CarLifeProtocol.CH_CMD,
                            CarLifeProtocol.MSG_CMD_HU_AUTHEN_REQUEST, authReq);
                } else {
                    sendVideoEncoderInitIfNeeded();
                }
                break;
            }
            case CarLifeProtocol.MSG_CMD_MD_AUTHEN_RESPONSE: {
                byte[] res = new CarLifeProtocol.ProtoWriter().writeInt(1, 1).toByteArray();
                CarLifeProtocol.writeMessage(cmdOut, CarLifeProtocol.CH_CMD,
                        CarLifeProtocol.MSG_CMD_HU_AUTHEN_RESULT, res);
                break;
            }
            case CarLifeProtocol.MSG_CMD_MD_AUTHEN_RESULT: {
                CarLifeProtocol.writeMessage(cmdOut, CarLifeProtocol.CH_CMD,
                        CarLifeProtocol.MSG_CMD_AUTH_FINISH, null);
                sendFeatureConfigRequest();
                sendVideoEncoderInitIfNeeded();
                break;
            }
            case CarLifeProtocol.MSG_CMD_MD_FEATURE_CONFIG_RESPONSE: {
                sendFeatureConfigRequest();
                sendVideoEncoderInitIfNeeded();
                break;
            }
            case CarLifeProtocol.MSG_CMD_VIDEO_ENCODER_INIT_DONE: {
                handleVideoEncoderInitDone(msg.payload);
                break;
            }
            default:
                break;
        }
    }

    private void sendHuInfoAndMaybeAuth(int mdVer) throws IOException {
        if (!huInfoSent) {
            huInfoSent = true;
            byte[] info = new CarLifeProtocol.ProtoWriter()
                    .writeString(1, "Android")
                    .writeString(4, "MoTou")
                    .writeString(14, "Kove-450RR")
                    .writeString(20, "1")
                    .writeString(24, "4.0.0")
                    .toByteArray();
            CarLifeProtocol.writeMessage(cmdOut, CarLifeProtocol.CH_CMD,
                    CarLifeProtocol.MSG_CMD_HU_INFO, info);
        }
        if (mdVer <= 1) {
            sendVideoEncoderInitIfNeeded();
        }
    }

    private void sendFeatureConfigRequest() throws IOException {
        if (featureReplySent) return;
        featureReplySent = true;
        byte[] contentEnc = new CarLifeProtocol.ProtoWriter()
                .writeString(1, "CONTENT_ENCRYPTION")
                .writeInt(2, 0)
                .toByteArray();
        byte[] aacSup = new CarLifeProtocol.ProtoWriter()
                .writeString(1, "AAC_SUPPORT")
                .writeInt(2, 0)
                .toByteArray();
        byte[] req = new CarLifeProtocol.ProtoWriter()
                .writeInt(1, 2)
                .writeBytes(2, contentEnc)
                .writeBytes(2, aacSup)
                .toByteArray();
        CarLifeProtocol.writeMessage(cmdOut, CarLifeProtocol.CH_CMD,
                CarLifeProtocol.MSG_CMD_HU_FEATURE_CONFIG_REQUEST, req);
    }

    private void sendVideoEncoderInitIfNeeded() throws IOException {
        if (videoInitSent) return;
        videoInitSent = true;
        int[] canvas = DiPlayConfigOptimizer.getEffectiveCarLifeCanvasSize(context);
        negotiatedWidth = canvas[0];
        negotiatedHeight = canvas[1];
        clusterDecoder.configureDimensions(negotiatedWidth, negotiatedHeight);
        previewDecoder.configureDimensions(negotiatedWidth, negotiatedHeight);

        byte[] init = new CarLifeProtocol.ProtoWriter()
                .writeInt(1, negotiatedWidth)
                .writeInt(2, negotiatedHeight)
                .writeInt(3, 30)
                .toByteArray();
        CarLifeProtocol.writeMessage(cmdOut, CarLifeProtocol.CH_CMD,
                CarLifeProtocol.MSG_CMD_VIDEO_ENCODER_INIT, init);
        Log.i(TAG, "Sent VIDEO_ENCODER_INIT " + negotiatedWidth + "x" + negotiatedHeight + "@30fps");
    }

    private void handleVideoEncoderInitDone(byte[] payload) throws IOException {
        if (payload != null && payload.length > 0) {
            CarLifeProtocol.ProtoReader p = CarLifeProtocol.ProtoReader.parse(payload);
            int w = p.getInt(1, negotiatedWidth);
            int h = p.getInt(2, negotiatedHeight);
            if (w >= 320 && h >= 240) {
                negotiatedWidth = w;
                negotiatedHeight = h;
                clusterDecoder.configureDimensions(w, h);
                previewDecoder.configureDimensions(w, h);
            }
        }
        OutputStream out = cmdOut;
        if (out != null) {
            CarLifeProtocol.writeMessage(out, CarLifeProtocol.CH_CMD,
                    CarLifeProtocol.MSG_CMD_VIDEO_ENCODER_START, null);
        }
        updateState(State.HANDSHAKING, "CarLife 已就绪，等待视频首帧（" + negotiatedWidth + "×" + negotiatedHeight + "）");
    }

    private void closeUdp() {
        DatagramSocket ds = udpBeaconSocket;
        udpBeaconSocket = null;
        if (ds != null) {
            try { ds.close(); } catch (Exception ignored) {}
        }
        Thread t = udpThread;
        udpThread = null;
        if (t != null && t != Thread.currentThread()) t.interrupt();
    }

    private void closeSessionSockets() {
        Thread hb = heartbeatThread;
        heartbeatThread = null;
        if (hb != null && hb != Thread.currentThread()) hb.interrupt();

        closeQuiet(cmdSocket);
        cmdSocket = null;
        cmdOut = null;

        closeQuiet(videoSocket);
        videoSocket = null;
        videoOut = null;

        closeQuiet(touchSocket);
        touchSocket = null;
        touchOut = null;

        for (int i = 0; i < auxSockets.length; i++) {
            closeQuiet(auxSockets[i]);
            auxSockets[i] = null;
        }
    }

    private static void closeQuiet(Socket s) {
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void updateState(State newState, String text) {
        this.state = newState;
        this.statusText = text;
        mainHandler.post(() -> {
            for (Listener l : listeners) {
                try { l.onStateChanged(newState, text); } catch (Exception ignored) {}
            }
        });
    }

    private void notifyFrame(long count, int w, int h) {
        mainHandler.post(() -> {
            for (Listener l : listeners) {
                try { l.onVideoFrame(count, w, h); } catch (Exception ignored) {}
            }
        });
    }

    /**
     * Launches an installed CarLife+ / Carlink companion app on this Android phone.
     * Returns the package name launched, or null if none is installed.
     */
    public static String launchLocalCarLifeApp(Context ctx) {
        if (ctx == null) return null;
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : CARLIFE_PACKAGES) {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
                return pkg;
            }
        }
        return null;
    }
}
