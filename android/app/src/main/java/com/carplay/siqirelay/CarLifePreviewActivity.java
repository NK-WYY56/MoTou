package com.carplay.siqirelay;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.carplay.siqirelay.carlife.CarLifeLocalReceiver;
import com.carplay.siqirelay.input.HandlebarKeyBridge;

/**
 * Fullscreen landscape interactive preview for local Baidu CarLife mode.
 * Mirrors CarLife decoded output cleanly with aspect-ratio preservation while leaving
 * VirtualDisplayStreamer's cluster 15456 pipeline running independently.
 */
public class CarLifePreviewActivity extends Activity {

    private TextureView textureView;
    private TextView tvEmpty;
    private LinearLayout floatingBar;
    private Surface previewSurface;

    private BridgeService bridgeService;
    private boolean isBound = false;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable fadeToolbarRunnable = () -> {
        if (floatingBar != null) {
            floatingBar.animate().alpha(0.28f).setDuration(250).start();
        }
    };

    private final CarLifeLocalReceiver.Listener carLifeListener = new CarLifeLocalReceiver.Listener() {
        @Override
        public void onStateChanged(CarLifeLocalReceiver.State state, String detail) {
            runOnUiThread(() -> updateEmptyAndAspect());
        }

        @Override
        public void onVideoFrame(long frameCount, int width, int height) {
            if (frameCount == 1 || frameCount % 30 == 0) {
                runOnUiThread(() -> updateEmptyAndAspect());
            }
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            bridgeService = ((BridgeService.LocalBinder) binder).getService();
            isBound = true;
            CarLifeLocalReceiver cl = bridgeService.getCarLifeReceiver();
            if (cl != null) {
                cl.addListener(carLifeListener);
                if (previewSurface != null && previewSurface.isValid()) {
                    cl.attachPreviewSurface(previewSurface);
                }
            }
            updateEmptyAndAspect();
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_carlife_preview);
        enableImmersiveFullscreen();

        textureView = findViewById(R.id.texture_carlife_fullscreen);
        tvEmpty = findViewById(R.id.tv_carlife_fullscreen_empty);
        floatingBar = findViewById(R.id.bar_carlife_fullscreen_controls);

        Button btnExit = findViewById(R.id.btn_fs_exit);
        Button btnHome = findViewById(R.id.btn_fs_home);
        Button btnBack = findViewById(R.id.btn_fs_back);
        Button btnVoice = findViewById(R.id.btn_fs_voice);
        Button btnRefresh = findViewById(R.id.btn_fs_refresh);

        btnExit.setOnClickListener(v -> finish());
        btnHome.setOnClickListener(v -> withReceiver(CarLifeLocalReceiver::sendHomeKey));
        btnBack.setOnClickListener(v -> withReceiver(CarLifeLocalReceiver::sendBackKey));
        btnVoice.setOnClickListener(v -> withReceiver(CarLifeLocalReceiver::sendVoiceKey));
        btnRefresh.setOnClickListener(v -> {
            withReceiver(CarLifeLocalReceiver::requestKeyframe);
            Toast.makeText(this, "已刷新 CarLife 关键帧", Toast.LENGTH_SHORT).show();
        });

        setupTextureTouchAndSurface();
        scheduleToolbarFade();

        bindService(new Intent(this, BridgeService.class), serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private interface ReceiverOp {
        void run(CarLifeLocalReceiver cl);
    }

    private void withReceiver(ReceiverOp op) {
        wakeToolbar();
        if (bridgeService != null && bridgeService.getCarLifeReceiver() != null) {
            op.run(bridgeService.getCarLifeReceiver());
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupTextureTouchAndSurface() {
        textureView.setOpaque(true);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                releasePreviewSurface();
                previewSurface = new Surface(surfaceTexture);
                CarLifeLocalReceiver cl = getReceiver();
                if (cl != null) {
                    cl.attachPreviewSurface(previewSurface);
                }
                updateEmptyAndAspect();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                updateEmptyAndAspect();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                releasePreviewSurface();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {}
        });

        textureView.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                wakeToolbar();
            }
            CarLifeLocalReceiver cl = getReceiver();
            if (cl != null) {
                cl.sendTouchEvent(event, v.getWidth(), v.getHeight());
            }
            return true;
        });
    }

    private CarLifeLocalReceiver getReceiver() {
        return bridgeService != null ? bridgeService.getCarLifeReceiver() : null;
    }

    private void updateEmptyAndAspect() {
        CarLifeLocalReceiver cl = getReceiver();
        boolean hasVideo = cl != null && (cl.getState() == CarLifeLocalReceiver.State.STREAMING || cl.hasReceivedVideo());
        if (tvEmpty != null) {
            tvEmpty.setVisibility(hasVideo ? View.GONE : View.VISIBLE);
            if (!hasVideo && cl != null) {
                tvEmpty.setText(cl.getStatusText() + "\n请保持手机停留在投屏页面或确认百度 CarLife+ 已连接");
            }
        }
        applyAspectRatio(cl != null ? cl.getNegotiatedWidth() : 1280, cl != null ? cl.getNegotiatedHeight() : 560);
    }

    private void applyAspectRatio(int videoW, int videoH) {
        if (textureView == null || videoW <= 0 || videoH <= 0) return;
        View root = findViewById(R.id.carlife_preview_root);
        if (root == null) return;
        int rootW = root.getWidth();
        int rootH = root.getHeight();
        if (rootW <= 0 || rootH <= 0) return;

        float videoAspect = (float) videoW / (float) videoH;
        float screenAspect = (float) rootW / (float) rootH;
        int targetW;
        int targetH;
        if (screenAspect > videoAspect) {
            targetH = rootH;
            targetW = Math.round(rootH * videoAspect);
        } else {
            targetW = rootW;
            targetH = Math.round(rootW / videoAspect);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) textureView.getLayoutParams();
        if (lp.width != targetW || lp.height != targetH) {
            lp.width = targetW;
            lp.height = targetH;
            textureView.setLayoutParams(lp);
        }
    }

    private void wakeToolbar() {
        if (floatingBar != null) {
            floatingBar.animate().cancel();
            floatingBar.setAlpha(1f);
        }
        scheduleToolbarFade();
    }

    private void scheduleToolbarFade() {
        uiHandler.removeCallbacks(fadeToolbarRunnable);
        uiHandler.postDelayed(fadeToolbarRunnable, 4000L);
    }

    private void releasePreviewSurface() {
        Surface closing = previewSurface;
        previewSurface = null;
        CarLifeLocalReceiver cl = getReceiver();
        if (cl != null && closing != null) {
            cl.detachPreviewSurface(closing);
        }
        if (closing != null) {
            try { closing.release(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enableImmersiveFullscreen();
        if (textureView != null && textureView.isAvailable() && previewSurface == null) {
            previewSurface = new Surface(textureView.getSurfaceTexture());
            CarLifeLocalReceiver cl = getReceiver();
            if (cl != null) {
                cl.attachPreviewSurface(previewSurface);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        releasePreviewSurface();
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
        uiHandler.removeCallbacks(fadeToolbarRunnable);
        releasePreviewSurface();
        CarLifeLocalReceiver cl = getReceiver();
        if (cl != null) {
            cl.removeListener(carLifeListener);
        }
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    @SuppressWarnings("deprecation")
    private void enableImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController ctrl = getWindow().getInsetsController();
            if (ctrl != null) {
                ctrl.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                ctrl.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }
}
