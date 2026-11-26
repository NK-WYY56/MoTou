package com.carplay.siqirelay.input;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.view.InputDevice;

import com.shilapi.xcertplay.SiQiRelayBridge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Inspects external physical Bluetooth/HID handlebar controllers and background accessibility
 * key-forwarding status for the simplified controller flow.
 */
public final class BluetoothControllerHelper {

    public static final class Snapshot {
        public final boolean connected;
        public final String controllerName;
        public final int pairedCandidateCount;
        public final boolean globalKeyServiceActive;

        Snapshot(boolean connected, String controllerName, int pairedCandidateCount, boolean globalKeyServiceActive) {
            this.connected = connected;
            this.controllerName = controllerName;
            this.pairedCandidateCount = pairedCandidateCount;
            this.globalKeyServiceActive = globalKeyServiceActive;
        }
    }

    private BluetoothControllerHelper() {}

    public static Snapshot inspect(Context context) {
        List<String> connectedInputs = findExternalKeyDevices(context);
        List<String> btConnectedHid = new ArrayList<>();
        int pairedCount = 0;

        boolean hasBtPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        if (hasBtPerm) {
            try {
                BluetoothManager bm = context.getSystemService(BluetoothManager.class);
                BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
                if (adapter != null && adapter.isEnabled()) {
                    @SuppressLint("MissingPermission")
                    Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                    if (bonded != null) {
                        for (BluetoothDevice dev : bonded) {
                            @SuppressLint("MissingPermission")
                            String name = dev.getName();
                            boolean likelyPhoneOrAudio = isLikelyNonControllerName(name);
                            if (!likelyPhoneOrAudio) {
                                pairedCount++;
                            }
                            if (isBluetoothDeviceConnected(dev) && !likelyPhoneOrAudio) {
                                btConnectedHid.add(name != null && !name.isEmpty() ? name : dev.getAddress());
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        boolean connected = !connectedInputs.isEmpty() || !btConnectedHid.isEmpty();
        String displayName = !connectedInputs.isEmpty()
                ? connectedInputs.get(0)
                : (!btConnectedHid.isEmpty() ? btConnectedHid.get(0) : null);

        return new Snapshot(
                connected,
                displayName,
                pairedCount,
                SiQiRelayBridge.isGlobalWheelKeyServiceConnected()
        );
    }

    private static List<String> findExternalKeyDevices(Context context) {
        List<String> names = new ArrayList<>();
        try {
            InputManager im = context.getSystemService(InputManager.class);
            if (im == null) return names;
            for (int id : im.getInputDeviceIds()) {
                InputDevice dev = im.getInputDevice(id);
                if (dev == null || dev.isVirtual()) continue;
                int sources = dev.getSources();
                boolean hasKeySource = (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
                        || (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                        || ((sources & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
                        && dev.getKeyboardType() != InputDevice.KEYBOARD_TYPE_NONE);
                if (!hasKeySource) continue;
                String name = dev.getName();
                if (!isBuiltInHardwareName(name) && (isExternalDevice(dev) || isLikelyControllerName(name))) {
                    names.add(name != null && !name.isEmpty() ? name : "蓝牙按键控制器");
                }
            }
        } catch (Throwable ignored) {}
        return names;
    }

    private static boolean isExternalDevice(InputDevice dev) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return dev.isExternal();
        }
        return false;
    }

    private static boolean isBuiltInHardwareName(String raw) {
        if (raw == null) return false;
        String lower = raw.toLowerCase();
        return lower.contains("gpio")
                || lower.contains("qpnp")
                || lower.contains("pmic")
                || lower.contains("pwrkey")
                || lower.contains("headset")
                || lower.contains("jack")
                || lower.contains("touch")
                || lower.contains("fingerprint")
                || lower.contains("hall")
                || lower.contains("uinput");
    }

    private static boolean isLikelyNonControllerName(String raw) {
        if (raw == null) return false;
        String lower = raw.toLowerCase();
        return lower.contains("iphone")
                || lower.contains("airpods")
                || lower.contains("buds")
                || lower.contains("watch")
                || lower.contains("cardio")
                || lower.contains("cqky");
    }

    private static boolean isLikelyControllerName(String raw) {
        if (raw == null) return false;
        String lower = raw.toLowerCase();
        return lower.contains("hid")
                || lower.contains("remote")
                || lower.contains("key")
                || lower.contains("bt")
                || lower.contains("controller")
                || lower.contains("gamepad")
                || lower.contains("mediacontrol");
    }

    private static boolean isBluetoothDeviceConnected(BluetoothDevice device) {
        try {
            Method m = BluetoothDevice.class.getMethod("isConnected");
            Object res = m.invoke(device);
            return Boolean.TRUE.equals(res);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
