package com.carplay.siqirelay.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Shared visual tokens & widget stylers matching embedded DiPlay's DiPlayPalette
 * (minimalist rounded cards, subtle 1dp borders, calm day/night contrast).
 */
public final class DiPlayStyle {

    public static final class Palette {
        public final boolean night;
        public final int background;
        public final int surface;
        public final int button;
        public final int outline;
        public final int textPrimary;
        public final int textSecondary;
        public final int accent;
        public final int onAccent;
        public final int accentTrack;
        public final int trackOff;
        public final int success;
        public final int warning;
        public final int danger;
        public final int ripple;

        private Palette(boolean night) {
            this.night = night;
            if (night) {
                background = Color.rgb(12, 17, 27);
                surface = Color.rgb(21, 30, 44);
                button = Color.rgb(31, 43, 61);
                outline = Color.rgb(42, 56, 75);
                textPrimary = Color.rgb(241, 245, 252);
                textSecondary = Color.rgb(168, 182, 202);
                accent = Color.rgb(166, 200, 255);
                onAccent = Color.rgb(12, 17, 27);
                accentTrack = Color.rgb(50, 88, 140);
                trackOff = Color.rgb(42, 56, 75);
                success = Color.rgb(127, 205, 154);
                warning = Color.rgb(255, 196, 128);
                danger = Color.rgb(190, 45, 45);
                ripple = 0x336F9FD9;
            } else {
                background = Color.rgb(233, 238, 246);
                surface = Color.rgb(248, 250, 253);
                button = Color.rgb(220, 229, 241);
                outline = Color.rgb(167, 181, 199);
                textPrimary = Color.rgb(28, 28, 30);
                textSecondary = Color.rgb(90, 100, 116);
                accent = Color.rgb(36, 90, 156);
                onAccent = Color.WHITE;
                accentTrack = Color.rgb(36, 90, 156);
                trackOff = Color.rgb(167, 181, 199);
                success = Color.rgb(40, 107, 63);
                warning = Color.rgb(122, 69, 0);
                danger = Color.rgb(155, 48, 48);
                ripple = 0x33245A9C;
            }
        }
    }

    private DiPlayStyle() {}

    public static boolean isNightMode(Context context) {
        int mask = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    public static Palette palette(Context context) {
        return new Palette(isNightMode(context));
    }

    public static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    public static void applyWindowChrome(Activity activity, View root) {
        Palette p = palette(activity);
        if (root != null) {
            root.setBackgroundColor(p.background);
        }
        Window window = activity.getWindow();
        if (window == null) return;
        window.setStatusBarColor(p.background);
        window.setNavigationBarColor(p.background);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController ctrl = window.getInsetsController();
            if (ctrl != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ctrl.setSystemBarsAppearance(p.night ? 0 : mask, mask);
            }
        } else {
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (p.night) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    public static void styleCard(View view, Palette p) {
        if (view == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(p.surface);
        bg.setCornerRadius(dp(view.getContext(), 20));
        bg.setStroke(dp(view.getContext(), 1), p.outline);
        view.setBackground(bg);
        view.setElevation(0f);
    }

    public static void styleInnerBox(View view, Palette p) {
        if (view == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(withAlpha(p.button, p.night ? 0x66 : 0x88));
        bg.setCornerRadius(dp(view.getContext(), 14));
        view.setBackground(bg);
    }

    public static void stylePrimaryButton(Button btn, Palette p) {
        applyButtonStyle(btn, p.accent, Color.TRANSPARENT, p.onAccent, p.ripple, 14);
    }

    public static void styleSecondaryButton(Button btn, Palette p) {
        applyButtonStyle(btn, p.button, p.outline, p.textPrimary, p.ripple, 14);
    }

    public static void styleDangerButton(Button btn, Palette p) {
        applyButtonStyle(btn, p.danger, Color.TRANSPARENT, Color.WHITE, 0x33FFFFFF, 14);
    }

    public static void styleSegmentedButton(Button btn, Palette p, boolean selected) {
        if (selected) {
            applyButtonStyle(btn, p.accent, Color.TRANSPARENT, p.onAccent, p.ripple, 12);
        } else {
            applyButtonStyle(btn, p.button, p.outline, p.textPrimary, p.ripple, 12);
        }
    }

    private static void applyButtonStyle(Button btn, int fill, int stroke, int textColor, int rippleColor, int radiusDp) {
        if (btn == null) return;
        Context ctx = btn.getContext();
        float radius = dp(ctx, radiusDp);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(radius);
        if (stroke != Color.TRANSPARENT) {
            shape.setStroke(dp(ctx, 1), stroke);
        }
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        btn.setBackgroundTintList(null);
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), shape, mask));
        btn.setTextColor(textColor);
        btn.setStateListAnimator(null);
        btn.setElevation(0f);
    }

    public static void styleChip(TextView chip, Palette p, int color) {
        if (chip == null) return;
        Context ctx = chip.getContext();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(withAlpha(color, p.night ? 0x2A : 0x20));
        bg.setCornerRadius(dp(ctx, 999));
        bg.setStroke(dp(ctx, 1), withAlpha(color, p.night ? 0x66 : 0x55));
        chip.setBackground(bg);
        chip.setTextColor(color);
    }

    public static void styleSwitch(Switch sw, Palette p) {
        if (sw == null) return;
        Context ctx = sw.getContext();
        int thumbInset = dp(ctx, 3);
        GradientDrawable thumbShape = new GradientDrawable();
        thumbShape.setShape(GradientDrawable.OVAL);
        thumbShape.setSize(dp(ctx, 24), dp(ctx, 24));
        thumbShape.setColor(Color.WHITE);
        sw.setThumbDrawable(new InsetDrawable(thumbShape, thumbInset));

        GradientDrawable trackShape = new GradientDrawable();
        trackShape.setCornerRadius(dp(ctx, 15));
        trackShape.setSize(dp(ctx, 52), dp(ctx, 30));
        trackShape.setColor(Color.WHITE);
        trackShape.setAlpha((int) (0.35f * 255));
        sw.setTrackDrawable(trackShape);
        sw.setSwitchMinWidth(dp(ctx, 52));
        sw.setShowText(false);

        int[][] states = new int[][]{
                new int[]{-android.R.attr.state_enabled},
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        sw.setThumbTintList(new ColorStateList(states, new int[]{
                withAlpha(p.textSecondary, 0x61), p.accent, p.textSecondary
        }));
        sw.setTrackTintList(new ColorStateList(states, new int[]{
                withAlpha(p.trackOff, 0x61), p.accentTrack, p.trackOff
        }));
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
