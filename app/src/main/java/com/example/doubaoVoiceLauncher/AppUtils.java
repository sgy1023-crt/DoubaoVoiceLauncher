package com.example.doubaoVoiceLauncher;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

public class AppUtils {

    /**
     * 检查无障碍服务是否已开启
     */
    public static boolean isAccessibilityServiceEnabled(Context context, Class<?> serviceClass) {
        String serviceName = context.getPackageName() + "/" + serviceClass.getCanonicalName();
        String enabledServices = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabledServices)) {
            return false;
        }
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            String componentName = splitter.next();
            if (componentName.equalsIgnoreCase(serviceName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查豆包APP是否已安装
     */
    public static boolean isDoubaoInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo("com.larus.nova", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 豆包APP包名
     */
    public static final String DOUBAO_PACKAGE = "com.larus.nova";
}
