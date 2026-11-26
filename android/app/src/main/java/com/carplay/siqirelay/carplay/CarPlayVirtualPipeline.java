package com.carplay.siqirelay.carplay;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

import com.carplay.siqirelay.kove.KoveClusterServer;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Hardware AVC offscreen encoder backing the VirtualDisplay CarPlay pipeline (Scheme A).
 * Encodes VirtualDisplay composition directly from GPU Surface to Baseline Annex-B H.264
 * with guaranteed SPS+PPS prepended to every IDR keyframe for Kove 450RR cluster (port 15456).
 */
public class CarPlayVirtualPipeline {
    private static final String TAG = "CarPlayPipeline";

    public static final int WIDTH = 1280;
    public static final int HEIGHT = 640;
    public static final int FPS = 30;
    public static final int BITRATE = 2500000;

    private final KoveClusterServer clusterServer;
    private MediaCodec encoder;
    private Surface inputSurface;
    private volatile boolean isRunning = false;
    private volatile boolean isStreamingToCluster = true;
    private volatile byte[] cachedSpsPps = null;
    private Thread drainThread;

    public CarPlayVirtualPipeline(KoveClusterServer clusterServer) {
        this.clusterServer = clusterServer;
    }

    public void setStreamingToCluster(boolean streaming) {
        this.isStreamingToCluster = streaming;
    }

    public synchronized Surface start() throws Exception {
        if (isRunning && inputSurface != null && inputSurface.isValid()) {
            return inputSurface;
        }
        stop();

        Log.i(TAG, "Starting Hardware AVC Offscreen Encoder (" + WIDTH + "x" + HEIGHT + " @ " + FPS + "fps)...");
        encoder = configureAvcEncoder();
        inputSurface = encoder.createInputSurface();
        encoder.start();
        isRunning = true;
        cachedSpsPps = null;

        startDrainThread();
        Log.i(TAG, "Hardware AVC Offscreen Encoder ready.");
        return inputSurface;
    }

    private MediaCodec configureAvcEncoder() throws Exception {
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            MediaFormat tuned = baseFormat();
            tuned.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            tuned.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
            tuned.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            codec.configure(tuned, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            return codec;
        } catch (Exception firstErr) {
            Log.w(TAG, "Tuned AVC configure fallback (" + firstErr.getMessage() + "), retrying basic Baseline");
            try {
                codec.release();
            } catch (Exception ignored) {}
            MediaCodec fallback = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            fallback.configure(baseFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            return fallback;
        }
    }

    private MediaFormat baseFormat() {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        return format;
    }

    public Surface getInputSurface() {
        return inputSurface;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public synchronized void requestKeyframe() {
        if (!isRunning || encoder == null) return;
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encoder.setParameters(params);
            Log.i(TAG, "Requested instant hardware IDR keyframe.");
        } catch (Exception e) {
            Log.w(TAG, "requestKeyframe error: " + e.getMessage());
        }
    }

    private void startDrainThread() {
        drainThread = new Thread(() -> {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            byte[] scratch = new byte[262144];

            while (isRunning) {
                MediaCodec current = encoder;
                if (current == null) break;
                try {
                    int index = current.dequeueOutputBuffer(bufferInfo, 15000);
                    if (index >= 0) {
                        ByteBuffer bb = current.getOutputBuffer(index);
                        if (bb != null && bufferInfo.size > 0) {
                            bb.position(bufferInfo.offset);
                            bb.limit(bufferInfo.offset + bufferInfo.size);

                            if (bufferInfo.size > scratch.length) {
                                scratch = new byte[bufferInfo.size];
                            }
                            bb.get(scratch, 0, bufferInfo.size);

                            boolean isConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                            boolean isKeyframe = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                            if (isConfig) {
                                cachedSpsPps = Arrays.copyOf(scratch, bufferInfo.size);
                                if (clusterServer != null) {
                                    clusterServer.updateSpsPps(cachedSpsPps);
                                }
                            } else if (isStreamingToCluster && clusterServer != null) {
                                if (isKeyframe) {
                                    byte[] payload = ensureSpsPpsOnKeyframe(scratch, bufferInfo.size);
                                    clusterServer.enqueueH264Frame(payload, true);
                                } else {
                                    clusterServer.enqueueH264Frame(Arrays.copyOf(scratch, bufferInfo.size), false);
                                }
                            }
                        }
                        current.releaseOutputBuffer(index, false);
                    }
                } catch (Exception e) {
                    if (!isRunning) break;
                    Log.w(TAG, "Encoder drain notice: " + e.getMessage());
                }
            }
        }, "VirtualAvcDrain");
        drainThread.setPriority(Thread.MAX_PRIORITY);
        drainThread.start();
    }

    private byte[] ensureSpsPpsOnKeyframe(byte[] frame, int len) {
        if (startsWithSps(frame, len) || cachedSpsPps == null || cachedSpsPps.length == 0) {
            return Arrays.copyOf(frame, len);
        }
        byte[] combined = new byte[cachedSpsPps.length + len];
        System.arraycopy(cachedSpsPps, 0, combined, 0, cachedSpsPps.length);
        System.arraycopy(frame, 0, combined, cachedSpsPps.length, len);
        return combined;
    }

    private static boolean startsWithSps(byte[] data, int len) {
        if (len >= 5 && data[0] == 0 && data[1] == 0) {
            if (data[2] == 1) return (data[3] & 0x1F) == 7;
            if (data[2] == 0 && data[3] == 1) return (data[4] & 0x1F) == 7;
        }
        return false;
    }

    public synchronized void stop() {
        isRunning = false;
        if (drainThread != null) {
            drainThread.interrupt();
            try { drainThread.join(400); } catch (InterruptedException ignored) {}
            drainThread = null;
        }
        if (encoder != null) {
            try { encoder.stop(); } catch (Exception ignored) {}
            try { encoder.release(); } catch (Exception ignored) {}
            encoder = null;
        }
        if (inputSurface != null) {
            try { inputSurface.release(); } catch (Exception ignored) {}
            inputSurface = null;
        }
    }
}
