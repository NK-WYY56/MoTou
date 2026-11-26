package com.carplay.siqirelay;

import android.Manifest;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

/**
 * Auto-starts CarPlay SiQi Gateway BridgeService on device boot or app update,
 * falling back to a one-tap launch notification on Android 14+ when BOOT_COMPLETED FGS is restricted.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    private static final String BOOT_CHANNEL_ID = "carplay_siqi_boot_channel";
    private static final int BOOT_NOTIF_ID = 4502;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Log.i(TAG, "System trigger detected (" + action + "), starting BridgeService...");
            Intent sIntent = new Intent(context, BridgeService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(sIntent);
                } else {
                    context.startService(sIntent);
                }
            } catch (Exception e) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && e instanceof ForegroundServiceStartNotAllowedException) {
                    Log.w(TAG, "Android 12+/14+ withheld BOOT_COMPLETED FGS launch; posting 1-tap notification");
                    postBootReadyNotification(context);
                } else {
                    Log.w(TAG, "Error starting BridgeService on boot: " + e.getMessage());
                }
            }
        }
    }

    private void postBootReadyNotification(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(
                        BOOT_CHANNEL_ID, "摩投 开机启动提示", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent openIntent = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(context, BOOT_CHANNEL_ID)
                    : new Notification.Builder(context);
            Notification notif = builder
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("摩投 待就绪")
                    .setContentText("受系统后台限制，点击此处即可启动无线 CarPlay 互联")
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            nm.notify(BOOT_NOTIF_ID, notif);
        } catch (Exception ignored) {}
    }
}
