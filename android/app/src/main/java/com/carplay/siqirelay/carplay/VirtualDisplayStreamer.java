package com.carplay.siqirelay.carplay;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.carplay.siqirelay.kove.KoveClusterServer;
import com.shilapi.xcertplay.SiQiRelayBridge;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Scheme A Virtual Display Streamer for Kove 450RR cluster.
 * Creates a private offscreen Android VirtualDisplay (1280x640) backed by hardware AVC encoder
 * [CarPlayVirtualPipeline], hosts a Presentation SurfaceView for embedded DiPlay, and streams H.264
 * directly to TCP 15456 — leaving the physical phone display completely free or turned off.
 */
public class VirtualDisplayStreamer implements KoveClusterServer.VideoControlCallback, SiQiRelayBridge.VirtualRelayListener {
    private static final String TAG = "VirtualDisplayStreamer";
    private static final int VIRTUAL_DPI = 240;

    private final Context context;
    private final KoveClusterServer clusterServer;
    private final CarPlayVirtualPipeline virtualPipeline;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean isEnabled = false;
    private volatile int sessionGen = 0;
    private long lastKeyframeRequestMs = 0L;

    private VirtualDisplay virtualDisplay;
    private VirtualCarPlayPresentation presentation;

    public VirtualDisplayStreamer(Context context, KoveClusterServer clusterServer) {
        this.context = context.getApplicationContext();
        this.clusterServer = clusterServer;
        this.virtualPipeline = new CarPlayVirtualPipeline(clusterServer);
    }

    public synchronized void start() {
        if (isEnabled) return;
        isEnabled = true;
        SiQiRelayBridge.addVirtualRelayListener(this);
        if (clusterServer != null) {
            clusterServer.setVideoControlCallback(this);
            if (clusterServer.isVideoStreamingActive()) {
                onVideoClientConnected();
            }
        }
        Log.i(TAG, "VirtualDisplayStreamer armed (idle until cluster 15456 connects).");
    }

    public synchronized void stop() {
        isEnabled = false;
        sessionGen++;
        SiQiRelayBridge.removeVirtualRelayListener(this);
        if (clusterServer != null) {
            clusterServer.setVideoControlCallback(null);
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            teardownVirtualScreenOnMain();
        } else {
            mainHandler.post(this::teardownVirtualScreenOnMain);
        }
        Log.i(TAG, "VirtualDisplayStreamer stopped.");
    }

    public CarPlayVirtualPipeline getPipeline() {
        return virtualPipeline;
    }

    public boolean isVirtualScreenStreaming() {
        return isEnabled && virtualDisplay != null && virtualPipeline.isRunning();
    }

    public void updateBorderLayout() {
        mainHandler.post(() -> {
            if (presentation != null) {
                presentation.applyBorderLayout();
            }
        });
    }

    @Override
    public void onVideoClientConnected() {
        if (!isEnabled) return;
        final int gen = ++sessionGen;
        Log.i(TAG, "Cluster 15456 connected -> starting VirtualDisplay 1280x640 offscreen pipeline gen=" + gen);
        mainHandler.post(() -> startVirtualScreenOnMain(gen));
    }

    @Override
    public void onVideoClientDisconnected() {
        final int gen = ++sessionGen;
        Log.i(TAG, "Cluster 15456 disconnected -> tearing down VirtualDisplay pipeline gen=" + gen);
        mainHandler.post(() -> teardownIfCurrentGen(gen));
    }

    @Override
    public synchronized void onCongestionNeedKeyframe() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastKeyframeRequestMs < 1000L) {
            return;
        }
        lastKeyframeRequestMs = now;
        virtualPipeline.requestKeyframe();
    }

    @Override
    public void onSessionStageChanged(String stage, boolean active) {
        mainHandler.post(() -> {
            if (presentation != null) {
                presentation.updateStatus(stage);
            }
        });
    }

    @Override
    public void onMainVideoStreamChanged(boolean active) {
        mainHandler.post(() -> {
            if (presentation != null) {
                presentation.setVideoActive(active);
            }
        });
    }

    private void startVirtualScreenOnMain(int expectedGen) {
        if (!isEnabled || expectedGen != sessionGen) return;
        teardownVirtualScreenOnMain();

        try {
            android.view.Surface encoderSurface = virtualPipeline.start();
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                Log.e(TAG, "DisplayManager unavailable!");
                return;
            }
            virtualDisplay = dm.createVirtualDisplay(
                    "KoveCarPlayVirtual",
                    CarPlayVirtualPipeline.WIDTH,
                    CarPlayVirtualPipeline.HEIGHT,
                    VIRTUAL_DPI,
                    encoderSurface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                    null,
                    mainHandler
            );
            Display vd = virtualDisplay.getDisplay();
            Context displayCtx = context.createDisplayContext(vd);
            presentation = new VirtualCarPlayPresentation(displayCtx, vd);
            presentation.show();
            Log.i(TAG, "VirtualDisplay + Presentation active displayId=" + vd.getDisplayId() + " (" +
                    CarPlayVirtualPipeline.WIDTH + "x" + CarPlayVirtualPipeline.HEIGHT + ")");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start VirtualDisplay pipeline: " + t.getMessage(), t);
            teardownVirtualScreenOnMain();
        }
    }

    private void teardownIfCurrentGen(int expectedGen) {
        if (expectedGen != sessionGen) return;
        teardownVirtualScreenOnMain();
    }

    private void teardownVirtualScreenOnMain() {
        SiQiRelayBridge.setVirtualMainSurface(null);
        if (presentation != null) {
            try { presentation.dismiss(); } catch (Exception ignored) {}
            presentation = null;
        }
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        virtualPipeline.stop();
    }

    private class VirtualCarPlayPresentation extends Presentation {
        private final Handler pulseHandler = new Handler(Looper.getMainLooper());
        private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        private TextureView videoTextureView;
        private Surface videoSurface;
        private LinearLayout waitingOverlay;
        private TextView tvStage;
        private TextView tvClock;
        private boolean videoActive = SiQiRelayBridge.isMainVideoActive();

        private final Runnable pulseRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isShowing() || videoActive) return;
                if (tvClock != null) {
                    tvClock.setText("等待 iPhone 连接 · " + sdf.format(new Date()));
                }
                if (tvStage != null) {
                    tvStage.setText(formatStageLine());
                }
                pulseHandler.postDelayed(this, 750);
            }
        };

        VirtualCarPlayPresentation(Context outerContext, Display display) {
            super(outerContext, display);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            Window w = getWindow();
            if (w != null) {
                w.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            }

            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(Color.BLACK);

            videoTextureView = new TextureView(getContext());
            videoTextureView.setOpaque(true);
            videoTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                    Log.i(TAG, "VirtualDisplay TextureView ready size=" + width + "x" + height + " -> attaching CarPlay 110 mirror!");
                    releaseVideoSurface();
                    applyVideoTransform(width, height);
                    videoSurface = new Surface(surfaceTexture);
                    SiQiRelayBridge.setVirtualMainSurface(videoSurface);
                    virtualPipeline.requestKeyframe();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                    Log.i(TAG, "VirtualDisplay TextureView size=" + width + "x" + height);
                    applyVideoTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                    Log.i(TAG, "VirtualDisplay TextureView destroyed -> detaching CarPlay mirror");
                    releaseVideoSurface();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {}
            });
            root.addView(videoTextureView, buildTextureParams());

            waitingOverlay = new LinearLayout(getContext());
            waitingOverlay.setOrientation(LinearLayout.VERTICAL);
            waitingOverlay.setGravity(Gravity.CENTER);
            waitingOverlay.setBackgroundColor(Color.parseColor("#0C111B"));

            ProgressBar spinner = new ProgressBar(getContext());
            spinner.setIndeterminate(true);
            LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(72, 72);
            spLp.bottomMargin = 24;
            waitingOverlay.addView(spinner, spLp);

            TextView tvTitle = new TextView(getContext());
            tvTitle.setText("思骑CarPlay · 等待连接");
            tvTitle.setTextColor(Color.parseColor("#F1F5FC"));
            tvTitle.setTextSize(22f);
            tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
            tvTitle.setGravity(Gravity.CENTER);
            waitingOverlay.addView(tvTitle);

            tvStage = new TextView(getContext());
            tvStage.setText(formatStageLine());
            tvStage.setTextColor(Color.parseColor("#A6C8FF"));
            tvStage.setTextSize(15f);
            tvStage.setGravity(Gravity.CENTER);
            tvStage.setMaxLines(3);
            tvStage.setPadding(36, 14, 36, 8);
            waitingOverlay.addView(tvStage);

            tvClock = new TextView(getContext());
            tvClock.setText("手机可直接锁屏或切换其他应用");
            tvClock.setTextColor(Color.parseColor("#A8B6CA"));
            tvClock.setTextSize(13f);
            tvClock.setGravity(Gravity.CENTER);
            waitingOverlay.addView(tvClock);

            root.addView(waitingOverlay, new FrameLayout.LayoutParams(-1, -1));
            setContentView(root);
            setVideoActive(videoActive);
        }

        private void releaseVideoSurface() {
            SiQiRelayBridge.setVirtualMainSurface(null);
            if (videoSurface != null) {
                try {
                    videoSurface.release();
                } catch (Exception ignored) {}
                videoSurface = null;
            }
        }

        private FrameLayout.LayoutParams buildTextureParams() {
            DiPlayConfigOptimizer.BikePresetSpec preset = DiPlayConfigOptimizer.getActivePresetSpec(context);
            int targetTop = Math.max(0, Math.min(preset.viewportTop, CarPlayVirtualPipeline.HEIGHT - 64));
            int targetH = Math.max(64, Math.min(preset.viewportHeight, CarPlayVirtualPipeline.HEIGHT - targetTop));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    CarPlayVirtualPipeline.WIDTH,
                    targetH,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL
            );
            lp.topMargin = targetTop;
            return lp;
        }

        private void applyVideoTransform(int viewW, int viewH) {
            if (videoTextureView == null) return;
            DiPlayConfigOptimizer.BikePresetSpec preset = DiPlayConfigOptimizer.getActivePresetSpec(context);
            int w = viewW > 0 ? viewW : CarPlayVirtualPipeline.WIDTH;
            int h = viewH > 0 ? viewH : preset.viewportHeight;

            Matrix matrix = new Matrix();
            matrix.setScale(preset.overscanX, 1.0f, w * 0.5f, h * 0.5f);
            videoTextureView.setTransform(matrix);
        }

        void applyBorderLayout() {
            if (videoTextureView != null) {
                FrameLayout.LayoutParams target = buildTextureParams();
                android.view.ViewGroup.LayoutParams cur = videoTextureView.getLayoutParams();
                boolean changed = !(cur instanceof FrameLayout.LayoutParams)
                        || cur.width != target.width
                        || cur.height != target.height
                        || ((FrameLayout.LayoutParams) cur).topMargin != target.topMargin;
                if (changed) {
                    videoTextureView.setLayoutParams(target);
                }
                applyVideoTransform(target.width, target.height);
            }
        }

        private String formatStageLine() {
            String stage = SiQiRelayBridge.getSessionStage();
            String hint = SiQiRelayBridge.getLastDiagnosticHint();
            if (!SiQiRelayBridge.hasSession() && hint != null && !hint.isEmpty() && !stage.contains(hint)) {
                return "状态: " + stage + "\n" + hint;
            }
            return "状态: " + stage;
        }

        void updateStatus(String stage) {
            if (tvStage != null && !videoActive) {
                tvStage.setText(formatStageLine());
            }
        }

        void setVideoActive(boolean active) {
            this.videoActive = active;
            if (waitingOverlay != null) {
                waitingOverlay.setVisibility(active ? View.GONE : View.VISIBLE);
            }
            pulseHandler.removeCallbacks(pulseRunnable);
            if (!active && isShowing()) {
                pulseHandler.post(pulseRunnable);
            }
            if (active) {
                virtualPipeline.requestKeyframe();
            }
        }

        @Override
        public void dismiss() {
            pulseHandler.removeCallbacks(pulseRunnable);
            releaseVideoSurface();
            super.dismiss();
        }
    }
}
