package com.example.doubaoVoiceLauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * 开机自启广播接收器
 * 如果用户开启了自动监听，手机重启后自动恢复监听
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "收到开机广播");

            SharedPreferences prefs = context.getSharedPreferences("doubao_voice_config", Context.MODE_PRIVATE);
            boolean autoListen = prefs.getBoolean("auto_listen", true);

            if (autoListen && AppUtils.isAccessibilityServiceEnabled(context, DoubaoAccessibilityService.class)) {
                Log.d(TAG, "自动监听已开启，启动语音监听服务");
                Intent serviceIntent = new Intent(context, VoiceListenerService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } else {
                Log.d(TAG, "自动监听未开启或无障碍服务未启用，跳过");
            }
        }
    }
}