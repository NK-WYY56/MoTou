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
    private static final String KEY_SOURCE_MODE = "projection_source_mode_v1";
    private static final String KEY_CUSTOM_BIKE_NAME = "custom_bike_name";
    private static final String KEY_CUSTOM_STREAM_W = "custom_stream_w";
    private static final String KEY_CUSTOM_STREAM_H = "custom_stream_h";
    private static final String KEY_CUSTOM_CANVAS_W = "custom_canvas_w";
    private static final String KEY_CUSTOM_CANVAS_H = "custom_canvas_h";
    private static final String KEY_CUSTOM_VP_TOP = "custom_vp_top";
    private static final String KEY_CUSTOM_VP_H = "custom_vp_h";
    private static final String KEY_CUSTOM_PHYSICAL_MM = "custom_physical_mm";
    private static final String KEY_CUSTOM_SQUARE_CORNERS = "custom_square_corners";

    // Projection source modes (keeps wireless CarPlay relay default, adds local CarLife mode)
    public static final int SOURCE_MODE_CARPLAY = 0;
    public static final int SOURCE_MODE_CARLIFE = 1;

    // Bike cluster model presets (add future motorcycle cluster profiles here)
    public static final int MODEL_KOVE_450RR = 0;
    public static final int MODEL_CUSTOM = 1;

    // Icon size presets (on 450RR 2560x1120 base: 1024x448 / 1280x560 / 1536x672, all 16px-aligned)
    public static final int ICON_MODE_XL = 0;       // 40%, 100mm -> Largest icons & lowest latency
    public static final int ICON_MODE_LARGE = 1;    // 50%, 100mm -> Default recommended (crisp large icons)
    public static final int ICON_MODE_STANDARD = 2; // 60%, 200mm -> Smaller 5-col grid

    public static final class BikePresetSpec {
        public final int id;
        public final String title;
        public final String description;
        public final int streamWidth;
        public final int streamHeight;
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
                int streamWidth,
                int streamHeight,
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
            this.streamWidth = streamWidth;
            this.streamHeight = streamHeight;
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

    public static final class CustomTemplate {
        public final String label;
        public final CustomDisplayProfile profile;

        public CustomTemplate(String label, CustomDisplayProfile profile) {
            this.label = label;
            this.profile = profile;
        }
    }

    /**
     * User-tunable display profile for non-450RR Thinkerride (SiQi) clusters to fix black screens
     * or aspect-ratio stretching, matching iOS SiqiCustomDisplayProfile.
     */
    public static final class CustomDisplayProfile {
        public String bikeName;
        public int streamWidth;
        public int streamHeight;
        public int canvasWidth;
        public int canvasHeight;
        public int viewportTop;
        public int viewportHeight;
        public int widthPhysicalMM;
        public boolean disableSafeAreaSquareCorners;

        public CustomDisplayProfile(
                String bikeName,
                int streamWidth,
                int streamHeight,
                int canvasWidth,
                int canvasHeight,
                int viewportTop,
                int viewportHeight,
                int widthPhysicalMM,
                boolean disableSafeAreaSquareCorners
        ) {
            this.bikeName = bikeName;
            this.streamWidth = streamWidth;
            this.streamHeight = streamHeight;
            this.canvasWidth = canvasWidth;
            this.canvasHeight = canvasHeight;
            this.viewportTop = viewportTop;
            this.viewportHeight = viewportHeight;
            this.widthPhysicalMM = widthPhysicalMM;
            this.disableSafeAreaSquareCorners = disableSafeAreaSquareCorners;
        }

        public static CustomDisplayProfile kove450RRDefault() {
            return new CustomDisplayProfile("凯越 450RR", 1280, 640, 1280, 560, 78, 484, 100, true);
        }

        public static final CustomTemplate[] COMMON_TEMPLATES = new CustomTemplate[]{
                new CustomTemplate("800×480（5寸常见）",
                        new CustomDisplayProfile("思骑 5寸 800×480", 800, 480, 800, 480, 0, 480, 120, true)),
                new CustomTemplate("1024×600（6.2寸）",
                        new CustomDisplayProfile("思骑 6.2寸 1024×600", 1024, 600, 1024, 600, 0, 600, 140, true)),
                new CustomTemplate("1280×640（450RR）", kove450RRDefault()),
                new CustomTemplate("1280×480（宽屏）",
                        new CustomDisplayProfile("思骑 宽屏 1280×480", 1280, 480, 1280, 480, 0, 480, 140, true)),
                new CustomTemplate("1280×720（720P）",
                        new CustomDisplayProfile("思骑 720P 1280×720", 1280, 720, 1280, 720, 0, 720, 160, true))
        };

        private static int evenClamped(int v, int min, int max) {
            int c = Math.max(min, Math.min(max, v));
            int even = (c / 2) * 2;
            return Math.max(min, even);
        }

        public CustomDisplayProfile sanitized() {
            int sW = evenClamped(streamWidth, 320, 1920);
            int sH = evenClamped(streamHeight, 240, 1200);
            int cW = evenClamped(canvasWidth, 320, 2560);
            int cH = evenClamped(canvasHeight, 240, 1440);
            int vTop = Math.max(0, Math.min(viewportTop, sH - 64));
            int vH = Math.max(64, Math.min(viewportHeight, sH - vTop));
            int mm = Math.max(80, Math.min(300, widthPhysicalMM));
            String cleanName = bikeName == null ? "" : bikeName.trim();
            if (cleanName.isEmpty()) cleanName = "自定义思骑仪表";
            return new CustomDisplayProfile(
                    cleanName, sW, sH, cW, cH, vTop, vH, mm, disableSafeAreaSquareCorners
            );
        }

        public BikePresetSpec toBikePresetSpec() {
            CustomDisplayProfile p = sanitized();
            return new BikePresetSpec(
                    MODEL_CUSTOM,
                    "自定义分辨率（" + p.bikeName + "）",
                    "推流 " + p.streamWidth + "×" + p.streamHeight
                            + " · 画布 " + p.canvasWidth + "×" + p.canvasHeight
                            + " · 视口 y=" + p.viewportTop + ", h=" + p.viewportHeight,
                    p.streamWidth, p.streamHeight,
                    p.canvasWidth, p.canvasHeight,
                    p.viewportTop, p.viewportHeight,
                    1.000f,
                    p.disableSafeAreaSquareCorners,
                    "MOTOU", "MoTou", "MoTou-Custom-Cluster"
            );
        }

        public String exportReport() {
            CustomDisplayProfile p = sanitized();
            int bottomPad = Math.max(0, p.streamHeight - p.viewportTop - p.viewportHeight);
            String escapedName = p.bikeName.replace("\\", "\\\\").replace("\"", "\\\"");
            String json = "{\n"
                    + "  \"bikeName\": \"" + escapedName + "\",\n"
                    + "  \"streamWidth\": " + p.streamWidth + ",\n"
                    + "  \"streamHeight\": " + p.streamHeight + ",\n"
                    + "  \"canvasWidth\": " + p.canvasWidth + ",\n"
                    + "  \"canvasHeight\": " + p.canvasHeight + ",\n"
                    + "  \"viewportTop\": " + p.viewportTop + ",\n"
                    + "  \"viewportHeight\": " + p.viewportHeight + ",\n"
                    + "  \"widthPhysicalMM\": " + p.widthPhysicalMM + ",\n"
                    + "  \"disableSafeAreaSquareCorners\": " + p.disableSafeAreaSquareCorners + "\n"
                    + "}";
            return "【摩投 Android · 思骑仪表实测分辨率配置】\n"
                    + "车型备注：" + p.bikeName + "\n"
                    + "仪表推流分辨率（69B头/H.264）：" + p.streamWidth + " × " + p.streamHeight + " @ 30 FPS\n"
                    + "虚拟画布（CarPlay/CarLife）：" + p.canvasWidth + " × " + p.canvasHeight + "\n"
                    + "有效视口范围：顶部偏移 " + p.viewportTop + " px，高度 " + p.viewportHeight
                    + " px（底部留黑 " + bottomPad + " px）\n"
                    + "图标物理宽度：" + p.widthPhysicalMM + " mm\n"
                    + "直角无圆角黑边：" + (p.disableSafeAreaSquareCorners ? "开启" : "关闭") + "\n\n"
                    + "JSON 配置（可直接发给作者收录预设）：\n"
                    + json;
        }
    }

    /**
     * Registered built-in bike cluster presets.
     */
    private static final BikePresetSpec[] PRESETS = new BikePresetSpec[]{
            new BikePresetSpec(
                    MODEL_KOVE_450RR,
                    "凯越 450RR",
                    "直角无圆角黑边 · 四边等距留白 · 1:1 图标比例矫正",
                    1280, 640,
                    2560, 1120,
                    78, 484,
                    1.000f,
                    true,
                    "KOVE", "Kove", "Kove-450RR-CarPlay"
            )
    };

    public static CustomDisplayProfile loadCustomProfile(Context ctx) {
        if (ctx == null) return CustomDisplayProfile.kove450RRDefault();
        SharedPreferences p = ctx.getSharedPreferences(LOCAL_PREF, Context.MODE_PRIVATE);
        return new CustomDisplayProfile(
                p.getString(KEY_CUSTOM_BIKE_NAME, "凯越 450RR"),
                p.getInt(KEY_CUSTOM_STREAM_W, 1280),
                p.getInt(KEY_CUSTOM_STREAM_H, 640),
                p.getInt(KEY_CUSTOM_CANVAS_W, 1280),
                p.getInt(KEY_CUSTOM_CANVAS_H, 560),
                p.getInt(KEY_CUSTOM_VP_TOP, 78),
                p.getInt(KEY_CUSTOM_VP_H, 484),
                p.getInt(KEY_CUSTOM_PHYSICAL_MM, 100),
                p.getBoolean(KEY_CUSTOM_SQUARE_CORNERS, true)
        ).sanitized();
    }

    public static void saveCustomProfile(Context ctx, CustomDisplayProfile profile) {
        if (ctx == null || profile == null) return;
        CustomDisplayProfile p = profile.sanitized();
        ctx.getSharedPreferences(LOCAL_PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CUSTOM_BIKE_NAME, p.bikeName)
                .putInt(KEY_CUSTOM_STREAM_W, p.streamWidth)
                .putInt(KEY_CUSTOM_STREAM_H, p.streamHeight)
                .putInt(KEY_CUSTOM_CANVAS_W, p.canvasWidth)
                .putInt(KEY_CUSTOM_CANVAS_H, p.canvasHeight)
                .putInt(KEY_CUSTOM_VP_TOP, p.viewportTop)
                .putInt(KEY_CUSTOM_VP_H, p.viewportHeight)
                .putInt(KEY_CUSTOM_PHYSICAL_MM, p.widthPhysicalMM)
                .putBoolean(KEY_CUSTOM_SQUARE_CORNERS, p.disableSafeAreaSquareCorners)
                .apply();
    }

    public static BikePresetSpec[] getAllPresets() {
        return PRESETS.clone();
    }

    public static BikePresetSpec[] getAllPresets(Context ctx) {
        return new BikePresetSpec[]{
                PRESETS[MODEL_KOVE_450RR],
                loadCustomProfile(ctx).toBikePresetSpec()
        };
    }

    public static BikePresetSpec getPresetSpec(int modelId) {
        return PRESETS[MODEL_KOVE_450RR];
    }

    public static BikePresetSpec getPresetSpec(Context ctx, int modelId) {
        if (modelId == MODEL_CUSTOM) {
            return loadCustomProfile(ctx).toBikePresetSpec();
        }
        return PRESETS[MODEL_KOVE_450RR];
    }

    public static BikePresetSpec getActivePresetSpec(Context ctx) {
        return getPresetSpec(ctx, getBikeModelPreset(ctx));
    }

    public static int getBikeModelPreset(Context ctx) {
        int val = ctx.getSharedPreferences(LOCAL_PREF, Context.MODE_PRIVATE)
                .getInt(KEY_BIKE_MODEL, MODEL_KOVE_450RR);
        return (val == MODEL_CUSTOM) ? MODEL_CUSTOM : MODEL_KOVE_450RR;
    }

    public static void setBikeModelPreset(Context ctx, int modelId) {
        int safeId = (modelId == MODEL_CUSTOM) ? MODEL_CUSTOM : MODEL_KOVE_450RR;
        ctx.getSharedPreferences(LOCAL_PREF, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_BIKE_MODEL, safeId)
                .apply();
    }

    public static int getProjectionSourceMode(Context ctx) {
        if (ctx == null) return SOURCE_MODE_CARPLAY;
        int val = ctx.getSharedPreferences(LOCAL_PREF, Context.MODE_PRIVATE)
                .getInt(KEY_SOURCE_MODE, SOURCE_MODE_CARPLAY);
        return (val == SOURCE_MODE_CARLIFE) ? SOURCE_MODE_CARLIFE : SOURCE_MODE_CARPLAY;
    }

    public static void setProjectionSourceMode(Context ctx, int mode) {
        if (ctx == null) return;
        int safe = (mode == SOURCE_MODE_CARLIFE) ? SOURCE_MODE_CARLIFE : SOURCE_MODE_CARPLAY;
        ctx.getSharedPreferences(LOCAL_PREF, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_SOURCE_MODE, safe)
                .apply();
    }

    public static int[] getEffectiveCarLifeCanvasSize(Context ctx) {
        if (ctx == null) return new int[]{1280, 560};
        if (getBikeModelPreset(ctx) == MODEL_CUSTOM) {
            CustomDisplayProfile p = loadCustomProfile(ctx);
            return new int[]{p.canvasWidth, p.canvasHeight};
        }
        int iconMode = getIconSizeMode(ctx);
        switch (iconMode) {
            case ICON_MODE_XL:
                return new int[]{1024, 448};
            case ICON_MODE_STANDARD:
                return new int[]{1536, 672};
            case ICON_MODE_LARGE:
            default:
                return new int[]{1280, 560};
        }
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
        if (preset.id == MODEL_CUSTOM) {
            CustomDisplayProfile custom = loadCustomProfile(ctx);
            scaleTenths = 10;
            scalePercent = 100;
            physicalMm = custom.widthPhysicalMM;
        } else {
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
