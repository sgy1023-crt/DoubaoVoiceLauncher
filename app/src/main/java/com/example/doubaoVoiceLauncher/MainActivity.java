package com.example.doubaoVoiceLauncher;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.RecognitionService;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import java.util.List;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_RECORD_AUDIO = 1001;

    private TextView tvAccessibilityStatus;
    private TextView tvListeningStatus;
    private TextView tvEngineInfo;
    private View statusIndicator;
    private ImageView ivAccessibilityIcon;
    private Button btnOpenAccessibility;
    private FloatingActionButton fabToggle;

    private boolean isListening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus);
        tvListeningStatus = findViewById(R.id.tvListeningStatus);
        tvEngineInfo = findViewById(R.id.tvEngineInfo);
        statusIndicator = findViewById(R.id.statusIndicator);
        ivAccessibilityIcon = findViewById(R.id.ivAccessibilityIcon);
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility);
        fabToggle = findViewById(R.id.fabToggle);

        // FAB 点击事件
        fabToggle.setOnClickListener(v -> toggleListening());

        btnOpenAccessibility.setOnClickListener(v -> openAccessibilitySettings());

        findViewById(R.id.btnSettings).setOnClickListener(v -> openSettings());

        // 检测并显示可用的语音引擎
        detectAndShowEngines();

        // 请求电池优化豁免（防止国产手机杀后台）
        requestBatteryOptimizationExemption();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccessibilityStatus();
        updateListeningStatus();
        // 通知服务：APP在前台，用SpeechRecognizer
        if (VoiceListenerService.isRunning()) {
            Intent intent = new Intent(this, VoiceListenerService.class);
            intent.setAction("ACTION_FOREGROUND");
            startService(intent);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 通知服务：APP切到后台，切AudioRecord模式
        if (VoiceListenerService.isRunning()) {
            Intent intent = new Intent(this, VoiceListenerService.class);
            intent.setAction("ACTION_BACKGROUND");
            startService(intent);
        }
    }

    /**
     * 切换监听状态（FAB 主操作）
     */
    private void toggleListening() {
        if (isListening) {
            stopListening();
        } else {
            startListening();
        }
    }

    /**
     * 检测系统中已安装的语音识别引擎并显示
     */
    private void detectAndShowEngines() {
        PackageManager pm = getPackageManager();
        Intent serviceIntent = new Intent(RecognitionService.SERVICE_INTERFACE);
        List<ResolveInfo> resolveInfos = pm.queryIntentServices(serviceIntent, PackageManager.MATCH_ALL);

        StringBuilder sb = new StringBuilder();
        sb.append("检测到的语音引擎:\n");

        if (resolveInfos == null || resolveInfos.isEmpty()) {
            sb.append("  未发现第三方语音引擎\n");
            sb.append("  将使用系统默认引擎");
        } else {
            for (ResolveInfo info : resolveInfos) {
                String pkg = info.serviceInfo.packageName;
                sb.append("  · ").append(getEngineDisplayName(pkg));
                sb.append("\n    (").append(pkg).append(")\n");
            }
        }

        tvEngineInfo.setText(sb.toString());
    }

    /**
     * 获取引擎的中文显示名称
     */
    private String getEngineDisplayName(String packageName) {
        switch (packageName) {
            case "com.iflytek.speechcloud":
            case "com.iflytek.vflynote":
                return "科大讯飞";
            case "com.baidu.speech":
            case "com.baidu.duersdk":
                return "百度语音";
            case "com.miui.voiceassist":
            case "com.xiaomi.aiasst.service":
                return "小米语音";
            case "com.huawei.vassistant":
            case "com.huawei.hiassistant":
                return "华为语音";
            case "com.heytap.speech":
                return "OPPO语音";
            case "com.vivo.voiceassistant":
                return "Vivo语音";
            case "com.samsung.android.bixby.agent":
            case "com.samsung.android.svoice":
                return "三星语音";
            case "com.google.android.googlequicksearchbox":
            case "com.google.android.voicesearch":
                return "Google语音";
            default:
                return packageName;
        }
    }

    private void updateAccessibilityStatus() {
        if (AppUtils.isAccessibilityServiceEnabled(this, DoubaoAccessibilityService.class)) {
            tvAccessibilityStatus.setText(R.string.status_accessibility_on);
            tvAccessibilityStatus.setTextColor(getResources().getColor(R.color.md_theme_onSurface));
            btnOpenAccessibility.setVisibility(View.GONE);

            // 更新图标为开启状态
            ivAccessibilityIcon.setImageResource(R.drawable.ic_accessibility_on);
        } else {
            tvAccessibilityStatus.setText(R.string.status_accessibility_off);
            tvAccessibilityStatus.setTextColor(getResources().getColor(R.color.md_theme_error));
            btnOpenAccessibility.setVisibility(View.VISIBLE);

            // 更新图标为关闭状态
            ivAccessibilityIcon.setImageResource(R.drawable.ic_accessibility_off);
        }
    }

    private void updateListeningStatus() {
        isListening = VoiceListenerService.isRunning();
        if (isListening) {
            tvListeningStatus.setText(R.string.status_listening);
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_listening);

            // FAB 切换为停止图标
            fabToggle.setImageResource(R.drawable.ic_mic_off);
            fabToggle.setBackgroundTintList(ColorStateList.valueOf(
                getResources().getColor(R.color.md_theme_tertiary)));

            // 卡片背景色变监听色
            findViewById(R.id.cardStatus).setBackgroundTintList(ColorStateList.valueOf(
                getResources().getColor(R.color.listening_glow)));

            Toast.makeText(this, "正在监听，请说「豆包豆包」", Toast.LENGTH_SHORT).show();
        } else {
            tvListeningStatus.setText(R.string.status_idle);
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_idle);

            // FAB 切换为开始图标
            fabToggle.setImageResource(R.drawable.ic_mic);
            fabToggle.setBackgroundTintList(ColorStateList.valueOf(
                getResources().getColor(R.color.md_theme_primary)));

            // 卡片背景色恢复
            findViewById(R.id.cardStatus).setBackgroundTintList(ColorStateList.valueOf(
                getResources().getColor(R.color.md_theme_primaryContainer)));
        }
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
        Toast.makeText(this, "请找到「豆包语音唤醒」并开启无障碍服务", Toast.LENGTH_LONG).show();
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /**
     * 请求电池优化豁免，防止后台被系统杀死
     */
    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    private void startListening() {
        // 检查录音权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }

        // 检查无障碍服务
        if (!AppUtils.isAccessibilityServiceEnabled(this, DoubaoAccessibilityService.class)) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show();
            openAccessibilitySettings();
            return;
        }

        // 启动语音监听服务
        Intent serviceIntent = new Intent(this, VoiceListenerService.class);
        ContextCompat.startForegroundService(this, serviceIntent);

        isListening = true;
        updateListeningStatus();
    }

    private void stopListening() {
        Intent serviceIntent = new Intent(this, VoiceListenerService.class);
        stopService(serviceIntent);

        isListening = false;
        updateListeningStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening();
            } else {
                Toast.makeText(this, "需要录音权限才能使用语音唤醒", Toast.LENGTH_SHORT).show();
            }
        }
    }
}