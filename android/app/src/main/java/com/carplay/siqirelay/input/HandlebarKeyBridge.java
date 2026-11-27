package com.carplay.siqirelay.input;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.carplay.siqirelay.carlife.CarLifeLocalReceiver;
import com.carplay.siqirelay.carplay.CarPlayDongleManager;
import com.shilapi.xcertplay.SiQiRelayBridge;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Handlebar Physical Key Bridge.
 * Translates Up/Down/Confirm/Back physical inputs directly into CarPlay Knob/Media/Siri actions
 * via the in-process [SiQiRelayBridge] API (works root-free even while screen is off or backgrounded)
 * plus optional USB Dongle routing.
 */
public class HandlebarKeyBridge {
    private static final String TAG = "HandlebarKeyBridge";

    public enum KeyAction {
        UP("向上 (旋钮前翻)", CarPlayDongleManager.KEY_UP),
        DOWN("向下 (旋钮后翻)", CarPlayDongleManager.KEY_DOWN),
        CONFIRM("确认 (旋钮按下)", CarPlayDongleManager.KEY_SELECT),
        BACK("返回 (上一层)", CarPlayDongleManager.KEY_BACK),
        SIRI("Siri / 语音", CarPlayDongleManager.KEY_SIRI),
        NEXT("下一曲", CarPlayDongleManager.KEY_NEXT),
        PREV("上一曲", CarPlayDongleManager.KEY_PREV),
        UNKNOWN("未知按键", 0);

        public final String title;
        public final int dongleCode;

        KeyAction(String title, int dongleCode) {
            this.title = title;
            this.dongleCode = dongleCode;
        }
    }

    public static class KeyLog {
        public final String time;
        public final KeyAction action;
        public final int rawCode;
        public final boolean isLongPress;

        public KeyLog(KeyAction action, int rawCode, boolean isLongPress) {
            this.time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
            this.action = action;
            this.rawCode = rawCode;
            this.isLongPress = isLongPress;
        }
    }

    public interface OnKeyEventListener {
        void onKeyAction(KeyAction action, int keyCode, boolean isLongPress);
    }

    private static volatile HandlebarKeyBridge instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<OnKeyEventListener> listeners = new CopyOnWriteArrayList<>();
    private final List<KeyLog> history = new CopyOnWriteArrayList<>();

    private volatile CarPlayDongleManager dongleManager;
    private volatile CarLifeLocalReceiver carLifeReceiver;

    private long confirmDownTime = 0;
    private boolean confirmLongHandled = false;

    public static HandlebarKeyBridge getInstance() {
        if (instance == null) {
            synchronized (HandlebarKeyBridge.class) {
                if (instance == null) {
                    instance = new HandlebarKeyBridge();
                }
            }
        }
        return instance;
    }

    private HandlebarKeyBridge() {
        SiQiRelayBridge.setFallbackKeyHandler(this::onKeyEvent);
    }

    public void setDongleManager(CarPlayDongleManager dongleManager) {
        this.dongleManager = dongleManager;
    }

    public void setCarLifeReceiver(CarLifeLocalReceiver receiver) {
        this.carLifeReceiver = receiver;
    }

    public void addListener(OnKeyEventListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(OnKeyEventListener listener) {
        listeners.remove(listener);
    }

    public List<KeyLog> getHistory() {
        return history;
    }

    private boolean hasActiveCarPlayTarget() {
        if (SiQiRelayBridge.hasSession()) return true;
        CarLifeLocalReceiver cl = carLifeReceiver;
        if (cl != null && cl.isConnected()) return true;
        CarPlayDongleManager dm = dongleManager;
        return dm != null && dm.isConnected();
    }

    /**
     * Dispatch raw KeyEvents (from Bluetooth HID button, Gamepad, Keyboard or Activity).
     * Preserves Android system Back navigation when no CarPlay target is active or when the key
     * comes from Android's virtual navigation bar.
     */
    public boolean onKeyEvent(KeyEvent event) {
        if (event == null) return false;

        int keyCode = event.getKeyCode();
        int action = event.getAction();
        KeyAction mapped = mapKeyCode(keyCode);

        if (mapped == KeyAction.UNKNOWN) return false;

        if (!hasActiveCarPlayTarget()) {
            return false;
        }

        if (mapped == KeyAction.BACK && event.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD) {
            return false;
        }

        // Long press detection for Confirm -> Siri
        if (mapped == KeyAction.CONFIRM) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    confirmDownTime = SystemClock.elapsedRealtime();
                    confirmLongHandled = false;
                } else if (!confirmLongHandled && (SystemClock.elapsedRealtime() - confirmDownTime) > 700) {
                    confirmLongHandled = true;
                    dispatchAction(KeyAction.SIRI, keyCode, true);
                    return true;
                }
            } else if (action == KeyEvent.ACTION_UP) {
                if (confirmLongHandled) {
                    confirmLongHandled = false;
                    return true;
                }
                dispatchAction(KeyAction.CONFIRM, keyCode, false);
                return true;
            }
            return true;
        }

        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            dispatchAction(mapped, keyCode, false);
            return true;
        }

        return action == KeyEvent.ACTION_UP;
    }

    public void dispatchAction(KeyAction action, int keyCode, boolean isLongPress) {
        Log.i(TAG, ">>> [HandlebarKey] " + action.title + " (raw=" + keyCode + ", long=" + isLongPress + ")");

        KeyLog log = new KeyLog(action, keyCode, isLongPress);
        history.add(0, log);
        while (history.size() > 30) {
            history.remove(history.size() - 1);
        }

        CarPlayDongleManager dm = dongleManager;
        if (dm != null && action.dongleCode > 0 && dm.isConnected()) {
            dm.sendKey(action.dongleCode);
        }

        boolean dispatchedInProcess = false;
        CarLifeLocalReceiver cl = carLifeReceiver;
        if (cl != null && cl.isConnected()) {
            cl.sendHardKey(action, isLongPress);
            dispatchedInProcess = true;
        }
        if (injectCarPlayAction(action)) {
            dispatchedInProcess = true;
        }
        if (!dispatchedInProcess) {
            Log.d(TAG, "In-process CarPlay/CarLife target inactive for key " + action.title);
        }

        handler.post(() -> {
            for (OnKeyEventListener l : listeners) {
                try { l.onKeyAction(action, keyCode, isLongPress); } catch (Exception ignored) {}
            }
        });
    }

    private boolean injectCarPlayAction(KeyAction action) {
        switch (action) {
            case UP:
                return SiQiRelayBridge.sendKnobWheel(-1);
            case DOWN:
                return SiQiRelayBridge.sendKnobWheel(1);
            case CONFIRM:
                return SiQiRelayBridge.sendKnobSelect();
            case BACK:
                return SiQiRelayBridge.sendKnobBack();
            case SIRI:
                return SiQiRelayBridge.requestSiri();
            case NEXT:
                return SiQiRelayBridge.sendMediaNext();
            case PREV:
                return SiQiRelayBridge.sendMediaPrev();
            default:
                return false;
        }
    }

    private KeyAction mapKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_W:
                return KeyAction.UP;

            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_S:
                return KeyAction.DOWN;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                return KeyAction.CONFIRM;

            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                return KeyAction.BACK;

            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return KeyAction.NEXT;

            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return KeyAction.PREV;

            case KeyEvent.KEYCODE_VOICE_ASSIST:
            case KeyEvent.KEYCODE_SEARCH:
                return KeyAction.SIRI;

            default:
                return KeyAction.UNKNOWN;
        }
    }
}
