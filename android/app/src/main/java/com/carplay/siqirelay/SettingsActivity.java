package com.carplay.siqirelay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.carplay.siqirelay.carplay.AutoConnectManager;
import com.carplay.siqirelay.carplay.CarPlayDongleManager;
import com.carplay.siqirelay.carplay.DiPlayConfigOptimizer;
import com.carplay.siqirelay.input.HandlebarKeyBridge;
import com.carplay.siqirelay.ui.DiPlayStyle;
import com.shilapi.xcertplay.SiQiRelayBridge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unified settings screen adopting DiPlay's calm card aesthetic. Houses audio/Siri routing,
 * automation toggles, bike cluster model secondary picker & icon size presets, diagnostic tools,
 * and open-source credits.
 */
public class SettingsActivity extends Activity {

    private Switch swHeadsetAudio;
    private Switch swAutoConnect;
    private Switch swAutoBackground;

    private View rowBikeModelPicker;
    private TextView tvAutoConnectSummary;
    private TextView tvBikeModelValue;
    private TextView tvBikeModelDesc;
    private TextView tvDongleStatus;
    private TextView tvSettingsKeyLog;

    private Button btnBack;
    private Button btnIconXl;
    private Button btnIconLarge;
    private Button btnIconStd;
    private Button btnResolutionTuner;
    private Button btnOptimizeConfig;
    private Button btnForceIdr;
    private Button btnScanUsb;
    private Button btnDiPlayAdvanced;
    private Button btnKeyUp;
    private Button btnKeyDown;
    private Button btnKeyConfirm;
    private Button btnKeyBack;
    private Button btnOpenSourceCredits;
    private Button btnAuthorGithub;
    private Button btnAuthorDouyin;

    private static final String AUTHOR_GITHUB_URL = "https://github.com/NK-WYY56/MoTou";
    private static final String AUTHOR_DOUYIN_URL = "https://v.douyin.com/1BVqQqBYtuc/";

    private BridgeService bridgeService;
    private boolean isBound = false;
    private HandlebarKeyBridge.OnKeyEventListener handlebarKeyListener;
    private DiPlayStyle.Palette palette;

    private final ExecutorService configExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SettingsConfigWorker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger applySeq = new AtomicInteger(0);

    private final AutoConnectManager.AutoConnectUiListener autoConnectListener =
            new AutoConnectManager.AutoConnectUiListener() {
                @Override
                public void onAutoConnectTriggered(boolean fromAppLaunch) {
                    updateAutoConnectSummary();
                }

                @Override
                public void onAutoConnectStateChanged() {
                    updateAutoConnectSummary();
                }
            };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            bridgeService = ((BridgeService.LocalBinder) binder).getService();
            isBound = true;
            updateDongleStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bridgeService = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        palette = DiPlayStyle.palette(this);
        DiPlayStyle.applyWindowChrome(this, findViewById(R.id.settings_root_scroll));

        initViews();
        applyDiPlayTheme();
        bindControls();

        Intent sIntent = new Intent(this, BridgeService.class);
        bindService(sIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AutoConnectManager.getInstance(this).setUiListener(autoConnectListener);
        refreshAllStates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        AutoConnectManager.getInstance(this).setUiListener(null);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        palette = DiPlayStyle.palette(this);
        DiPlayStyle.applyWindowChrome(this, findViewById(R.id.settings_root_scroll));
        applyDiPlayTheme();
        refreshAllStates();
    }

    private void initViews() {
        btnBack = findViewById(R.id.btn_settings_back);
        swHeadsetAudio = findViewById(R.id.sw_headset_audio);
        swAutoConnect = findViewById(R.id.sw_auto_connect);
        swAutoBackground = findViewById(R.id.sw_auto_background);

        rowBikeModelPicker = findViewById(R.id.row_bike_model_picker);
        tvAutoConnectSummary = findViewById(R.id.tv_auto_connect_summary);
        tvBikeModelValue = findViewById(R.id.tv_bike_model_value);
        tvBikeModelDesc = findViewById(R.id.tv_bike_model_desc);
        tvDongleStatus = findViewById(R.id.tv_dongle_status);
        tvSettingsKeyLog = findViewById(R.id.tv_settings_key_log);

        btnIconXl = findViewById(R.id.btn_icon_xl);
        btnIconLarge = findViewById(R.id.btn_icon_large);
        btnIconStd = findViewById(R.id.btn_icon_std);
        btnResolutionTuner = findViewById(R.id.btn_resolution_tuner);
        btnOptimizeConfig = findViewById(R.id.btn_optimize_config);

        btnForceIdr = findViewById(R.id.btn_force_idr);
        btnScanUsb = findViewById(R.id.btn_scan_usb);
        btnDiPlayAdvanced = findViewById(R.id.btn_diplay_advanced);
        btnKeyUp = findViewById(R.id.btn_key_up);
        btnKeyDown = findViewById(R.id.btn_key_down);
        btnKeyConfirm = findViewById(R.id.btn_key_confirm);
        btnKeyBack = findViewById(R.id.btn_key_back);
        btnOpenSourceCredits = findViewById(R.id.btn_open_source_credits);
        btnAuthorGithub = findViewById(R.id.btn_author_github);
        btnAuthorDouyin = findViewById(R.id.btn_author_douyin);
    }

    private void applyDiPlayTheme() {
        DiPlayStyle.styleCard(findViewById(R.id.card_settings_audio), palette);
        DiPlayStyle.styleCard(findViewById(R.id.card_settings_auto), palette);
        DiPlayStyle.styleCard(findViewById(R.id.card_settings_display), palette);
        DiPlayStyle.styleCard(findViewById(R.id.card_settings_tools), palette);
        DiPlayStyle.styleCard(findViewById(R.id.card_settings_credits), palette);
        DiPlayStyle.styleInnerBox(rowBikeModelPicker, palette);

        setAccentText(R.id.tv_kicker_audio);
        setAccentText(R.id.tv_kicker_auto);
        setAccentText(R.id.tv_kicker_display);
        setAccentText(R.id.tv_kicker_tools);
        setAccentText(R.id.tv_kicker_credits);
        setAccentText(R.id.tv_bike_model_value);

        setPrimaryText(R.id.tv_settings_title);
        setPrimaryText(R.id.tv_headset_audio_title);
        setPrimaryText(R.id.tv_auto_connect_title);
        setPrimaryText(R.id.tv_auto_bg_title);
        setPrimaryText(R.id.tv_bike_model_label);
        setPrimaryText(R.id.tv_icon_size_label);
        setPrimaryText(R.id.tv_key_test_label);

        setSecondaryText(R.id.tv_headset_audio_desc);
        setSecondaryText(R.id.tv_auto_connect_summary);
        setSecondaryText(R.id.tv_auto_bg_desc);
        setSecondaryText(R.id.tv_bike_model_desc);
        setSecondaryText(R.id.tv_dongle_status);
        setSecondaryText(R.id.tv_settings_key_log);
        setSecondaryText(R.id.tv_credits_summary);

        DiPlayStyle.styleSecondaryButton(btnBack, palette);
        DiPlayStyle.styleSecondaryButton(btnResolutionTuner, palette);
        DiPlayStyle.styleSecondaryButton(btnOptimizeConfig, palette);
        DiPlayStyle.styleSecondaryButton(btnForceIdr, palette);
        DiPlayStyle.styleSecondaryButton(btnScanUsb, palette);
        DiPlayStyle.stylePrimaryButton(btnDiPlayAdvanced, palette);
        DiPlayStyle.styleSecondaryButton(btnOpenSourceCredits, palette);
        DiPlayStyle.styleSecondaryButton(btnAuthorGithub, palette);
        DiPlayStyle.styleSecondaryButton(btnAuthorDouyin, palette);
        DiPlayStyle.styleSecondaryButton(btnKeyUp, palette);
        DiPlayStyle.styleSecondaryButton(btnKeyDown, palette);
        DiPlayStyle.styleSecondaryButton(btnKeyConfirm, palette);
        DiPlayStyle.styleSecondaryButton(btnKeyBack, palette);

        DiPlayStyle.styleSwitch(swHeadsetAudio, palette);
        DiPlayStyle.styleSwitch(swAutoConnect, palette);
        DiPlayStyle.styleSwitch(swAutoBackground, palette);
    }

    private void setAccentText(int id) {
        TextView tv = findViewById(id);
        if (tv != null) tv.setTextColor(palette.accent);
    }

    private void setPrimaryText(int id) {
        TextView tv = findViewById(id);
        if (tv != null) tv.setTextColor(palette.textPrimary);
    }

    private void setSecondaryText(int id) {
        TextView tv = findViewById(id);
        if (tv != null) tv.setTextColor(palette.textSecondary);
    }

    private void bindControls() {
        btnBack.setOnClickListener(v -> finish());

        swHeadsetAudio.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) return;
            boolean running = SiQiRelayBridge.hasSession();
            Toast.makeText(
                    this,
                    isChecked
                            ? (running ? "已切为 iPhone 蓝牙耳机出声，正在重连 CarPlay..." : "已设为声音与 Siri 走 iPhone 蓝牙耳机")
                            : (running ? "已切回手机端播放音频，正在重连 CarPlay..." : "已切回手机端播放 CarPlay 音频"),
                    Toast.LENGTH_SHORT
            ).show();
            configExecutor.execute(() -> DiPlayConfigOptimizer.setHeadsetAudioDefault(SettingsActivity.this, isChecked, true));
        });

        swAutoConnect.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) return;
            AutoConnectManager.getInstance(this).setAutoConnectEnabled(isChecked);
            updateAutoConnectSummary();
            if (isChecked) {
                AutoConnectManager.getInstance(this).evaluateOnAppLaunch();
            }
        });

        swAutoBackground.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) return;
            AutoConnectManager.getInstance(this).setAutoBackgroundEnabled(isChecked);
        });

        rowBikeModelPicker.setOnClickListener(v -> showBikeModelPickerDialog());
        btnResolutionTuner.setOnClickListener(v -> startActivity(new Intent(this, ResolutionTunerActivity.class)));

        btnIconXl.setOnClickListener(v -> applyIconPreset(DiPlayConfigOptimizer.ICON_MODE_XL, "已选超大图标"));
        btnIconLarge.setOnClickListener(v -> applyIconPreset(DiPlayConfigOptimizer.ICON_MODE_LARGE, "已选大图标推荐"));
        btnIconStd.setOnClickListener(v -> applyIconPreset(DiPlayConfigOptimizer.ICON_MODE_STANDARD, "已选标准图标"));

        btnOptimizeConfig.setOnClickListener(v -> applyPresetConfigAndReconnect("已保存画面参数"));

        btnForceIdr.setOnClickListener(v -> {
            if (bridgeService != null && bridgeService.getClusterServer() != null) {
                bridgeService.getClusterServer().forceKeyframe();
            }
            Toast.makeText(this, "已刷新仪表画面", Toast.LENGTH_SHORT).show();
        });

        btnScanUsb.setOnClickListener(v -> {
            if (bridgeService != null && bridgeService.getDongleManager() != null) {
                bridgeService.getDongleManager().scanUsbDevices();
                Toast.makeText(this, "正在扫描 USB CarPlay 盒子...", Toast.LENGTH_SHORT).show();
            }
        });

        btnDiPlayAdvanced.setOnClickListener(v -> {
            try {
                SiQiRelayBridge.launchDiPlayHome(this, "settings");
            } catch (Exception e) {
                Toast.makeText(this, "打开失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        btnKeyUp.setOnClickListener(v -> HandlebarKeyBridge.getInstance().dispatchAction(
                HandlebarKeyBridge.KeyAction.UP, KeyEvent.KEYCODE_DPAD_UP, false));
        btnKeyDown.setOnClickListener(v -> HandlebarKeyBridge.getInstance().dispatchAction(
                HandlebarKeyBridge.KeyAction.DOWN, KeyEvent.KEYCODE_DPAD_DOWN, false));
        btnKeyConfirm.setOnClickListener(v -> HandlebarKeyBridge.getInstance().dispatchAction(
                HandlebarKeyBridge.KeyAction.CONFIRM, KeyEvent.KEYCODE_DPAD_CENTER, false));
        btnKeyBack.setOnClickListener(v -> HandlebarKeyBridge.getInstance().dispatchAction(
                HandlebarKeyBridge.KeyAction.BACK, KeyEvent.KEYCODE_BACK, false));

        handlebarKeyListener = (action, keyCode, isLongPress) -> runOnUiThread(() -> {
            if (tvSettingsKeyLog != null) {
                tvSettingsKeyLog.setText("最近触发: " + action.title + (isLongPress ? " [长按呼出Siri]" : ""));
                tvSettingsKeyLog.setTextColor(palette.accent);
            }
        });
        HandlebarKeyBridge.getInstance().addListener(handlebarKeyListener);

        btnOpenSourceCredits.setOnClickListener(v -> showOpenSourceCreditsDialog());
        btnAuthorGithub.setOnClickListener(v -> openExternalUrl(AUTHOR_GITHUB_URL));
        btnAuthorDouyin.setOnClickListener(v -> openExternalUrl(AUTHOR_DOUYIN_URL));
    }

    private void openExternalUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开链接: " + url, Toast.LENGTH_SHORT).show();
        }
    }

    private void showBikeModelPickerDialog() {
        DiPlayConfigOptimizer.BikePresetSpec[] presets = DiPlayConfigOptimizer.getAllPresets(this);
        CharSequence[] items = new CharSequence[presets.length];
        int currentId = DiPlayConfigOptimizer.getBikeModelPreset(this);
        int checkedIndex = 0;
        for (int i = 0; i < presets.length; i++) {
            items[i] = presets[i].title + "\n" + presets[i].description;
            if (presets[i].id == currentId) {
                checkedIndex = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("选择车型仪表预设")
                .setSingleChoiceItems(items, checkedIndex, (dialog, which) -> {
                    dialog.dismiss();
                    DiPlayConfigOptimizer.BikePresetSpec chosen = presets[which];
                    DiPlayConfigOptimizer.setBikeModelPreset(this, chosen.id);
                    updatePresetButtonsUi();
                    applyPresetConfigAndReconnect("已切换为「" + chosen.title + "」预设");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshAllStates() {
        swHeadsetAudio.setChecked(DiPlayConfigOptimizer.isHeadsetAudioDefault(this));
        AutoConnectManager acm = AutoConnectManager.getInstance(this);
        swAutoConnect.setChecked(acm.isAutoConnectEnabled());
        swAutoBackground.setChecked(acm.isAutoBackgroundEnabled());
        updateAutoConnectSummary();
        updatePresetButtonsUi();
        updateDongleStatus();
    }

    private void updateAutoConnectSummary() {
        AutoConnectManager acm = AutoConnectManager.getInstance(this);
        tvAutoConnectSummary.setText(acm.getStatusSummary());
        tvAutoConnectSummary.setTextColor(acm.isReadyForAutoConnect() ? palette.success : palette.textSecondary);
    }

    private void applyIconPreset(int mode, String msg) {
        DiPlayConfigOptimizer.setIconSizeMode(this, mode);
        updatePresetButtonsUi();
        applyPresetConfigAndReconnect(msg);
    }

    private void applyPresetConfigAndReconnect(String msg) {
        boolean running = SiQiRelayBridge.hasSession();
        int seq = applySeq.incrementAndGet();
        btnOptimizeConfig.setEnabled(false);
        btnOptimizeConfig.setText(running ? "正在应用并重连 CarPlay..." : "正在保存画面参数...");
        configExecutor.execute(() -> {
            boolean isLatest = (applySeq.get() == seq);
            boolean ok = DiPlayConfigOptimizer.applyOptimizedConfig(SettingsActivity.this, true, isLatest);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (applySeq.get() == seq) {
                    btnOptimizeConfig.setEnabled(true);
                    btnOptimizeConfig.setText("应用当前画面参数");
                    Toast.makeText(
                            SettingsActivity.this,
                            ok ? (running ? msg + "，正在重连生效" : msg + "，下次连接生效") : "写入失败",
                            Toast.LENGTH_SHORT
                    ).show();
                }
            });
        });
    }

    private void updatePresetButtonsUi() {
        DiPlayConfigOptimizer.BikePresetSpec spec = DiPlayConfigOptimizer.getActivePresetSpec(this);
        if (tvBikeModelValue != null) {
            tvBikeModelValue.setText(spec.title + "  ›");
        }
        if (tvBikeModelDesc != null) {
            tvBikeModelDesc.setText(spec.description);
        }

        int iconMode = DiPlayConfigOptimizer.getIconSizeMode(this);
        DiPlayStyle.styleSegmentedButton(btnIconXl, palette, iconMode == DiPlayConfigOptimizer.ICON_MODE_XL);
        DiPlayStyle.styleSegmentedButton(btnIconLarge, palette, iconMode == DiPlayConfigOptimizer.ICON_MODE_LARGE);
        DiPlayStyle.styleSegmentedButton(btnIconStd, palette, iconMode == DiPlayConfigOptimizer.ICON_MODE_STANDARD);
    }

    private void updateDongleStatus() {
        if (bridgeService == null || bridgeService.getDongleManager() == null) return;
        CarPlayDongleManager dm = bridgeService.getDongleManager();
        tvDongleStatus.setText(dm.isConnected() ? "有线 USB 盒子: 已连接" : "有线 USB 盒子: 未接入（默认使用无线连接）");
        tvDongleStatus.setTextColor(dm.isConnected() ? palette.success : palette.textSecondary);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (HandlebarKeyBridge.getInstance().onKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void showOpenSourceCreditsDialog() {
        String msg =
                "摩投 遵循以下开源项目原始协议并致谢：\n\n" +
                "1. DiPlay (GNU GPL-3.0)\n" +
                "   • 仓库: https://github.com/shihabal3amri/DiPlay\n" +
                "   • 作用: 提供无外置盒子 Android 原生无线/有线 CarPlay 接收引擎 (:common 与 :shared)\n\n" +
                "2. xcertplay (GNU GPL-3.0)\n" +
                "   • 仓库: https://github.com/shilapi/xcertplay\n" +
                "   • 作用: DiPlay 上游核心 iAP2 / AirPlay / HID 协议栈实现\n\n" +
                "3. DiAuto (GNU AGPL-3.0)\n" +
                "   • 仓库: https://github.com/shihabal3amri/DiAuto\n" +
                "   • 作用: 内置设置页与简洁卡片界面风格参考\n\n" +
                "4. 上游协议与第三方依赖致谢：\n" +
                "   • LIVI (github.com/f-io/LIVI) & Showcase (github.com/amineross/showcase)\n" +
                "   • SpeexDSP 1.2.1 (BSD-3-Clause) · Concentus (Opus BSD)\n" +
                "   • AndroidX / Jetpack Compose / JmDNS (Apache-2.0) · Bouncy Castle (MIT)\n\n" +
                "完整许可文本详见源码根目录 LICENSE 与 docs/THIRD_PARTY_NOTICES.md。\n" +
                "CarPlay 为 Apple Inc. 注册商标。";
        new AlertDialog.Builder(this)
                .setTitle("开源项目声明与致谢")
                .setMessage(msg)
                .setPositiveButton("确定", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        configExecutor.shutdown();
        if (handlebarKeyListener != null) {
            HandlebarKeyBridge.getInstance().removeListener(handlebarKeyListener);
            handlebarKeyListener = null;
        }
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}
