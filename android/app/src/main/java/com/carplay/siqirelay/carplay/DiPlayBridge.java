package com.carplay.siqirelay.carplay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;
import android.view.Display;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceView;

import com.carplay.siqirelay.kove.KoveClusterServer;
import com.shilapi.xcertplay.SiQiRelayBridge;

/**
 * In-process Wireless CarPlay Bridge (embedded DiPlay engine + Kove 450RR relay).
 * Uses bundled open-source DiPlay (:common / :shared) directly within the same APK.
 */
public class DiPlayBridge {
    private static final String TAG = "DiPlayBridge";

    public static final String ACTION_EMBED_MAP = "com.shihab.diplay.action.EMBED_MAP";

    // DiPlay MapEmbedService exact IPC Protocol constants
    public static final int MSG_ATTACH = 1;
    public static final int MSG_RESIZE = 2;
    public static final int MSG_DETACH = 3;
    public static final int MSG_ATTACHED = 101;
    public static final int MSG_STREAM_STATE = 102;
    public static final int MSG_ERROR = 199;

    public interface DiPlayStateListener {
        void onDiPlayServiceBound(boolean bound);
        void onDiPlayAttached(boolean active);
        void onDiPlayStreamState(boolean streaming);
        void onDiPlayError(String error);
    }

    private final Context context;
    private final KoveClusterServer clusterServer;
    private final CarPlayVirtualPipeline virtualPipeline;
    private DiPlayStateListener listener;

    private Messenger serviceMessenger;
    private boolean isBound = false;
    private VirtualDisplay virtualDisplay;
    private volatile boolean isAttached = false;
    private volatile boolean isStreaming = false;

    private final Messenger clientMessenger = new Messenger(new Handler(Looper.getMainLooper(), this::handleDiPlayMessage));

    public DiPlayBridge(Context context, KoveClusterServer clusterServer, CarPlayVirtualPipeline virtualPipeline) {
        this.context = context.getApplicationContext();
        this.clusterServer = clusterServer;
        this.virtualPipeline = virtualPipeline;
    }

    public void setListener(DiPlayStateListener listener) {
        this.listener = listener;
    }

    public boolean isAttached() {
        return isAttached;
    }

    public boolean isStreaming() {
        return isStreaming;
    }

    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isBound) {
                bindDiPlayService();
            }
            reconnectHandler.postDelayed(this, 3000);
        }
    };

    public synchronized void start() {
        Log.i(TAG, "Starting DiPlay Software Wireless CarPlay Bridge...");
        reconnectHandler.post(reconnectRunnable);
    }

    public synchronized void bindDiPlayService() {
        if (isBound) return;

        Intent intent = new Intent(ACTION_EMBED_MAP);
        intent.setPackage(context.getPackageName());
        intent.setComponent(new ComponentName(context.getPackageName(), "com.shilapi.xcertplay.MapEmbedService"));

        try {
            boolean ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bindService to embedded MapEmbedService result: " + ok);
            if (!ok && listener != null) {
                listener.onDiPlayError("内置 DiPlay 地图服务初始化中...");
            }
        } catch (Exception e) {
            Log.w(TAG, "Error binding to embedded MapEmbedService: " + e.getMessage());
            if (listener != null) listener.onDiPlayError(e.getMessage());
        }
    }

    private SurfaceView previewSurfaceView;
    private final Handler captureHandler = new Handler(Looper.getMainLooper());
    private boolean isCapturing = false;
    private Bitmap captureBitmap = null;

    public synchronized void attachSurfaceView(SurfaceView surfaceView) {
        this.previewSurfaceView = surfaceView;
        Log.i(TAG, "previewSurfaceView registered: " + surfaceView);
        if (isBound && serviceMessenger != null && !isAttached) {
            attachToDiPlay();
        }
    }

    public synchronized void detachSurfaceView() {
        stopCaptureLoop();
        if (serviceMessenger != null && isAttached) {
            try {
                serviceMessenger.send(Message.obtain(null, MSG_DETACH));
            } catch (Exception ignored) {}
        }
        isAttached = false;
        isStreaming = false;
        previewSurfaceView = null;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            serviceMessenger = new Messenger(service);
            isBound = true;
            Log.i(TAG, ">>> Connected to DiPlay MapEmbedService!");
            if (listener != null) listener.onDiPlayServiceBound(true);

            // Once connected, attach if SurfaceView is ready
            attachToDiPlay();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceMessenger = null;
            isBound = false;
            isAttached = false;
            isStreaming = false;
            stopCaptureLoop();
            Log.w(TAG, "DiPlay MapEmbedService disconnected");
            if (listener != null) {
                listener.onDiPlayServiceBound(false);
                listener.onDiPlayAttached(false);
            }
        }
    };

    /**
     * Attaches DiPlay CarPlay MapEmbed stream via SurfaceControlViewHost.
     */
    public synchronized void attachToDiPlay() {
        if (serviceMessenger == null) return;

        IBinder hostToken = null;
        int displayId = 0;

        if (previewSurfaceView != null) {
            hostToken = previewSurfaceView.getHostToken();
            if (previewSurfaceView.getDisplay() != null) {
                displayId = previewSurfaceView.getDisplay().getDisplayId();
            }
        }

        if (hostToken == null) {
            Log.w(TAG, "previewSurfaceView hostToken is not yet ready, will attach when surface is created.");
            return;
        }

        try {
            int reqW = previewSurfaceView.getWidth() > 0 ? previewSurfaceView.getWidth() : KoveClusterServer.WIDTH;
            int reqH = previewSurfaceView.getHeight() > 0 ? previewSurfaceView.getHeight() : KoveClusterServer.HEIGHT;
            Message msg = Message.obtain(null, MSG_ATTACH);
            Bundle data = new Bundle();
            data.putBinder("hostToken", hostToken);
            data.putInt("displayId", displayId);
            data.putInt("width", reqW);
            data.putInt("height", reqH);
            msg.setData(data);
            msg.replyTo = clientMessenger;

            serviceMessenger.send(msg);
            Log.i(TAG, "Sent MSG_ATTACH (what=1) to DiPlay size=" + reqW + "x" + reqH + " displayId=" + displayId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to attach to DiPlay: " + e.getMessage(), e);
            if (listener != null) listener.onDiPlayError("连接 DiPlay 失败: " + e.getMessage());
        }
    }

    private boolean handleDiPlayMessage(Message msg) {
        switch (msg.what) {
            case MSG_ATTACHED: // 101
                isAttached = true;
                Log.i(TAG, ">>> DiPlay MSG_ATTACHED received! CarPlay stream linked.");
                if (msg.getData() != null) {
                    try {
                        SurfaceControlViewHost.SurfacePackage surfacePackage =
                                msg.getData().getParcelable("surfacePackage");
                        if (surfacePackage != null && previewSurfaceView != null) {
                            previewSurfaceView.setChildSurfacePackage(surfacePackage);
                            Log.i(TAG, ">>> Successfully attached SurfacePackage to SurfaceView!");
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Error applying surfacePackage: " + t.getMessage());
                    }

                    boolean streamActive = msg.getData().getBoolean("streamActive", false);
                    isStreaming = streamActive;
                    Log.i(TAG, "DiPlay streamActive=" + streamActive);
                    if (isStreaming && virtualPipeline != null) {
                        startCaptureLoop();
                    }
                }
                if (listener != null) {
                    listener.onDiPlayAttached(true);
                    listener.onDiPlayStreamState(isStreaming);
                }
                break;

            case MSG_STREAM_STATE: // 102
                if (msg.getData() != null) {
                    isStreaming = msg.getData().getBoolean("streamActive", false);
                    Log.i(TAG, "DiPlay MSG_STREAM_STATE: " + isStreaming);
                    if (isStreaming && virtualPipeline != null) {
                        startCaptureLoop();
                    } else {
                        stopCaptureLoop();
                    }
                    if (listener != null) listener.onDiPlayStreamState(isStreaming);
                }
                break;

            case MSG_ERROR: // 199
                String error = "unknown";
                if (msg.getData() != null) {
                    error = msg.getData().getString("error", "unknown");
                }
                Log.w(TAG, "DiPlay returned error: " + error);
                if ("disabled".equals(error)) {
                    Log.i(TAG, "Notice: DiPlay requires '与其他启动器共享实时地图' to be toggled ON in DiPlay settings.");
                }
                if (listener != null) listener.onDiPlayError("DiPlay: " + error);
                break;

            case MSG_DETACH: // 3
                isAttached = false;
                isStreaming = false;
                stopCaptureLoop();
                Log.i(TAG, "DiPlay MSG_DETACH received");
                if (listener != null) {
                    listener.onDiPlayAttached(false);
                    listener.onDiPlayStreamState(false);
                }
                break;

            default:
                break;
        }
        return true;
    }

    private void startCaptureLoop() {
        if (isCapturing || previewSurfaceView == null || virtualPipeline == null) return;
        isCapturing = true;
        if (captureBitmap == null) {
            captureBitmap = Bitmap.createBitmap(KoveClusterServer.WIDTH, KoveClusterServer.HEIGHT, Bitmap.Config.ARGB_8888);
        }
        Log.i(TAG, "Starting PixelCopy CarPlay frame capture loop (30 fps)...");
        scheduleNextFrame();
    }

    private void stopCaptureLoop() {
        isCapturing = false;
        captureHandler.removeCallbacksAndMessages(null);
    }

    private void scheduleNextFrame() {
        if (!isCapturing || previewSurfaceView == null || virtualPipeline == null) return;
        PixelCopy.request(previewSurfaceView, captureBitmap, copyResult -> {
            if (copyResult == PixelCopy.SUCCESS) {
                Surface inputSurface = virtualPipeline.getInputSurface();
                if (inputSurface != null && inputSurface.isValid()) {
                    try {
                        Canvas canvas = inputSurface.lockHardwareCanvas();
                        canvas.drawBitmap(captureBitmap, 0, 0, null);
                        inputSurface.unlockCanvasAndPost(canvas);
                    } catch (Exception ignored) {}
                }
            }
            if (isCapturing) {
                captureHandler.postDelayed(this::scheduleNextFrame, 33);
            }
        }, captureHandler);
    }

    /**
     * Launch embedded DiPlay CarPlayHostActivity (Full Apple CarPlay primary screen).
     */
    public void launchCarPlayHost() {
        try {
            SiQiRelayBridge.launchCarPlayHost(context);
            Log.i(TAG, "Launched embedded CarPlayHostActivity!");
        } catch (Exception e) {
            Log.w(TAG, "Failed to launch embedded CarPlayHostActivity: " + e.getMessage());
            launchDiPlayApp();
        }
    }

    /**
     * Launch embedded DiPlay setup/settings screen (Bluetooth pairing, Wi-Fi Direct, Audio, Credits).
     */
    public void launchDiPlayApp() {
        try {
            SiQiRelayBridge.launchDiPlayHome(context, "home");
        } catch (Exception e) {
            Log.w(TAG, "Failed to launch embedded DiPlay home: " + e.getMessage());
        }
    }

    public synchronized void stop() {
        stopCaptureLoop();
        reconnectHandler.removeCallbacksAndMessages(null);
        if (serviceMessenger != null && isAttached) {
            try {
                Message msg = Message.obtain(null, MSG_DETACH);
                serviceMessenger.send(msg);
            } catch (Exception ignored) {}
        }

        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }

        if (isBound) {
            try { context.unbindService(connection); } catch (Exception ignored) {}
            isBound = false;
        }

        isAttached = false;
        isStreaming = false;
    }
}
