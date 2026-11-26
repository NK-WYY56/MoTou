package com.carplay.siqirelay.kove;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * BLE Scanner and Manager targeting Kove / 思骑仪表 (CQKY_xxxx / Thinkerride).
 */
public class KoveBleScanner {
    private static final String TAG = "KoveBleScanner";

    public interface BleListener {
        void onClusterBleConnected(String name, String mac);
        void onClusterBleDisconnected();
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BleListener listener;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private volatile boolean isScanning = false;
    private volatile boolean isConnected = false;
    private volatile boolean paused = false;
    private volatile boolean stopped = false;

    public KoveBleScanner(Context context) {
        this.context = context.getApplicationContext();
        android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            bluetoothAdapter = bm.getAdapter();
        }
    }

    public void setListener(BleListener listener) {
        this.listener = listener;
    }

    public boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    public synchronized void start() {
        stopped = false;
        paused = false;
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        if (!hasBlePermissions()) {
            Log.i(TAG, "Waiting for Bluetooth runtime permissions before starting cluster BLE scan.");
            return;
        }
        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        startScan();
    }

    public synchronized void pauseScan() {
        paused = true;
        handler.removeCallbacksAndMessages(null);
        stopScan();
    }

    public synchronized void resumeScan() {
        paused = false;
        if (!stopped && !isConnected) {
            startScan();
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void startScan() {
        if (stopped || paused || isScanning || isConnected || bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        if (bleScanner == null) {
            bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        }
        if (bleScanner == null || !hasBlePermissions()) return;
        isScanning = true;
        Log.i(TAG, "Starting low-power BLE scan for cluster...");
        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .build();
            bleScanner.startScan(null, settings, scanCallback);
        } catch (SecurityException se) {
            isScanning = false;
            Log.w(TAG, "BLE scan skipped (permission missing): " + se.getMessage());
        } catch (Exception e) {
            isScanning = false;
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (!isScanning || bleScanner == null) return;
        isScanning = false;
        try {
            bleScanner.stopScan(scanCallback);
        } catch (Exception ignored) {}
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;

            String name = (result.getScanRecord() != null) ? result.getScanRecord().getDeviceName() : null;
            if (name == null && hasBlePermissions()) {
                try {
                    name = device.getName();
                } catch (SecurityException ignored) {
                    return;
                }
            }
            String mac = device.getAddress();

            if (name != null && (name.startsWith("CQKY_") || name.contains("Thinker") || name.contains("Siki"))) {
                Log.i(TAG, ">>> Found Target Bike Cluster BLE: " + name + " [" + mac + "]");
                stopScan();
                connectDevice(device, name);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            isScanning = false;
            Log.w(TAG, "BLE scan failed (code=" + errorCode + "); backing off 10s");
            if (!stopped && !paused) {
                handler.postDelayed(() -> startScan(), 10_000);
            }
        }
    };

    @SuppressLint("MissingPermission")
    private synchronized void connectDevice(BluetoothDevice device, String deviceName) {
        if (stopped || !hasBlePermissions()) return;
        closeGattQuietly(bluetoothGatt);
        bluetoothGatt = null;
        try {
            bluetoothGatt = device.connectGatt(context, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        isConnected = true;
                        Log.i(TAG, "Cluster BLE Connected: " + deviceName);
                        if (listener != null) listener.onClusterBleConnected(deviceName, device.getAddress());
                        try { gatt.discoverServices(); } catch (SecurityException ignored) {}
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        isConnected = false;
                        Log.i(TAG, "Cluster BLE Disconnected");
                        closeGattQuietly(gatt);
                        synchronized (KoveBleScanner.this) {
                            if (bluetoothGatt == gatt) {
                                bluetoothGatt = null;
                            }
                        }
                        if (listener != null) listener.onClusterBleDisconnected();
                        if (!stopped) {
                            handler.postDelayed(() -> startScan(), 3000);
                        }
                    }
                }
            });
        } catch (SecurityException se) {
            Log.w(TAG, "connectGatt permission missing: " + se.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    private void closeGattQuietly(BluetoothGatt gatt) {
        if (gatt == null) return;
        try { gatt.disconnect(); } catch (Exception ignored) {}
        try { gatt.close(); } catch (Exception ignored) {}
    }

    @SuppressLint("MissingPermission")
    public synchronized void stop() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        stopScan();
        closeGattQuietly(bluetoothGatt);
        bluetoothGatt = null;
        isConnected = false;
    }
}
