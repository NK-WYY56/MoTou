package com.carplay.siqirelay.carlife;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Hardware AVC MediaCodec decoder taking Annex-B H.264 frames from Baidu CarLife
 * and rendering directly onto the VirtualDisplay TextureView Surface.
 */
public class CarLifeVideoDecoder {
    private static final String TAG = "CarLifeVideoDecoder";

    public interface Callback {
        void onFirstFrameRendered(int width, int height);
        void onFrameDecoded(long frameCount);
    }

    private final Object lock = new Object();
    private Surface outputSurface;
    private MediaCodec decoder;
    private Thread outputThread;
    private volatile boolean running = false;
    private volatile int width = 1280;
    private volatile int height = 560;
    private Callback callback;

    private byte[] cachedSps;
    private byte[] cachedPps;
    private boolean firstRendered = false;
    private long decodedCount = 0;

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void configureDimensions(int width, int height) {
        this.width = Math.max(320, width);
        this.height = Math.max(240, height);
    }

    public void attachSurface(Surface surface) {
        synchronized (lock) {
            this.outputSurface = surface;
            if (surface == null || !surface.isValid()) {
                releaseCodecLocked();
                return;
            }
            if (decoder != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        decoder.setOutputSurface(surface);
                        Log.i(TAG, "Switched CarLife decoder output Surface cleanly");
                        return;
                    } catch (Exception e) {
                        Log.w(TAG, "setOutputSurface failed, recreating decoder: " + e.getMessage());
                    }
                }
                releaseCodecLocked();
            }
            if (cachedSps != null && cachedPps != null) {
                initCodecLocked(cachedSps, cachedPps);
            }
        }
    }

    public void feedAnnexB(byte[] annexB) {
        if (annexB == null || annexB.length == 0) return;

        List<byte[]> nalus = splitAnnexBNalus(annexB);
        if (nalus.isEmpty()) return;

        boolean hasIdr = false;
        boolean paramsUpdated = false;
        for (byte[] nalu : nalus) {
            int offset = startCodeLength(nalu);
            if (offset >= nalu.length) continue;
            int nalType = nalu[offset] & 0x1F;
            if (nalType == 7) {
                if (cachedSps == null || !Arrays.equals(cachedSps, nalu)) {
                    cachedSps = nalu;
                    paramsUpdated = true;
                }
            } else if (nalType == 8) {
                if (cachedPps == null || !Arrays.equals(cachedPps, nalu)) {
                    cachedPps = nalu;
                    paramsUpdated = true;
                }
            } else if (nalType == 5) {
                hasIdr = true;
            }
        }

        synchronized (lock) {
            if (outputSurface == null || !outputSurface.isValid()) {
                return;
            }
            if (decoder == null || (paramsUpdated && hasIdr)) {
                if (cachedSps == null || cachedPps == null || !hasIdr) {
                    return;
                }
                releaseCodecLocked();
                initCodecLocked(cachedSps, cachedPps);
            }
            if (decoder == null || !running) return;

            try {
                int inIdx = decoder.dequeueInputBuffer(10_000);
                if (inIdx >= 0) {
                    ByteBuffer buf = decoder.getInputBuffer(inIdx);
                    if (buf != null) {
                        buf.clear();
                        if (buf.remaining() >= annexB.length) {
                            buf.put(annexB);
                            decoder.queueInputBuffer(inIdx, 0, annexB.length, System.nanoTime() / 1000L, 0);
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, 0);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "feedAnnexB decode error, resetting decoder: " + e.getMessage());
                releaseCodecLocked();
            }
        }
    }

    public void reset() {
        synchronized (lock) {
            releaseCodecLocked();
            cachedSps = null;
            cachedPps = null;
            firstRendered = false;
            decodedCount = 0;
        }
    }

    private void initCodecLocked(byte[] sps, byte[] pps) {
        if (outputSurface == null || !outputSurface.isValid()) return;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
            MediaCodec codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(format, outputSurface, null, 0);
            codec.start();

            decoder = codec;
            running = true;
            startOutputLoop(codec);
            Log.i(TAG, "CarLife AVC decoder started (" + width + "x" + height + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to init CarLife AVC decoder: " + e.getMessage(), e);
            releaseCodecLocked();
        }
    }

    private void startOutputLoop(MediaCodec codec) {
        outputThread = new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running && decoder == codec) {
                try {
                    int outIdx = codec.dequeueOutputBuffer(info, 15_000);
                    if (outIdx >= 0) {
                        codec.releaseOutputBuffer(outIdx, true);
                        decodedCount++;
                        Callback cb = callback;
                        if (!firstRendered) {
                            firstRendered = true;
                            if (cb != null) cb.onFirstFrameRendered(width, height);
                        }
                        if (cb != null) cb.onFrameDecoded(decodedCount);
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat fmt = codec.getOutputFormat();
                        if (fmt.containsKey(MediaFormat.KEY_WIDTH) && fmt.containsKey(MediaFormat.KEY_HEIGHT)) {
                            width = fmt.getInteger(MediaFormat.KEY_WIDTH);
                            height = fmt.getInteger(MediaFormat.KEY_HEIGHT);
                        }
                    }
                } catch (IllegalStateException e) {
                    break;
                } catch (Exception e) {
                    if (running) Log.w(TAG, "Decoder drain notice: " + e.getMessage());
                }
            }
        }, "CarLifeDecoderDrain");
        outputThread.setDaemon(true);
        outputThread.start();
    }

    private void releaseCodecLocked() {
        running = false;
        Thread t = outputThread;
        outputThread = null;
        if (decoder != null) {
            try { decoder.stop(); } catch (Exception ignored) {}
            try { decoder.release(); } catch (Exception ignored) {}
            decoder = null;
        }
        if (t != null && t != Thread.currentThread()) {
            try { t.join(250); } catch (InterruptedException ignored) {}
        }
    }

    private static List<byte[]> splitAnnexBNalus(byte[] data) {
        List<byte[]> result = new ArrayList<>();
        int i = 0;
        int naluStart = -1;
        while (i < data.length - 3) {
            boolean is4 = (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1);
            boolean is3 = !is4 && (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1);
            if (is4 || is3) {
                if (naluStart >= 0 && i > naluStart) {
                    result.add(Arrays.copyOfRange(data, naluStart, i));
                }
                naluStart = i;
                i += is4 ? 4 : 3;
            } else {
                i++;
            }
        }
        if (naluStart >= 0 && naluStart < data.length) {
            result.add(Arrays.copyOfRange(data, naluStart, data.length));
        }
        return result;
    }

    private static int startCodeLength(byte[] nalu) {
        if (nalu.length >= 4 && nalu[0] == 0 && nalu[1] == 0 && nalu[2] == 0 && nalu[3] == 1) return 4;
        if (nalu.length >= 3 && nalu[0] == 0 && nalu[1] == 0 && nalu[2] == 1) return 3;
        return 0;
    }
}
