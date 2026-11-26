package com.carplay.siqirelay.carplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.shilapi.xcertplay.SiQiRelayBridge;

/**
 * Configures embedded DiPlay AirPlay parameters directly in-process via SharedPreferences
 * (bike cluster model preset, resolution scale, physical width mm for icon sizing,
 * square-corner full-bleed mode, audio headset routing, and OEM identity).
 */
public class DiPlayConfigOptimizer {
    private static final String TAG = "DiPlayConfigOptimizer";
    private static final String DIPLAY_PREFS = "xcertplay_airplay";
    private static final String LOCAL_PREF = "carplay_optimizer_prefs";
    private static final String KEY_BIKE_MODEL = "bike_model_preset";
    private static final String KEY_ICON_MODE = "icon_size_mode";
    private static final String KEY_DEFAULTS_SEEDED = "defaults_seeded_v7";
    private static final String KEY_AUDIO_HEADSET_SEEDED = "audio_headset_default_seeded_v1";
    private static final String KEY_CAR_BLUETOOTH_AUDIO = "car_bluetooth_audio";

    // Bike cluster model presets (add future motorcycle cluster profiles here)
    public static final int MODEL_KOVE_450RR = 0;

    // Icon size presets (on 450RR 2560x1120 base: 1024x448 / 1280x560 / 1536x672, all 16px-aligned)
    public static final int ICON_MODE_XL = 0;       // 40%, 100mm -> Largest icons & lowest latency
    public static final int ICON_MODE_LARGE = 1;    // 50%, 100mm -> Default recommended (crisp large icons)
    public static final int ICON_MODE_STANDARD = 2; // 60%, 200mm -> Smaller 5-col grid

    public static final class BikePresetSpec {
        public final int id;
        public final String title;
        public final String description;
        public final int baseWidth;
        public final int baseHeight;
        public final int viewportTop;
        public final int viewportHeight;
        public final float overscanX;
        public final boolean disableSafeAreaSquareCorners;
        public final String oemLabel;
        public final String manufacturer;
        public final String modelName;

        BikePresetSpec(
                int id,
                String title,
                String description,
                int baseWidth,
                int baseHeight,
                int viewportTop,
                int viewportHeight,
                float overscanX,
                boolean disableSafeAreaSquareCorners,
                String oemLabel,
                String manufacturer,
                String modelName
        ) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.baseWidth = baseWidth;
            this.baseHeight = baseHeight;
            this.viewportTop = viewportTop;
            this.viewportHeight = viewportHeight;
            this.overscanX = overscanX;
            this.disableSafeAreaSquareCorners = disableSafeAreaSquareCorners;
            this.oemLabel = oemLabel;
            this.manufacturer = manufacturer;
            this.modelName = modelName;
        }
    }

    /**
     * Registered bike cluster presets. Additional models can be appended here later and will
     * automatically appear in the Settings secondary picker menu.
     */
    private static final BikePresetSpec[] PRESETS = new BikePresetSpec[]{
            new BikePresetSpec(
                    MODEL_KOVE_450RR,
                    "凯越 450RR",
                    "直角无圆角黑边 · 四边等距留白 · 1:1 图标比例矫正",
                    2560, 1120,
                    78, 484,
                    1.000f,
                    true,
                    "KOVE", "Kove", "Kove-450RR-CarPlay"
            )
    };

    public static BikePresetSpec[] getAllPresets() {
        return PRESETS.clone();
    }

    public static BikePresetSpec getPresetSpec(int modelId) {
        if (modelId >= 0 && modelId < PRESETS.length) {
            return PRESETS[modelId];
        }
        return PRESETS[MODEL_KOVE_450RR];
    }

    public static BikePresetSpec getActivePresetSpec(Context ctx) {
        return getPresetSpec(getBikeModelPreset(ctx));
    }

    public static int getBikeModelPreset(Context ctx) {
        int val = ctx.getSharedPreferences(LOCAL_PREF, Context.MODE_PRIVATE)
                .getInt(KEY_BIKE_MODEL, MODEL_KOVE_450RR);
        return (val >= 0 && val < PRESETS.length) ? val : MODEL_KOVE_450RR;
    }

    public static void setBikeModelPreset(Context ctx, int modelId) {
        int safeId = (modelId >= 0 && modelId < PRESETS.length) ? modelId : MODEL_KOVE_450RR;
        ctx.getSharedPreferences(LOCAL_PREF, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_BIKE_MODEL, safeId)
                .apply();
    }

    public static int getIconSizeMode(Context ctx) {
        return ctx.getSharedPreferences(LOCAL_PREF, Context.MODE_PRIVATE).getInt(KEY_ICON_MODE, ICON_MODE_LARGE);
    }

    public static void setIconSizeMode(Context ctx, int mode) {
        ctx.getSharedPreferences(LOCAL_PREF, Context.MODE_PRIVATE).edit().putInt(KEY_ICON_MODE, mode).apply();
    }

    /** True when CarPlay audio & Siri stay on iPhone's Bluetooth headset instead of playing on Android phone. */
    public static boolean isHeadsetAudioDefault(Context ctx) {
        SharedPreferences localPrefs = ctx.getSharedPreferences(LOCAL_PREF, Context.MODE_PRIVATE);
        SharedPreferences prefs = ctx.getSharedPreferences(DIPLAY_PREFS, Context.MODE_PRIVATE);
        if (!localPrefs.getBoolean(KEY_AUDIO_HEADSET_SEEDED, false)) {
            return true;
        }
        return prefs.getBoolean(KEY_CAR_BLUETOOTH_AUDIO, true);
    }

    public static void setHeadsetAudioDefault(Context ctx, boolean enabled, boolean restartIfRunning) {
        if (ctx == null) return;
        ctx.getSharedPreferences(DIPLAY_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CAR_BLUETOOTH_AUDIO, enabled)
                .commit();
        ctx.getSharedPreferences(LOCAL_PREF, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUDIO_HEADSET_SEEDED, true)
                .apply();
        if (restartIfRunning) {
            try {
                SiQiRelayBridge.restartIfRunning(ctx);
            } catch (Exception e) {
                Log.w(TAG, "Audio routing restart notice: " + e.getMessage());
            }
        }
    }

    public static boolean applyOptimizedConfig() {
        return false;
    }

    public static synchronized boolean applyOptimizedConfig(Context ctx, boolean forceRestartCarPlay) {
        return applyOptimizedConfig(ctx, forceRestartCarPlay, forceRestartCarPlay);
    }

    public static synchronized boolean applyOptimizedConfig(
            Context ctx,
            boolean forceApplyPresets,
            boolean forceRestartCarPlay
    ) {
        if (ctx == null) return false;

        SharedPreferences localPrefs = ctx.getSharedPreferences(LOCAL_PREF, Context.MODE_PRIVATE);
        SharedPreferences prefs = ctx.getSharedPreferences(DIPLAY_PREFS, Context.MODE_PRIVATE);
        boolean firstV5Seed = !localPrefs.getBoolean(KEY_DEFAULTS_SEEDED, false);

        // Always keep virtual-screen offscreen streaming enabled
        if (!SiQiRelayBridge.isVirtualScreenEnabled(ctx)) {
            SiQiRelayBridge.setVirtualScreenEnabled(ctx, true);
        }

        BikePresetSpec preset = getActivePresetSpec(ctx);
        int iconMode = getIconSizeMode(ctx);

        int scaleTenths;
        int scalePercent;
        int physicalMm;
        switch (iconMode) {
            case ICON_MODE_XL:
                scaleTenths = 4;
                scalePercent = 40;
                physicalMm = 100;
                break;
            case ICON_MODE_STANDARD:
                scaleTenths = 6;
                scalePercent = 60;
                physicalMm = 200;
                break;
            case ICON_MODE_LARGE:
            default:
                scaleTenths = 5;
                scalePercent = 50;
                physicalMm = 100;
                break;
        }

        String presetSafeKey = "safe_area_" + preset.baseWidth + "x" + preset.baseHeight;
        String presetSafeVal = "0,0," + preset.baseWidth + "," + preset.baseHeight;

        SharedPreferences.Editor editor = prefs.edit();
        boolean shouldWriteDisplayPresets = forceApplyPresets || firstV5Seed;
        if (shouldWriteDisplayPresets) {
            SiQiRelayBridge.setVirtualDisplayProfile(ctx, preset.baseWidth, preset.baseHeight, 0, preset.baseHeight);

            editor.putInt("display_scale_tenths", scaleTenths);
            editor.putInt("display_scale_percent", scalePercent);
            editor.putInt("display_width_physical_mm", physicalMm);
            editor.putInt("display_fps", 30);
            editor.putBoolean("hevc_enabled", false);
            editor.putBoolean("hevc_software_decoder_enabled", false);
            editor.putBoolean("direct_video_output", true);
            editor.putBoolean("main_display_disable_safe_area", preset.disableSafeAreaSquareCorners);
            editor.putInt("black_corner_radius_dp", 0);
            editor.putBoolean("safe_area_draw_outside", true);
            editor.putString(presetSafeKey, presetSafeVal);

            editor.putBoolean("wireless_enabled", true);
            editor.putString("wireless_hotspot_mode", "WIFI_P2P");
            editor.putString("mfi_target", "LOCAL");
            editor.putBoolean("launcher_map_sharing", true);
            editor.putBoolean("cluster_map_enabled", true);

            editor.putString("oem_label", preset.oemLabel);
            editor.putString("manufacturer", preset.manufacturer);
            editor.putString("model", preset.modelName);
        }

        // Seed once on upgrade so iPhone audio + Siri route to iPhone Bluetooth headset rather than phone speakers
        boolean seedHeadsetAudio = !localPrefs.getBoolean(KEY_AUDIO_HEADSET_SEEDED, false);
        if (seedHeadsetAudio) {
            editor.putBoolean(KEY_CAR_BLUETOOTH_AUDIO, true);
        }

        String curCarBrandBadge = prefs.getString("car_brand_badge", "");
        if (curCarBrandBadge == null || curCarBrandBadge.trim().isEmpty() || "byd_orig".equals(curCarBrandBadge)) {
            editor.putString("car_brand_badge", "default");
        }
        if (prefs.contains("car_brand")) {
            editor.remove("car_brand");
        }

        boolean saved = editor.commit();
        if (saved && (shouldWriteDisplayPresets || seedHeadsetAudio)) {
            SharedPreferences.Editor localEd = localPrefs.edit();
            if (shouldWriteDisplayPresets) {
                localEd.putBoolean(KEY_DEFAULTS_SEEDED, true);
            }
            if (seedHeadsetAudio) {
                localEd.putBoolean(KEY_AUDIO_HEADSET_SEEDED, true);
            }
            localEd.apply();
        }
        Log.i(TAG, "Applied display preset (saved=" + saved
                + ", model=" + preset.title + " [" + preset.baseWidth + "x" + preset.baseHeight + "]"
                + ", viewport=top:" + preset.viewportTop + ",h:" + preset.viewportHeight
                + ", overscanX=" + preset.overscanX
                + ", squareCorners=" + preset.disableSafeAreaSquareCorners
                + ", scale=" + scalePercent + "%, mm=" + physicalMm + ")");

        com.carplay.siqirelay.BridgeService svc = com.carplay.siqirelay.BridgeService.getInstance();
        if (svc != null) {
            svc.notifyBorderPresetUpdated();
        }

        if (forceRestartCarPlay) {
            try {
                SiQiRelayBridge.restartIfRunning(ctx);
            } catch (Exception e) {
                Log.w(TAG, "Clean CarPlay restart notice: " + e.getMessage());
            }
        }
        return saved;
    }
}
