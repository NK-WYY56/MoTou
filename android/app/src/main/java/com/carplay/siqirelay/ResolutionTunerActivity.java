package com.carplay.siqirelay;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.carplay.siqirelay.carplay.DiPlayConfigOptimizer;
import com.carplay.siqirelay.carplay.DiPlayConfigOptimizer.CustomDisplayProfile;
import com.carplay.siqirelay.carplay.DiPlayConfigOptimizer.CustomTemplate;
import com.carplay.siqirelay.ui.DiPlayStyle;
import com.shilapi.xcertplay.SiQiRelayBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Interactive display resolution & viewport tuner for Thinkerride (SiQi) clusters.
 * Lets riders test starter resolutions (e.g. 800x480, 1024x600) to solve non-450RR black screens,
 * save custom stream/canvas parameters, and copy/share JSON calibration reports.
 */
public class ResolutionTunerActivity extends Activity {

    private DiPlayStyle.Palette palette;
    private CustomDisplayProfile profile;

    private Button btnBack;
    private Button btnSyncCanvas;
    private Button btnFillViewport;
    private Button btnSaveApply;
    private Button btnExport;
    private Button btnResetKove;

    private EditText etBikeName;
    private EditText etStreamW;
    private EditText etStreamH;
    private EditText etCanvasW;
    private EditText etCanvasH;
    private EditText etVpTop;
    private EditText etVpH;
    private EditText etPhysicalMm;
    private Switch swSquareCorners;

    private TextView tvPreview;
    private TextView tvStatus;
    private LinearLayout layoutTemplates;

    private final List<Button> generatedButtons = new ArrayList<>();
    private final List<TextView> rowLabels = new ArrayList<>();
    private final List<EditText> numericInputs = new ArrayList<>();
    private boolean syncingFields = false;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ResolutionTunerWorker");
        t.setDaemon(true);
        return t;
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_resolution_tuner);
        palette = DiPlayStyle.palette(this);
        DiPlayStyle.applyWindowChrome(this, findViewById(R.id.tuner_root_scroll));

        profile = DiPlayConfigOptimizer.loadCustomProfile(this);
        initViews();
        buildTemplateButtons();
        buildStepperRows();
        bindActions();
        applyTheme();
        syncFieldsFromProfile();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        palette = DiPlayStyle.palette(this);
        DiPlayStyle.applyWindowChrome(this, findViewById(R.id.tuner_root_scroll));
        applyTheme();
    }

    private void initViews() {
        btnBack = findViewById(R.id.btn_tuner_back);
        btnSyncCanvas = findViewById(R.id.btn_sync_canvas_to_stream);
        btnFillViewport = findViewById(R.id.btn_fill_full_viewport);
        btnSaveApply = findViewById(R.id.btn_tuner_save_apply);
        btnExport = findViewById(R.id.btn_tuner_export);
        btnResetKove = findViewById(R.id.btn_tuner_reset_kove);

        etBikeName = findViewById(R.id.et_bike_name);
        swSquareCorners = findViewById(R.id.sw_square_corners);
        tvPreview = findViewById(R.id.tv_tuner_preview);
        tvStatus = findViewById(R.id.tv_tuner_status);
        layoutTemplates = findViewById(R.id.layout_template_buttons);
    }

    private void buildTemplateButtons() {
        layoutTemplates.removeAllViews();
        CustomTemplate[] templates = CustomDisplayProfile.COMMON_TEMPLATES;
        int margin = DiPlayStyle.dp(this, 4);
        int h = DiPlayStyle.dp(this, 38);

        for (int i = 0; i < templates.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = DiPlayStyle.dp(this, 8);
            row.setLayoutParams(rowLp);

            for (int j = 0; j < 2 && (i + j) < templates.length; j++) {
                final CustomTemplate item = templates[i + j];
                Button btn = new Button(this);
                btn.setAllCaps(false);
                btn.setText(item.label);
                btn.setTextSize(12f);
                btn.setMaxLines(1);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1f);
                if (j == 0 && (i + 1) < templates.length) {
                    lp.setMarginEnd(margin);
                } else if (j == 1) {
                    lp.setMarginStart(margin);
                }
                btn.setLayoutParams(lp);
                btn.setOnClickListener(v -> {
                    profile = item.profile.sanitized();
                    syncFieldsFromProfile();
                    tvStatus.setText("已填入「" + item.label + "」模板，点底部「保存并启用」生效。");
                    tvStatus.setTextColor(palette.accent);
                });
                generatedButtons.add(btn);
                row.addView(btn);
            }
            layoutTemplates.addView(row);
        }
    }

    private void buildStepperRows() {
        etStreamW = setupStepperRow(R.id.row_stream_w, "推流宽度", 16, () -> profile.streamWidth, v -> profile.streamWidth = v);
        etStreamH = setupStepperRow(R.id.row_stream_h, "推流高度", 16, () -> profile.streamHeight, v -> profile.streamHeight = v);
        etCanvasW = setupStepperRow(R.id.row_canvas_w, "画布宽度", 16, () -> profile.canvasWidth, v -> profile.canvasWidth = v);
        etCanvasH = setupStepperRow(R.id.row_canvas_h, "画布高度", 16, () -> profile.canvasHeight, v -> profile.canvasHeight = v);
        etVpTop = setupStepperRow(R.id.row_vp_top, "顶部偏移 y", 2, () -> profile.viewportTop, v -> profile.viewportTop = v);
        etVpH = setupStepperRow(R.id.row_vp_h, "有效高度 h", 2, () -> profile.viewportHeight, v -> profile.viewportHeight = v);
        etPhysicalMm = setupStepperRow(R.id.row_physical_mm, "物理宽 mm", 10, () -> profile.widthPhysicalMM, v -> profile.widthPhysicalMM = v);
    }

    private interface IntGetter {
        int get();
    }

    private interface IntSetter {
        void set(int v);
    }

    private EditText setupStepperRow(int rowId, String label, int step, IntGetter getter, IntSetter setter) {
        LinearLayout row = findViewById(rowId);
        row.removeAllViews();
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(13f);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                DiPlayStyle.dp(this, 82), ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLabel.setLayoutParams(labelLp);
        rowLabels.add(tvLabel);
        row.addView(tvLabel);

        Button btnMinus = new Button(this);
        btnMinus.setAllCaps(false);
        btnMinus.setText("-" + step);
        btnMinus.setTextSize(12f);
        LinearLayout.LayoutParams minusLp = new LinearLayout.LayoutParams(
                DiPlayStyle.dp(this, 56), DiPlayStyle.dp(this, 36));
        btnMinus.setLayoutParams(minusLp);
        generatedButtons.add(btnMinus);
        row.addView(btnMinus);

        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setGravity(Gravity.CENTER);
        field.setTextSize(14f);
        field.setMaxLines(1);
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(
                0, DiPlayStyle.dp(this, 38), 1f);
        fieldLp.setMarginStart(DiPlayStyle.dp(this, 8));
        fieldLp.setMarginEnd(DiPlayStyle.dp(this, 8));
        field.setLayoutParams(fieldLp);
        numericInputs.add(field);
        row.addView(field);

        Button btnPlus = new Button(this);
        btnPlus.setAllCaps(false);
        btnPlus.setText("+" + step);
        btnPlus.setTextSize(12f);
        LinearLayout.LayoutParams plusLp = new LinearLayout.LayoutParams(
                DiPlayStyle.dp(this, 56), DiPlayStyle.dp(this, 36));
        btnPlus.setLayoutParams(plusLp);
        generatedButtons.add(btnPlus);
        row.addView(btnPlus);

        btnMinus.setOnClickListener(v -> {
            readFieldsIntoProfile();
            setter.set(Math.max(0, getter.get() - step));
            profile = profile.sanitized();
            syncFieldsFromProfile();
        });
        btnPlus.setOnClickListener(v -> {
            readFieldsIntoProfile();
            setter.set(getter.get() + step);
            profile = profile.sanitized();
            syncFieldsFromProfile();
        });

        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (syncingFields) return;
                String raw = s.toString().trim();
                if (!raw.isEmpty()) {
                    try {
                        setter.set(Integer.parseInt(raw));
                        updatePreviewText(profile.sanitized());
                    } catch (NumberFormatException ignored) {}
                }
            }
        });
        return field;
    }

    private void bindActions() {
        btnBack.setOnClickListener(v -> finish());

        btnSyncCanvas.setOnClickListener(v -> {
            readFieldsIntoProfile();
            profile.canvasWidth = profile.streamWidth;
            profile.canvasHeight = profile.streamHeight;
            syncFieldsFromProfile();
        });

        btnFillViewport.setOnClickListener(v -> {
            readFieldsIntoProfile();
            profile.viewportTop = 0;
            profile.viewportHeight = profile.streamHeight;
            syncFieldsFromProfile();
        });

        swSquareCorners.setOnCheckedChangeListener((btn, checked) -> {
            if (syncingFields) return;
            profile.disableSafeAreaSquareCorners = checked;
            updatePreviewText(profile.sanitized());
        });

        btnSaveApply.setOnClickListener(v -> saveAndApply());
        btnExport.setOnClickListener(v -> exportConfiguration());
        btnResetKove.setOnClickListener(v -> {
            profile = CustomDisplayProfile.kove450RRDefault();
            syncFieldsFromProfile();
            tvStatus.setText("已恢复凯越 450RR 默认参数（点上方「保存并启用」生效）。");
            tvStatus.setTextColor(palette.textSecondary);
        });
    }

    private void applyTheme() {
        DiPlayStyle.styleCard(findViewById(R.id.card_tuner_templates), palette);
        DiPlayStyle.styleCard(findViewById(R.id.card_tuner_params), palette);
        DiPlayStyle.styleCard(findViewById(R.id.card_tuner_actions), palette);

        setColor(R.id.tv_kicker_templates, palette.accent);
        setColor(R.id.tv_kicker_params, palette.accent);
        setColor(R.id.tv_kicker_actions, palette.accent);

        setColor(R.id.tv_tuner_title, palette.textPrimary);
        setColor(R.id.tv_label_bike_name, palette.textPrimary);
        setColor(R.id.tv_label_stream_res, palette.textPrimary);
        setColor(R.id.tv_label_canvas_res, palette.textPrimary);
        setColor(R.id.tv_label_viewport, palette.textPrimary);
        setColor(R.id.tv_label_physical, palette.textPrimary);
        setColor(R.id.tv_square_corners_label, palette.textPrimary);

        setColor(R.id.tv_templates_desc, palette.textSecondary);
        tvPreview.setTextColor(palette.textSecondary);
        tvStatus.setTextColor(palette.textSecondary);

        for (TextView tv : rowLabels) {
            tv.setTextColor(palette.textSecondary);
        }

        styleEditText(etBikeName);
        for (EditText et : numericInputs) {
            styleEditText(et);
        }

        DiPlayStyle.styleSecondaryButton(btnBack, palette);
        DiPlayStyle.styleSecondaryButton(btnSyncCanvas, palette);
        DiPlayStyle.styleSecondaryButton(btnFillViewport, palette);
        DiPlayStyle.stylePrimaryButton(btnSaveApply, palette);
        DiPlayStyle.styleSecondaryButton(btnExport, palette);
        DiPlayStyle.styleSecondaryButton(btnResetKove, palette);
        for (Button btn : generatedButtons) {
            DiPlayStyle.styleSecondaryButton(btn, palette);
        }
        DiPlayStyle.styleSwitch(swSquareCorners, palette);
    }

    private void setColor(int id, int color) {
        TextView tv = findViewById(id);
        if (tv != null) tv.setTextColor(color);
    }

    private void styleEditText(EditText et) {
        if (et == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.night ? Color.argb(180, 31, 43, 61) : Color.argb(200, 220, 229, 241));
        bg.setCornerRadius(DiPlayStyle.dp(this, 10));
        bg.setStroke(DiPlayStyle.dp(this, 1), palette.outline);
        et.setBackground(bg);
        et.setTextColor(palette.textPrimary);
        et.setHintTextColor(palette.textSecondary);
    }

    private void readFieldsIntoProfile() {
        profile.bikeName = etBikeName.getText().toString();
        profile.streamWidth = parseIntOr(etStreamW, profile.streamWidth);
        profile.streamHeight = parseIntOr(etStreamH, profile.streamHeight);
        profile.canvasWidth = parseIntOr(etCanvasW, profile.canvasWidth);
        profile.canvasHeight = parseIntOr(etCanvasH, profile.canvasHeight);
        profile.viewportTop = parseIntOr(etVpTop, profile.viewportTop);
        profile.viewportHeight = parseIntOr(etVpH, profile.viewportHeight);
        profile.widthPhysicalMM = parseIntOr(etPhysicalMm, profile.widthPhysicalMM);
        profile.disableSafeAreaSquareCorners = swSquareCorners.isChecked();
        profile = profile.sanitized();
    }

    private int parseIntOr(EditText et, int fallback) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void syncFieldsFromProfile() {
        syncingFields = true;
        profile = profile.sanitized();
        etBikeName.setText(profile.bikeName);
        etStreamW.setText(String.valueOf(profile.streamWidth));
        etStreamH.setText(String.valueOf(profile.streamHeight));
        etCanvasW.setText(String.valueOf(profile.canvasWidth));
        etCanvasH.setText(String.valueOf(profile.canvasHeight));
        etVpTop.setText(String.valueOf(profile.viewportTop));
        etVpH.setText(String.valueOf(profile.viewportHeight));
        etPhysicalMm.setText(String.valueOf(profile.widthPhysicalMM));
        swSquareCorners.setChecked(profile.disableSafeAreaSquareCorners);
        syncingFields = false;
        updatePreviewText(profile);
    }

    private void updatePreviewText(CustomDisplayProfile p) {
        int bottom = Math.max(0, p.streamHeight - p.viewportTop - p.viewportHeight);
        tvPreview.setText("当前预览：画布 " + p.canvasWidth + "×" + p.canvasHeight
                + " → 仪表推流 " + p.streamWidth + "×" + p.streamHeight
                + "（视口 y=" + p.viewportTop + ".." + (p.viewportTop + p.viewportHeight)
                + "，底留黑 " + bottom + "px）· " + p.widthPhysicalMM + "mm · "
                + (p.disableSafeAreaSquareCorners ? "直角" : "安全区"));
    }

    private void saveAndApply() {
        readFieldsIntoProfile();
        syncFieldsFromProfile();
        DiPlayConfigOptimizer.saveCustomProfile(this, profile);
        DiPlayConfigOptimizer.setBikeModelPreset(this, DiPlayConfigOptimizer.MODEL_CUSTOM);

        final boolean sessionRunning = SiQiRelayBridge.hasSession();
        btnSaveApply.setEnabled(false);
        tvStatus.setText("正在保存「" + profile.bikeName + "」并同步推流分辨率...");
        tvStatus.setTextColor(palette.accent);

        worker.execute(() -> {
            boolean ok = DiPlayConfigOptimizer.applyOptimizedConfig(
                    ResolutionTunerActivity.this, true, true);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                btnSaveApply.setEnabled(true);
                if (ok) {
                    tvStatus.setText(sessionRunning
                            ? "已保存并切为自定义预设「" + profile.bikeName + "」，正在重连生效。"
                            : "已保存并启用自定义预设「" + profile.bikeName + "」，连接仪表后立即生效。");
                    tvStatus.setTextColor(palette.success);
                    Toast.makeText(this, "已应用自定义分辨率 " + profile.streamWidth + "×" + profile.streamHeight,
                            Toast.LENGTH_SHORT).show();
                } else {
                    tvStatus.setText("写入参数失败，请重试。");
                    tvStatus.setTextColor(palette.danger);
                }
            });
        });
    }

    private void exportConfiguration() {
        readFieldsIntoProfile();
        syncFieldsFromProfile();
        DiPlayConfigOptimizer.saveCustomProfile(this, profile);
        String report = profile.exportReport();

        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("MoTou Resolution Config", report));
        }
        tvStatus.setText("已复制最终分辨率配置到剪贴板，正在打开系统分享。");
        tvStatus.setTextColor(palette.success);

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "摩投 思骑仪表分辨率配置 - " + profile.bikeName);
        share.putExtra(Intent.EXTRA_TEXT, report);
        startActivity(Intent.createChooser(share, "分享实测分辨率配置"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }
}
