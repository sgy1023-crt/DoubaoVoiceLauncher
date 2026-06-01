package com.example.doubaoVoiceLauncher;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;

/**
 * 屏幕截图工具类
 * 方式1: AccessibilityService.takeScreenshot() (Android 11+)
 * 方式2: screencap 命令 (需要root或ADB权限)
 */
public class ScreenCaptureHelper {

    private static final String TAG = "ScreenCaptureHelper";

    private AccessibilityService service;
    private int screenWidth;
    private int screenHeight;

    public interface ScreenshotCallback {
        void onScreenshot(Bitmap bitmap);
        void onError(String error);
    }

    public ScreenCaptureHelper(AccessibilityService service) {
        this.service = service;

        DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
    }

    /**
     * 截取当前屏幕
     */
    public void takeScreenshot(ScreenshotCallback callback) {
        // 方式1: Android 11+ 官方API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshotAccessibilityApi(callback);
            return;
        }

        // 方式2: screencap 命令
        Bitmap screencapBitmap = takeScreenshotScreencap();
        if (screencapBitmap != null) {
            callback.onScreenshot(screencapBitmap);
            return;
        }

        // 方式3: 全部失败
        callback.onError("当前设备不支持截图，请使用Android 11+设备或授予ADB权限");
    }

    /**
     * Android 11+ 无障碍服务截图API
     */
    private void takeScreenshotAccessibilityApi(ScreenshotCallback callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            service.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    service.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            try {
                                android.hardware.HardwareBuffer hardwareBuffer = result.getHardwareBuffer();
                                if (hardwareBuffer == null) {
                                    callback.onError("截图HardwareBuffer为空");
                                    return;
                                }
                                Bitmap bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null);
                                hardwareBuffer.close();
                                if (bitmap != null) {
                                    // 转为软件位图以便处理像素
                                    Bitmap softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                                    bitmap.recycle();
                                    Log.d(TAG, "截图成功: " + softBitmap.getWidth() + "x" + softBitmap.getHeight());
                                    callback.onScreenshot(softBitmap);
                                } else {
                                    callback.onError("截图转换失败");
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "处理截图失败", e);
                                // 回退到screencap
                                Bitmap screencapBitmap = takeScreenshotScreencap();
                                if (screencapBitmap != null) {
                                    callback.onScreenshot(screencapBitmap);
                                } else {
                                    callback.onError("处理截图失败: " + e.getMessage());
                                }
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            Log.e(TAG, "截图失败，错误码: " + errorCode);
                            // 回退到screencap
                            Bitmap screencapBitmap = takeScreenshotScreencap();
                            if (screencapBitmap != null) {
                                callback.onScreenshot(screencapBitmap);
                            } else {
                                callback.onError("截图失败，错误码: " + errorCode);
                            }
                        }
                    }
            );
        }
    }

    /**
     * 使用 screencap 命令截图（需要root或ADB shell权限）
     */
    private Bitmap takeScreenshotScreencap() {
        try {
            String outputPath = service.getCacheDir() + "/screenshot.png";
            Process process = Runtime.getRuntime().exec("screencap -p " + outputPath);
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                File file = new File(outputPath);
                if (file.exists() && file.length() > 0) {
                    Bitmap bitmap = BitmapFactory.decodeFile(outputPath);
                    file.delete();
                    return bitmap;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "screencap命令不可用: " + e.getMessage());
        }
        return null;
    }

    /**
     * 裁剪指定区域
     */
    public static Bitmap cropRegion(Bitmap source, int left, int top, int right, int bottom) {
        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(source.getWidth(), right);
        bottom = Math.min(source.getHeight(), bottom);

        if (right <= left || bottom <= top) return null;

        return Bitmap.createBitmap(source, left, top, right - left, bottom - top);
    }

    /**
     * 获取屏幕尺寸
     */
    public int getScreenWidth() { return screenWidth; }
    public int getScreenHeight() { return screenHeight; }
}

