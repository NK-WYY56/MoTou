package com.carplay.siqirelay.carplay;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

import com.carplay.siqirelay.kove.KoveClusterServer;

import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * High-performance Hardware Screen Streamer (Route B Backpack Mode).
 *
 * Key engineering guarantees:
 * 1. On-demand activation: Only runs screenrecord while cluster port 15456 is connected (0% idle heat/battery).
 * 2. Instant clean IDR sync on connect: Cleanly starts/restarts capture whenever cluster opens 15456
 *    or screen orientation rotates (portrait -> landscape), preventing first-frame pixelation ("花屏").
 * 3. Synchronous process cleanup: Waits for previous screenrecord teardown before spawning new one
 *    (eliminates async pkill race condition that killed newly started screenrecord instances).
 * 4. Stateful Annex-B NALU framing: Assembles complete NALU frames, caches SPS+PPS, prepends SPS+PPS
 *    to every IDR keyframe, and forwards frame-by-frame to KoveClusterServer's non-blocking queue.
 */
public class ScreenStreamer implements KoveClusterServer.VideoControlCallback {
    private static final String TAG = "ScreenStreamer";
    private static final int BITRATE = 2000000;

    private final Context context;
    private final KoveClusterServer clusterServer;
    private final Object controlLock = new Object();

    private volatile boolean isServiceRunning = false;
    private volatile boolean isStreamingDesired = false;
    private volatile int streamGeneration = 0;

    private Thread streamThread;
    private volatile Process recordProcess;

    private DisplayManager displayManager;
    private int lastRotation = -1;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY && displayManager != null) {
                Display d = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
                if (d != null) {
                    int rot = d.getRotation();
                    if (lastRotation != -1 && (rot % 2) != (lastRotation % 2)) {
                        Log.i(TAG, "Display orientation changed (" + lastRotation + " -> " + rot + "), resyncing IDR...");
                        lastRotation = rot;
                        if (isStreamingDesired) {
                            restartCaptureGeneration();
                        }
                    } else {
                        lastRotation = rot;
                    }
                }
            }
        }
    };

    public ScreenStreamer(Context context, KoveClusterServer clusterServer) {
        this.context = context.getApplicationContext();
        this.clusterServer = clusterServer;
    }

    public synchronized void start() {
        if (isServiceRunning) return;
        isServiceRunning = true;

        try {
            displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager != null) {
                Display d = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
                if (d != null) lastRotation = d.getRotation();
                displayManager.registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
            }
        } catch (Exception e) {
            Log.w(TAG, "DisplayListener init notice: " + e.getMessage());
        }

        if (clusterServer != null) {
            clusterServer.setVideoControlCallback(this);
        }

        // Clean any orphaned screenrecord processes left over from older app versions synchronously on boot
        killOrphanedScreenrecordSync();

        streamThread = new Thread(this::runStreamLoop, "ScreenStreamerThread");
        streamThread.setPriority(Thread.MAX_PRIORITY);
        streamThread.start();
        Log.i(TAG, "ScreenStreamer ready (on-demand idle until cluster 15456 connects).");
    }

    @Override
    public void onVideoClientConnected() {
        Log.i(TAG, "Cluster 15456 connected -> starting clean IDR capture session");
        synchronized (controlLock) {
            isStreamingDesired = true;
            streamGeneration++;
            destroyActiveProcessQuietly();
            controlLock.notifyAll();
        }
    }

    @Override
    public void onVideoClientDisconnected() {
        Log.i(TAG, "Cluster 15456 disconnected -> entering 0-CPU idle mode");
        synchronized (controlLock) {
            isStreamingDesired = false;
            streamGeneration++;
            destroyActiveProcessQuietly();
            controlLock.notifyAll();
        }
    }

    @Override
    public void onCongestionNeedKeyframe() {
        Log.i(TAG, "Restarting capture for clean IDR resync");
        if (isStreamingDesired) {
            restartCaptureGeneration();
        }
    }

    private void restartCaptureGeneration() {
        synchronized (controlLock) {
            streamGeneration++;
            destroyActiveProcessQuietly();
            controlLock.notifyAll();
        }
    }

    private void runStreamLoop() {
        byte[] readBuf = new byte[131072];

        while (isServiceRunning) {
            int myGen;
            synchronized (controlLock) {
                while (isServiceRunning && !isStreamingDesired) {
                    try {
                        controlLock.wait(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (!isServiceRunning) break;
                myGen = streamGeneration;
            }

            Process proc = null;
            try {
                // Ensure zero stale processes BEFORE spawning the new screenrecord
                killOrphanedScreenrecordSync();
                synchronized (controlLock) {
                    if (!isServiceRunning || !isStreamingDesired || myGen != streamGeneration) {
                        continue;
                    }
                }

                String cmd = "exec screenrecord --size 1280x640 --bit-rate " + BITRATE + " --output-format=h264 --time-limit 0 - 2>/dev/null";
                proc = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                recordProcess = proc;

                Log.i(TAG, "Started screenrecord capture session gen=" + myGen);
                AnnexBFrameAssembler assembler = new AnnexBFrameAssembler();

                try (InputStream in = proc.getInputStream()) {
                    int bytesRead;
                    while (isServiceRunning && isStreamingDesired && myGen == streamGeneration &&
                           (bytesRead = in.read(readBuf)) != -1) {
                        if (bytesRead > 0) {
                            boolean mayHaveMore = (bytesRead == readBuf.length) || (bytesRead == 4096) || (in.available() > 0);
                            assembler.feed(readBuf, bytesRead, mayHaveMore);
                        }
                    }
                }
            } catch (Exception e) {
                if (isServiceRunning && isStreamingDesired && myGen == streamGeneration) {
                    Log.w(TAG, "Capture session interrupted: " + e.getMessage());
                }
            } finally {
                if (proc != null) {
                    try { proc.destroyForcibly(); } catch (Exception ignored) {}
                    if (recordProcess == proc) recordProcess = null;
                }
                killOrphanedScreenrecordSync();
            }

            // Back off cleanly on EOF (e.g., non-rooted device where `su` exits immediately) or error
            synchronized (controlLock) {
                if (isServiceRunning && isStreamingDesired && myGen == streamGeneration) {
                    try {
                        controlLock.wait(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }
    }

    private void destroyActiveProcessQuietly() {
        Process p = recordProcess;
        if (p != null) {
            try { p.destroyForcibly(); } catch (Exception ignored) {}
            recordProcess = null;
        }
    }

    private void killOrphanedScreenrecordSync() {
        try {
            Process killProc = Runtime.getRuntime().exec(new String[]{"su", "-c", "pkill -9 -x screenrecord || true"});
            killProc.waitFor(1500, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {}
    }

    public synchronized void stop() {
        isServiceRunning = false;
        isStreamingDesired = false;
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(displayListener); } catch (Exception ignored) {}
        }
        synchronized (controlLock) {
            streamGeneration++;
            destroyActiveProcessQuietly();
            controlLock.notifyAll();
        }
        if (streamThread != null) {
            streamThread.interrupt();
            streamThread = null;
        }
        killOrphanedScreenrecordSync();
        Log.i(TAG, "ScreenStreamer stopped");
    }

    /**
     * Zero-lookahead Annex-B H.264 NALU framer.
     * Flushes IDR (prepended with cached SPS+PPS) and P-frames immediately per Access Unit
     * without waiting for the next frame's start code, guaranteeing instant static & dynamic display.
     */
    private class AnnexBFrameAssembler {
        private byte[] buf = new byte[262144];
        private int len = 0;
        private byte[] latestSps = null;
        private byte[] latestPps = null;
        private boolean inAdmittedSlice = false;

        void feed(byte[] chunk, int chunkLen, boolean mayHaveMore) {
            if (len + chunkLen > 2 * 1024 * 1024) {
                len = 0;
                inAdmittedSlice = false;
            }
            if (len + chunkLen > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + chunkLen));
            }
            System.arraycopy(chunk, 0, buf, len, chunkLen);
            len += chunkLen;

            int sc0 = findStartCode(0);
            if (sc0 < 0) {
                int keep = mayHaveMore ? trailingZeroCount(buf, len, 3) : 0;
                int flushLen = len - keep;
                if (flushLen > 0) {
                    if (inAdmittedSlice && clusterServer != null) {
                        clusterServer.enqueueH264Continuation(Arrays.copyOfRange(buf, 0, flushLen));
                    }
                    if (keep > 0) {
                        System.arraycopy(buf, flushLen, buf, 0, keep);
                    }
                    len = keep;
                }
                return;
            }

            if (sc0 > 0 && inAdmittedSlice && clusterServer != null) {
                clusterServer.enqueueH264Continuation(Arrays.copyOfRange(buf, 0, sc0));
            }
            inAdmittedSlice = false;

            while (sc0 >= 0) {
                int sc0Len = (buf[sc0 + 2] == 1) ? 3 : 4;
                if (sc0 + sc0Len >= len) {
                    shiftToZero(sc0);
                    return;
                }

                int sc1 = findStartCode(sc0 + sc0Len);
                if (sc1 >= 0) {
                    emitNalu(buf, sc0 + sc0Len, sc1 - (sc0 + sc0Len));
                    sc0 = sc1;
                    continue;
                }

                // Trailing NALU in current buffer: flush IDR/P immediately unless SPS/PPS header is incomplete
                int nalType = buf[sc0 + sc0Len] & 0x1F;
                if (nalType == 7 || nalType == 8) {
                    shiftToZero(sc0);
                    return;
                }

                int keep = mayHaveMore ? trailingZeroCount(buf, len, 3) : 0;
                int payloadEnd = len - keep;
                int payloadLen = payloadEnd - (sc0 + sc0Len);
                if (payloadLen > 0) {
                    emitNalu(buf, sc0 + sc0Len, payloadLen);
                    inAdmittedSlice = (nalType == 5 || nalType == 1);
                    if (keep > 0) {
                        System.arraycopy(buf, payloadEnd, buf, 0, keep);
                        len = keep;
                    } else {
                        len = 0;
                    }
                } else {
                    shiftToZero(sc0);
                }
                return;
            }
            len = 0;
        }

        private void shiftToZero(int from) {
            if (from > 0) {
                System.arraycopy(buf, from, buf, 0, len - from);
                len -= from;
            }
        }

        private int trailingZeroCount(byte[] data, int end, int maxCount) {
            int count = 0;
            while (count < maxCount && end - 1 - count >= 0 && data[end - 1 - count] == 0) {
                count++;
            }
            return count;
        }

        private int findStartCode(int from) {
            for (int i = from; i <= len - 3; i++) {
                if (buf[i] == 0 && buf[i + 1] == 0) {
                    if (buf[i + 2] == 1) return i;
                    if (i + 3 < len && buf[i + 2] == 0 && buf[i + 3] == 1) return i;
                }
            }
            return -1;
        }

        private void emitNalu(byte[] src, int payloadStart, int payloadLen) {
            if (payloadLen <= 0 || clusterServer == null) return;
            int nalType = src[payloadStart] & 0x1F;

            // Normalize to 4-byte Annex-B start code (00 00 00 01)
            byte[] nalu = new byte[4 + payloadLen];
            nalu[0] = 0;
            nalu[1] = 0;
            nalu[2] = 0;
            nalu[3] = 1;
            System.arraycopy(src, payloadStart, nalu, 4, payloadLen);

            if (nalType == 7) { // SPS
                latestSps = nalu;
                if (latestPps != null) updateCombinedSpsPps();
            } else if (nalType == 8) { // PPS
                latestPps = nalu;
                if (latestSps != null) updateCombinedSpsPps();
            } else if (nalType == 5) { // IDR Keyframe
                if (latestSps != null && latestPps != null) {
                    byte[] combined = new byte[latestSps.length + latestPps.length + nalu.length];
                    System.arraycopy(latestSps, 0, combined, 0, latestSps.length);
                    System.arraycopy(latestPps, 0, combined, latestSps.length, latestPps.length);
                    System.arraycopy(nalu, 0, combined, latestSps.length + latestPps.length, nalu.length);
                    clusterServer.enqueueH264Frame(combined, true);
                } else {
                    clusterServer.enqueueH264Frame(nalu, true);
                }
            } else if (nalType == 1) { // P-frame slice
                clusterServer.enqueueH264Frame(nalu, false);
            }
        }

        private void updateCombinedSpsPps() {
            byte[] combined = new byte[latestSps.length + latestPps.length];
            System.arraycopy(latestSps, 0, combined, 0, latestSps.length);
            System.arraycopy(latestPps, 0, combined, latestSps.length, latestPps.length);
            clusterServer.updateSpsPps(combined);
        }
    }
}
