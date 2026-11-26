package com.carplay.siqirelay.carplay;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.carplay.siqirelay.kove.KoveClusterServer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;

/**
 * Direct USB Host Driver for Carlinkit / AutoKit / CPC200 CarPlay Dongles.
 * Reads H.264 video stream over USB Bulk endpoints and routes directly into KoveClusterServer.
 * Injects Handlebar Knob / D-pad Key commands back to the iPhone.
 */
public class CarPlayDongleManager {
    private static final String TAG = "CarPlayDongleMgr";
    private static final String ACTION_USB_PERMISSION = "com.nkwyy56.motou.USB_PERMISSION";

    public static final int CARLINKIT_VID = 0x1314;
    public static final int MAGIC_HEADER = 0x55AA55AA;

    public static final int KEY_UP = 1;
    public static final int KEY_DOWN = 2;
    public static final int KEY_SELECT = 3;
    public static final int KEY_BACK = 4;
    public static final int KEY_SIRI = 5;
    public static final int KEY_PREV = 6;
    public static final int KEY_NEXT = 7;

    public interface DongleStateListener {
        void onDongleConnected(String deviceName);
        void onDongleDisconnected();
        void onCarPlayVideoStreaming(boolean active);
        void onCarPlaySessionState(String state);
    }

    private final Context context;
    private final KoveClusterServer clusterServer;
    private final UsbManager usbManager;
    private DongleStateListener listener;

    private UsbDevice currentDevice;
    private UsbDeviceConnection usbConnection;
    private UsbInterface claimedInterface;
    private UsbEndpoint endpointIn;
    private UsbEndpoint endpointOut;

    private volatile boolean isRunning = false;
    private Thread usbReadThread;

    public synchronized boolean isConnected() {
        return usbConnection != null && endpointIn != null && endpointOut != null;
    }

    public CarPlayDongleManager(Context context, KoveClusterServer clusterServer) {
        this.context = context.getApplicationContext();
        this.clusterServer = clusterServer;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void setListener(DongleStateListener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, filter);
        }

        scanUsbDevices();
        Log.i(TAG, "CarPlayDongleManager started, listening for USB CarPlay modules.");
    }

    public void scanUsbDevices() {
        if (usbManager == null) return;
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            if (isCarPlayDongle(device)) {
                Log.i(TAG, "Found CarPlay Dongle: " + device.getDeviceName() + " (VID: 0x" + Integer.toHexString(device.getVendorId()) + ")");
                requestPermissionOrConnect(device);
                return;
            }
        }
    }

    private boolean isCarPlayDongle(UsbDevice device) {
        int vid = device.getVendorId();
        // Carlinkit / AutoKit / CPC200 USB CarPlay dongles (avoid claiming generic USB hubs/adapters)
        return vid == CARLINKIT_VID;
    }

    private void requestPermissionOrConnect(UsbDevice device) {
        if (usbManager.hasPermission(device)) {
            connectDongle(device);
        } else {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
            Intent permIntent = new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, permIntent, flags);
            usbManager.requestPermission(device, pi);
        }
    }

    private synchronized void connectDongle(UsbDevice device) {
        disconnectDongle();
        currentDevice = device;
        UsbInterface usbIf = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getEndpointCount() >= 2) {
                usbIf = iface;
                break;
            }
        }

        if (usbIf == null) {
            Log.e(TAG, "No suitable USB interface with endpoints found on dongle");
            return;
        }

        usbConnection = usbManager.openDevice(device);
        if (usbConnection == null) {
            Log.e(TAG, "Failed to open USB device connection");
            return;
        }

        if (!usbConnection.claimInterface(usbIf, true)) {
            Log.e(TAG, "Failed to claim USB interface on dongle");
            usbConnection.close();
            usbConnection = null;
            return;
        }
        claimedInterface = usbIf;

        for (int i = 0; i < usbIf.getEndpointCount(); i++) {
            UsbEndpoint ep = usbIf.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    endpointIn = ep;
                } else {
                    endpointOut = ep;
                }
            }
        }

        if (endpointIn == null || endpointOut == null) {
            Log.e(TAG, "Bulk endpoints not located on USB interface");
            disconnectDongle();
            return;
        }

        Log.i(TAG, ">>> CarPlay USB Dongle initialized successfully! Starting video reader thread...");
        if (listener != null) {
            listener.onDongleConnected(device.getDeviceName());
            listener.onCarPlaySessionState("USB盒子已就绪，等待 iPhone 连接...");
        }

        startReaderThread();
    }

    private void startReaderThread() {
        usbReadThread = new Thread(() -> {
            byte[] headerBuf = new byte[16];
            byte[] packetBuf = new byte[131072];
            int consecutiveFastErrors = 0;

            while (isRunning && usbConnection != null && endpointIn != null && !Thread.currentThread().isInterrupted()) {
                // 1. Read packet header (16 bytes: Magic 4B, Length 4B, Type 4B, Flags 4B)
                long t0 = SystemClock.elapsedRealtime();
                int read = usbConnection.bulkTransfer(endpointIn, headerBuf, 16, 2000);
                if (read < 0) {
                    long elapsed = SystemClock.elapsedRealtime() - t0;
                    if (elapsed < 100) {
                        consecutiveFastErrors++;
                        if (consecutiveFastErrors >= 5) {
                            Log.w(TAG, "USB dongle bulkTransfer failing rapidly; closing session cleanly");
                            disconnectDongle();
                            break;
                        }
                        try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                    } else {
                        consecutiveFastErrors = 0;
                    }
                    continue;
                }
                if (read == 0) {
                    consecutiveFastErrors = 0;
                    continue;
                }
                consecutiveFastErrors = 0;

                if (read == 16) {
                    ByteBuffer bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN);
                    int magic = bb.getInt();
                    int length = bb.getInt();
                    int type = bb.getInt();

                    if (magic == MAGIC_HEADER && length > 0 && length < packetBuf.length) {
                        // 2. Read payload body
                        int bytesRead = 0;
                        while (bytesRead < length && isRunning) {
                            int r = usbConnection.bulkTransfer(endpointIn, packetBuf, bytesRead, length - bytesRead, 1000);
                            if (r <= 0) break;
                            bytesRead += r;
                        }

                        if (bytesRead == length) {
                            handleDonglePacket(type, packetBuf, length);
                        }
                    }
                }
            }
        }, "CarPlayUsbReader");
        usbReadThread.start();
    }

    private void handleDonglePacket(int type, byte[] data, int length) {
        // Type 1 = H.264 Video Stream NALU
        if (type == 1) {
            boolean isKeyframe = false;
            if (length > 4) {
                int nalType = data[4] & 0x1F;
                if (nalType == 5 || nalType == 7) {
                    isKeyframe = true;
                }
            }
            // Stream directly to Kove 450RR cluster port 15456!
            clusterServer.sendH264Data(data, 0, length, isKeyframe);

            if (listener != null) {
                listener.onCarPlayVideoStreaming(true);
            }
        } else if (type == 4) {
            // Control status (e.g. iPhone attached / wireless CarPlay started)
            String msg = new String(data, 0, length);
            Log.i(TAG, "CarPlay Dongle Status: " + msg);
            if (listener != null) {
                listener.onCarPlaySessionState(msg);
            }
        }
    }

    /**
     * Injects Handlebar key press (Knob Up, Down, Select, Back) to iPhone via USB
     */
    public synchronized void sendKey(int keyAction) {
        if (usbConnection == null || endpointOut == null) {
            Log.w(TAG, "Cannot sendKey: USB Dongle connection not ready");
            return;
        }

        try {
            // Format AutoKit Key Packet
            byte[] packet = new byte[20];
            ByteBuffer bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(MAGIC_HEADER);
            bb.putInt(4); // Length = 4 bytes
            bb.putInt(3); // Type = 3 (Key / Touch)
            bb.putInt(0); // Flags
            bb.putInt(keyAction); // Action code

            usbConnection.bulkTransfer(endpointOut, packet, packet.length, 500);
            Log.i(TAG, ">>> Injected Handlebar Key to iPhone: " + keyAction);
        } catch (Exception e) {
            Log.w(TAG, "Failed to send key to dongle: " + e.getMessage());
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (CarPlayDongleManager.this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) connectDongle(device);
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                scanUsbDevices();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice detached = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                synchronized (CarPlayDongleManager.this) {
                    if (detached == null || currentDevice == null || detached.equals(currentDevice)) {
                        disconnectDongle();
                    }
                }
            }
        }
    };

    public synchronized void disconnectDongle() {
        if (usbReadThread != null) {
            usbReadThread.interrupt();
            usbReadThread = null;
        }
        if (usbConnection != null) {
            if (claimedInterface != null) {
                try { usbConnection.releaseInterface(claimedInterface); } catch (Exception ignored) {}
                claimedInterface = null;
            }
            try { usbConnection.close(); } catch (Exception ignored) {}
            usbConnection = null;
        } else {
            claimedInterface = null;
        }
        currentDevice = null;
        endpointIn = null;
        endpointOut = null;
        if (listener != null) {
            listener.onDongleDisconnected();
            listener.onCarPlayVideoStreaming(false);
        }
    }

    public synchronized void stop() {
        isRunning = false;
        try { context.unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        disconnectDongle();
    }
}
