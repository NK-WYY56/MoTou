package com.carplay.siqirelay.kove;

import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core Cluster Server implementation for 思骑 (Thinkerride / Kove 450RR).
 * Operates port 17818 (control/navi state), 15457 (heartbeat), and 15456 (H.264 video streaming).
 *
 * Includes zero-glitch IDR synchronization gate and bounded low-latency frame-drop queue
 * to prevent first-frame pixelation ("花屏") and TCP latency accumulation.
 */
public class KoveClusterServer {
    private static final String TAG = "KoveClusterServer";

    public static final int WIDTH = 1280;
    public static final int HEIGHT = 640;
    private static final int MAX_QUEUED_FRAMES = 30;

    public interface ClusterStateListener {
        void onClusterConnected(String clientIp);
        void onClusterDisconnected();
        void onVideoStreamActive();
        void onVideoStreamClosed();
        void onFrameStreamed(long frameCount, long bytesStreamed);
    }

    public interface VideoControlCallback {
        void onVideoClientConnected();
        void onVideoClientDisconnected();
        void onCongestionNeedKeyframe();
    }

    private static class VideoFrame {
        final byte[] data;
        final boolean isKeyFrame;

        VideoFrame(byte[] data, boolean isKeyFrame) {
            this.data = data;
            this.isKeyFrame = isKeyFrame;
        }
    }

    private final List<ClusterStateListener> listeners = new CopyOnWriteArrayList<>();
    private VideoControlCallback videoControlCallback;
    private volatile boolean isRunning = false;

    private ServerSocket server17818;
    private ServerSocket server15457;
    private ServerSocket server15456;
    private Thread controlServerThread;
    private Thread heartbeatServerThread;
    private Thread videoServerThread;

    private volatile Socket currentControlClient;
    private volatile Socket currentHeartbeatClient;
    private volatile Socket currentVideoClient;
    private volatile OutputStream videoOutputStream;
    private Thread videoSenderThread;
    private Thread videoReaderThread;

    private final Object queueLock = new Object();
    private final ArrayDeque<VideoFrame> frameQueue = new ArrayDeque<>();
    private volatile boolean awaitingIdr = true;
    private long lastCongestionResetTime = 0;

    private volatile byte[] cachedSpsPps = null;
    private final AtomicLong totalFramesStreamed = new AtomicLong(0);
    private final AtomicLong totalBytesStreamed = new AtomicLong(0);
    private volatile int videoWidth = WIDTH;
    private volatile int videoHeight = HEIGHT;

    public synchronized void setVideoOutputSize(int width, int height) {
        int safeW = Math.max(320, Math.min(1920, (width / 2) * 2));
        int safeH = Math.max(240, Math.min(1200, (height / 2) * 2));
        boolean changed = (this.videoWidth != safeW || this.videoHeight != safeH);
        this.videoWidth = safeW;
        this.videoHeight = safeH;
        if (changed) {
            Log.i(TAG, "Updated cluster 69B video output size -> " + safeW + "x" + safeH);
            if (isVideoStreamingActive()) {
                // Close active 15456 socket so the cluster reconnects and receives the updated 69B header
                closeVideoClient();
            }
        }
    }

    public int getVideoWidth() {
        return videoWidth;
    }

    public int getVideoHeight() {
        return videoHeight;
    }

    public void addListener(ClusterStateListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(ClusterStateListener listener) {
        listeners.remove(listener);
    }

    public void setListener(ClusterStateListener listener) {
        listeners.clear();
        if (listener != null) listeners.add(listener);
    }

    public void setVideoControlCallback(VideoControlCallback callback) {
        this.videoControlCallback = callback;
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;

        startControlServer(17818);
        startHeartbeatServer(15457);
        startVideoServer(15456);

        Log.i(TAG, "All Kove Cluster Server sockets active (17818, 15457, 15456).");
    }

    public boolean isVideoStreamingActive() {
        return currentVideoClient != null && !currentVideoClient.isClosed() && videoOutputStream != null;
    }

    public boolean isClusterControlConnected() {
        return currentControlClient != null && !currentControlClient.isClosed();
    }

    public boolean isAnyClusterActive() {
        return isClusterControlConnected() || isVideoStreamingActive();
    }

    public void updateSpsPps(byte[] spsPps) {
        if (spsPps != null && spsPps.length > 0) {
            cachedSpsPps = spsPps.clone();
            Log.i(TAG, "Cached SPS/PPS headers (" + spsPps.length + " bytes)");
        }
    }

    public void forceKeyframe() {
        synchronized (queueLock) {
            frameQueue.clear();
            awaitingIdr = true;
            queueLock.notifyAll();
        }
        if (videoControlCallback != null) {
            videoControlCallback.onCongestionNeedKeyframe();
        }
        Log.i(TAG, "Triggered fresh IDR keyframe sync for cluster");
    }

    /**
     * Enqueues a complete, Annex-B start-code-aligned H.264 NALU/Access Unit.
     * Drops pre-IDR delta frames on new client connect and clears stale backlog if network stalls.
     */
    public void enqueueH264Frame(byte[] data, boolean isKeyFrame) {
        if (!isRunning || !isVideoStreamingActive() || data == null || data.length == 0) {
            return;
        }

        boolean needCongestionRestart = false;
        synchronized (queueLock) {
            if (awaitingIdr) {
                if (!isKeyFrame) {
                    // Strictly drop P-frames until an IDR arrives so cluster never glitches on first frame
                    return;
                }
                frameQueue.clear();
                frameQueue.addLast(new VideoFrame(data, true));
                awaitingIdr = false;
                queueLock.notifyAll();
                return;
            }

            if (isKeyFrame) {
                // Fresh IDR replaces any older queued frames -> resets latency to ~0ms cleanly
                frameQueue.clear();
                frameQueue.addLast(new VideoFrame(data, true));
                queueLock.notifyAll();
                return;
            }

            if (frameQueue.size() >= MAX_QUEUED_FRAMES) {
                // 2.4GHz Wi-Fi momentarily stalled: drop stale delta queue & request fresh IDR
                frameQueue.clear();
                awaitingIdr = true;
                long now = System.currentTimeMillis();
                if (now - lastCongestionResetTime > 1200) {
                    lastCongestionResetTime = now;
                    needCongestionRestart = true;
                }
                Log.w(TAG, "Video queue congested (>= " + MAX_QUEUED_FRAMES + " frames), dropping backlog & syncing IDR");
            } else {
                frameQueue.addLast(new VideoFrame(data, false));
                queueLock.notifyAll();
            }
        }

        if (needCongestionRestart && videoControlCallback != null) {
            videoControlCallback.onCongestionNeedKeyframe();
        }
    }

    /**
     * Appends trailing continuation bytes of a previously admitted large (>128KB) NALU slice.
     * Ignored while awaitingIdr is true so truncated/pre-IDR fragments are never emitted.
     */
    public void enqueueH264Continuation(byte[] data) {
        if (!isRunning || !isVideoStreamingActive() || data == null || data.length == 0) {
            return;
        }
        boolean needCongestionRestart = false;
        synchronized (queueLock) {
            if (awaitingIdr) return;
            if (frameQueue.size() >= MAX_QUEUED_FRAMES) {
                frameQueue.clear();
                awaitingIdr = true;
                long now = System.currentTimeMillis();
                if (now - lastCongestionResetTime > 1200) {
                    lastCongestionResetTime = now;
                    needCongestionRestart = true;
                }
                Log.w(TAG, "Video continuation queue congested (>= " + MAX_QUEUED_FRAMES + " frames), dropping backlog & syncing IDR");
            } else {
                frameQueue.addLast(new VideoFrame(data, false));
                queueLock.notifyAll();
            }
        }
        if (needCongestionRestart && videoControlCallback != null) {
            videoControlCallback.onCongestionNeedKeyframe();
        }
    }

    /**
     * Legacy fallback push helper (wraps raw buffer as non-IDR unless starting with SPS/IDR).
     */
    public void sendRawH264Stream(byte[] data, int offset, int length) {
        if (data == null || length <= 0) return;
        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);
        boolean isKey = length > 4 && data[offset] == 0 && data[offset + 1] == 0 &&
                ((data[offset + 2] == 0 && data[offset + 3] == 1 && ((data[offset + 4] & 0x1F) == 7 || (data[offset + 4] & 0x1F) == 5)) ||
                 (data[offset + 2] == 1 && ((data[offset + 3] & 0x1F) == 7 || (data[offset + 3] & 0x1F) == 5)));
        enqueueH264Frame(copy, isKey);
    }

    public void sendH264Data(byte[] data, int offset, int length, boolean isKeyFrame) {
        if (data == null || length <= 0) return;
        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);
        enqueueH264Frame(copy, isKeyFrame);
    }

    private void startControlServer(int port) {
        controlServerThread = new Thread(() -> {
            while (isRunning) {
                try {
                    server17818 = new ServerSocket();
                    server17818.setReuseAddress(true);
                    server17818.bind(new InetSocketAddress("0.0.0.0", port));
                    Log.i(TAG, "Control server bound to 0.0.0.0:" + port);

                    while (isRunning && !server17818.isClosed()) {
                        Socket client = server17818.accept();
                        try { client.setKeepAlive(true); } catch (Exception ignored) {}
                        Socket prev = currentControlClient;
                        currentControlClient = client;
                        if (prev != null && prev != client) {
                            closeSocketQuietly(prev);
                        }
                        String ip = client.getInetAddress().getHostAddress();
                        Log.i(TAG, ">>> BIKE CONNECTED ON 17818: " + client.getRemoteSocketAddress());
                        for (ClusterStateListener l : listeners) {
                            try { l.onClusterConnected(ip); } catch (Exception ignored) {}
                        }

                        new Thread(() -> handleControlClient(client), "ControlClientHandler").start();
                    }
                } catch (Exception e) {
                    if (isRunning) {
                        try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                    }
                } finally {
                    try { if (server17818 != null) server17818.close(); } catch (Exception ignored) {}
                }
            }
        }, "17818Server");
        controlServerThread.start();
    }

    private void handleControlClient(Socket client) {
        Thread keepalive = null;
        try (OutputStream out = client.getOutputStream();
             InputStream in = client.getInputStream()) {

            // 1. TUC Query
            String getTuc = "{\"msg_id\":27,\"func\":\"TUC\",\"act\":\"GET\"}";
            out.write(KoveProtocol.wrapFrame(getTuc.getBytes(StandardCharsets.UTF_8)));

            // 2. Device Type = Android
            out.write(KoveProtocol.makeDeviceTypePacket());

            // 3. Link Info
            out.write(KoveProtocol.makeLinkInfoPacket("CarPlay-Bridge"));

            // 4. Request Firmware Version
            out.write(KoveProtocol.makeFirmwareRequestPacket());

            // 5. Navigation Start JSON
            byte[] naviStartFrame = KoveProtocol.makeNaviStartPacket();
            out.write(naviStartFrame);
            out.flush();

            // Heartbeat thread on 17818 (every 2s with navi start reaffirmation)
            keepalive = new Thread(() -> {
                byte[] hb = KoveProtocol.makeKeepalivePacket();
                byte[] naviData = KoveProtocol.makeNaviInfoPacket(1, "Apple CarPlay", 1000, 10000);
                int count = 0;
                try {
                    while (isRunning && currentControlClient == client && !client.isClosed()) {
                        out.write(hb);
                        out.write(naviData);
                        count++;
                        if (count % 2 == 0) {
                            out.write(naviStartFrame);
                        }
                        out.flush();
                        Thread.sleep(2000);
                    }
                } catch (Exception e) {
                    // Close dead socket so handleControlClient's blocking read exits promptly on bike power-off
                    closeSocketQuietly(client);
                }
            }, "17818Keepalive");
            keepalive.start();

            byte[] buf = new byte[2048];
            while (isRunning && currentControlClient == client && !client.isClosed()) {
                int len = in.read(buf);
                if (len == -1) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "17818 client disconnected: " + e.getMessage());
        } finally {
            if (keepalive != null) {
                keepalive.interrupt();
            }
            closeSocketQuietly(client);
            boolean activeLost = (currentControlClient == client);
            if (activeLost) {
                currentControlClient = null;
                for (ClusterStateListener l : listeners) {
                    try { l.onClusterDisconnected(); } catch (Exception ignored) {}
                }
            }
        }
    }

    private void startHeartbeatServer(int port) {
        heartbeatServerThread = new Thread(() -> {
            while (isRunning) {
                try {
                    server15457 = new ServerSocket();
                    server15457.setReuseAddress(true);
                    server15457.bind(new InetSocketAddress("0.0.0.0", port));

                    while (isRunning && !server15457.isClosed()) {
                        Socket client = server15457.accept();
                        client.setTcpNoDelay(true);
                        try { client.setKeepAlive(true); } catch (Exception ignored) {}
                        Socket prev = currentHeartbeatClient;
                        currentHeartbeatClient = client;
                        if (prev != null && prev != client) {
                            closeSocketQuietly(prev);
                        }
                        new Thread(() -> handleHeartbeatClient(client), "HBHandler").start();
                    }
                } catch (Exception e) {
                    if (isRunning) {
                        try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                    }
                } finally {
                    try { if (server15457 != null) server15457.close(); } catch (Exception ignored) {}
                }
            }
        }, "HeartbeatServer");
        heartbeatServerThread.start();
    }

    private void handleHeartbeatClient(Socket client) {
        byte[] hbPacket = new byte[]{0x02, 0x01, 0x00, 0x00, 0x00, 0x00};
        Thread reader = null;
        try (OutputStream out = client.getOutputStream();
             InputStream in = client.getInputStream()) {

            reader = new Thread(() -> {
                byte[] buf = new byte[128];
                try {
                    while (isRunning && currentHeartbeatClient == client && !client.isClosed()) {
                        int r = in.read(buf);
                        if (r == -1) break;
                    }
                } catch (Exception ignored) {}
            }, "HBReader");
            reader.start();

            while (isRunning && currentHeartbeatClient == client && !client.isClosed()) {
                out.write(hbPacket);
                out.flush();
                Thread.sleep(450);
            }
        } catch (Exception ignored) {}
        finally {
            if (reader != null) {
                reader.interrupt();
            }
            closeSocketQuietly(client);
            if (currentHeartbeatClient == client) {
                currentHeartbeatClient = null;
            }
        }
    }

    private void startVideoServer(int port) {
        videoServerThread = new Thread(() -> {
            while (isRunning) {
                try {
                    server15456 = new ServerSocket();
                    server15456.setReuseAddress(true);
                    server15456.bind(new InetSocketAddress("0.0.0.0", port));

                    while (isRunning && !server15456.isClosed()) {
                        Socket client = server15456.accept();
                        client.setTcpNoDelay(true);
                        try { client.setKeepAlive(true); } catch (Exception ignored) {}
                        client.setSendBufferSize(16384); // 16KB minimizes kernel TCP queue lag
                        Log.i(TAG, ">>> BIKE CONNECTED ON 15456 (VIDEO STREAM)!");

                        setupVideoClient(client);
                    }
                } catch (Exception e) {
                    if (isRunning) {
                        try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                    }
                } finally {
                    try { if (server15456 != null) server15456.close(); } catch (Exception ignored) {}
                }
            }
        }, "VideoServer");
        videoServerThread.start();
    }

    private synchronized void setupVideoClient(Socket client) {
        closeVideoClient();
        currentVideoClient = client;
        totalFramesStreamed.set(0);
        totalBytesStreamed.set(0);

        synchronized (queueLock) {
            frameQueue.clear();
            awaitingIdr = true;
            queueLock.notifyAll();
        }

        try {
            OutputStream out = client.getOutputStream();
            videoOutputStream = out;
            InputStream in = client.getInputStream();

            // 1. Drain incoming ACK reads from cluster
            videoReaderThread = new Thread(() -> {
                byte[] buf = new byte[64];
                try {
                    while (isRunning && currentVideoClient == client && !client.isClosed()) {
                        int len = in.read(buf);
                        if (len == -1) break;
                    }
                } catch (Exception ignored) {}
                closeVideoClientForSocket(client);
            }, "VideoSocketReader");
            videoReaderThread.start();

            // 2. Send 69-byte video format header (android, active stream size) ONLY; do NOT push stale P-frames!
            int outW = videoWidth;
            int outH = videoHeight;
            byte[] formatHeader = KoveProtocol.makeVideoFormatHeader(outW, outH);
            out.write(formatHeader);
            out.flush();
            Log.i(TAG, "Sent 69-byte format header (android, " + outW + "x" + outH + "), waiting for clean IDR keyframe");

            // 3. Start dedicated async video sender thread
            videoSenderThread = new Thread(() -> runVideoSender(client, out), "VideoSenderThread");
            videoSenderThread.setPriority(Thread.MAX_PRIORITY);
            videoSenderThread.start();

            for (ClusterStateListener l : listeners) {
                try { l.onVideoStreamActive(); } catch (Exception ignored) {}
            }
            if (videoControlCallback != null) {
                videoControlCallback.onVideoClientConnected();
            }

        } catch (Exception e) {
            Log.w(TAG, "Failed to initialize video client: " + e.getMessage());
            closeVideoClient();
        }
    }

    private void runVideoSender(Socket client, OutputStream out) {
        boolean firstKeyframeSent = false;
        try {
            while (isRunning && currentVideoClient == client && !client.isClosed()) {
                VideoFrame frame;
                synchronized (queueLock) {
                    while (frameQueue.isEmpty() && isRunning && currentVideoClient == client && !client.isClosed()) {
                        queueLock.wait(200);
                    }
                    if (!isRunning || currentVideoClient != client || client.isClosed()) break;
                    frame = frameQueue.pollFirst();
                }

                if (frame == null) continue;

                // Ensure very first frame sent to decoder starts with SPS/PPS + IDR
                if (!firstKeyframeSent) {
                    if (!frame.isKeyFrame) continue;
                    firstKeyframeSent = true;
                    Log.i(TAG, "Writing first clean SPS/PPS + IDR keyframe (" + frame.data.length + " bytes) to cluster");
                }

                out.write(frame.data);
                out.flush();

                long frames = totalFramesStreamed.incrementAndGet();
                long bytes = totalBytesStreamed.addAndGet(frame.data.length);

                if (frames == 1 || frames % 30 == 0) {
                    for (ClusterStateListener l : listeners) {
                        try { l.onFrameStreamed(frames, bytes); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Video sender stopped for client: " + e.getMessage());
            closeVideoClientForSocket(client);
        }
    }

    private synchronized void closeVideoClientForSocket(Socket client) {
        if (currentVideoClient == client) {
            closeVideoClient();
        }
    }

    private synchronized void closeVideoClient() {
        boolean hadClient = (currentVideoClient != null);
        if (currentVideoClient != null) {
            try { currentVideoClient.close(); } catch (Exception ignored) {}
            currentVideoClient = null;
        }
        videoOutputStream = null;

        synchronized (queueLock) {
            frameQueue.clear();
            awaitingIdr = true;
            queueLock.notifyAll();
        }

        if (videoSenderThread != null) {
            videoSenderThread.interrupt();
            videoSenderThread = null;
        }
        if (videoReaderThread != null) {
            videoReaderThread.interrupt();
            videoReaderThread = null;
        }

        if (hadClient) {
            for (ClusterStateListener l : listeners) {
                try { l.onVideoStreamClosed(); } catch (Exception ignored) {}
            }
            if (videoControlCallback != null) {
                videoControlCallback.onVideoClientDisconnected();
            }
        }
    }

    private void closeSocketQuietly(Socket socket) {
        if (socket != null) {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    public synchronized void stop() {
        isRunning = false;
        closeVideoClient();
        closeSocketQuietly(currentControlClient);
        currentControlClient = null;
        closeSocketQuietly(currentHeartbeatClient);
        currentHeartbeatClient = null;
        try { if (server17818 != null) server17818.close(); } catch (Exception ignored) {}
        try { if (server15457 != null) server15457.close(); } catch (Exception ignored) {}
        try { if (server15456 != null) server15456.close(); } catch (Exception ignored) {}
        if (controlServerThread != null) { controlServerThread.interrupt(); controlServerThread = null; }
        if (heartbeatServerThread != null) { heartbeatServerThread.interrupt(); heartbeatServerThread = null; }
        if (videoServerThread != null) { videoServerThread.interrupt(); videoServerThread = null; }
    }
}
