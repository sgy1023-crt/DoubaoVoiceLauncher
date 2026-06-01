package com.example.doubaoVoiceLauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

public class DoubaoAccessibilityService extends AccessibilityService {

    private static final String TAG = "DoubaoAccessibility";

    // 延迟点击时间（毫秒），等待豆包APP界面加载完成
    private static final long CLICK_DELAY = 1500;
    // 重试间隔
    private static final long RETRY_DELAY = 1000;
    // 最大重试次数
    private static final int MAX_RETRY = 5;

    private Handler handler;
    private boolean shouldAutoClick = false;
    private int retryCount = 0;
    private int screenWidth;
    private int screenHeight;

    private static DoubaoAccessibilityService instance;

    public static void triggerAutoClick() {
        if (instance != null) {
            instance.shouldAutoClick = true;
            instance.retryCount = 0;
        }
    }

    // 图像识别相关
    private ScreenCaptureHelper screenCaptureHelper;
    private ImageMatcher imageMatcher;
    private AIScreenRecognizer aiRecognizer;

    // 识别模式
    private static final int MODE_TEXT_ONLY = 0;      // 仅文字匹配
    private static final int MODE_TEMPLATE_MATCH = 1;  // 文字 + 模板匹配
    private static final int MODE_AI_RECOGNIZE = 2;    // 文字 + 模板 + AI
    private int recognizeMode = MODE_TEMPLATE_MATCH;

    private BroadcastReceiver launchReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.doubaoVoiceLauncher.ACTION_LAUNCH_DOUBAO".equals(intent.getAction())) {
                Log.d(TAG, "收到启动豆包广播，准备自动点击");
                shouldAutoClick = true;
                retryCount = 0;
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        handler = new Handler(Looper.getMainLooper());

        IntentFilter filter = new IntentFilter("com.example.doubaoVoiceLauncher.ACTION_LAUNCH_DOUBAO");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(launchReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(launchReceiver, filter);
        }

        // 获取屏幕尺寸
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        Log.d(TAG, "屏幕尺寸: " + screenWidth + " x " + screenHeight);

        // 初始化图像识别
        screenCaptureHelper = new ScreenCaptureHelper(this);
        imageMatcher = new ImageMatcher(this);
        aiRecognizer = new AIScreenRecognizer();

        // 加载AI配置
        loadAIConfig();
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(launchReceiver);
        } catch (Exception e) {
            Log.e(TAG, "注销广播接收器失败", e);
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int eventType = event.getEventType();

        // 监听窗口变化事件
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence packageName = event.getPackageName();
            if (packageName != null && AppUtils.DOUBAO_PACKAGE.equals(packageName.toString())) {
                Log.d(TAG, "检测到豆包APP窗口变化");

                if (shouldAutoClick) {
                    handler.postDelayed(this::performAutoClick, CLICK_DELAY);
                }
            }
        }

        // 监听内容变化，按钮可能是动态加载的
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && shouldAutoClick) {
            CharSequence packageName = event.getPackageName();
            if (packageName != null && AppUtils.DOUBAO_PACKAGE.equals(packageName.toString())) {
                handler.postDelayed(this::performAutoClick, 500);
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "无障碍服务被中断");
    }

    /**
     * 加载AI配置
     */
    private void loadAIConfig() {
        SharedPreferences prefs = getSharedPreferences("doubao_voice_config", MODE_PRIVATE);
        String apiUrl = prefs.getString("ai_api_url", "");
        String apiKey = prefs.getString("ai_api_key", "");
        String modelId = prefs.getString("ai_model_id", "");
        recognizeMode = prefs.getInt("recognize_mode", MODE_TEMPLATE_MATCH);

        aiRecognizer.setConfig(apiUrl, apiKey, modelId);
        Log.d(TAG, "加载AI配置: mode=" + recognizeMode + ", url=" + apiUrl);
    }

    /**
     * 执行自动点击操作（主流程）
     */
    private void performAutoClick() {
        if (!shouldAutoClick) return;

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            Log.e(TAG, "无法获取根节点");
            retryClick();
            return;
        }

        boolean clicked = false;

        // === 策略1: 文字匹配（最快，优先尝试） ===
        clicked = tryTextMatch(rootNode);

        // === 策略2: 位置特征匹配 ===
        if (!clicked) {
            clicked = tryPositionMatch(rootNode);
        }

        // === 策略3: 模板图像匹配 ===
        if (!clicked && recognizeMode >= MODE_TEMPLATE_MATCH) {
            clicked = tryTemplateMatch();
        }

        // === 策略4: AI视觉识别 ===
        if (!clicked && recognizeMode >= MODE_AI_RECOGNIZE) {
            tryAIRecognize();
            // AI是异步的，不在这里处理结果
            rootNode.recycle();
            return;
        }

        if (clicked) {
            Log.d(TAG, "成功自动点击打电话按钮");
            shouldAutoClick = false;
            retryCount = 0;
        } else {
            Log.d(TAG, "未找到打电话按钮，尝试重试");
            retryClick();
        }

        rootNode.recycle();
    }

    /**
     * 文字匹配策略
     */
    private boolean tryTextMatch(AccessibilityNodeInfo rootNode) {
        String[] keywords = {"打电话", "语音通话", "通话", "电话", "拨打", "call", "Call"};
        for (String keyword : keywords) {
            if (findAndClickByText(rootNode, keyword)) return true;
        }

        String[] descKeywords = {"打电话", "语音通话", "通话", "电话", "拨打", "call", "phone"};
        for (String keyword : descKeywords) {
            if (findAndClickByDescription(rootNode, keyword)) return true;
        }

        return false;
    }

    /**
     * 位置特征匹配策略
     */
    private boolean tryPositionMatch(AccessibilityNodeInfo rootNode) {
        // 右上角区域查找电话相关按钮
        if (findAndClickTopRightPhoneButton(rootNode)) return true;
        // 按位置+大小特征查找
        if (findClickablePhoneNodeByPosition(rootNode)) return true;
        // 兜底：右上角小图标
        return findTopRightIconButton(rootNode);
    }

    /**
     * 模板图像匹配策略
     */
    private boolean tryTemplateMatch() {
        try {
            final boolean[] result = {false};

            screenCaptureHelper.takeScreenshot(new ScreenCaptureHelper.ScreenshotCallback() {
                @Override
                public void onScreenshot(Bitmap screenshot) {
                    // 使用 findCallIcon 方法：优先 assets 自定义模板，其次内置 drawable 图标
                    ImageMatcher.MatchResult match = imageMatcher.findCallIcon(screenshot);
                    if (match != null) {
                        Log.d(TAG, "图标模板匹配成功: " + match);
                        clickAtPosition(match.x, match.y);
                        result[0] = true;
                        shouldAutoClick = false;
                        retryCount = 0;
                    } else {
                        Log.d(TAG, "模板匹配未找到目标");
                    }

                    screenshot.recycle();
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "截图失败: " + error);
                }
            });

            return result[0];
        } catch (Exception e) {
            Log.e(TAG, "模板匹配异常", e);
            return false;
        }
    }

    /**
     * AI视觉识别策略（异步）
     */
    private void tryAIRecognize() {
        screenCaptureHelper.takeScreenshot(new ScreenCaptureHelper.ScreenshotCallback() {
            @Override
            public void onScreenshot(Bitmap screenshot) {
                aiRecognizer.recognizeCallButton(screenshot, screenWidth, screenHeight,
                        new AIScreenRecognizer.RecognizeCallback() {
                            @Override
                            public void onResult(int x, int y, int width, int height, String description) {
                                Log.d(TAG, "AI识别成功: (" + x + "," + y + ") " + description);
                                handler.post(() -> {
                                    clickAtPosition(x, y);
                                    shouldAutoClick = false;
                                    retryCount = 0;
                                });
                                screenshot.recycle();
                            }

                            @Override
                            public void onError(String error) {
                                Log.e(TAG, "AI识别失败: " + error);
                                handler.post(() -> retryClick());
                                screenshot.recycle();
                            }
                        });
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "截图失败: " + error);
                handler.post(() -> retryClick());
            }
        });
    }

    /**
     * 在指定坐标执行点击
     */
    private boolean clickAtPosition(float x, float y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path clickPath = new Path();
            clickPath.moveTo(x, y);

            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, 100));

            boolean dispatched = dispatchGesture(builder.build(), null, null);
            Log.d(TAG, "点击坐标 (" + x + "," + y + "): " + (dispatched ? "成功" : "失败"));
            return dispatched;
        }
        return false;
    }

    /**
     * 重试点击
     */
    private void retryClick() {
        retryCount++;
        if (retryCount < MAX_RETRY) {
            Log.d(TAG, "第 " + retryCount + " 次重试");
            handler.postDelayed(this::performAutoClick, RETRY_DELAY);
        } else {
            Log.e(TAG, "达到最大重试次数，放弃自动点击");
            shouldAutoClick = false;
            retryCount = 0;
        }
    }

    // ========== 文字匹配相关方法 ==========

    private boolean findAndClickByText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                if (tryPerformClick(node, "文字:" + text)) return true;
            }
        }
        return false;
    }

    private boolean findAndClickByDescription(AccessibilityNodeInfo root, String description) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(description);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                CharSequence desc = node.getContentDescription();
                if (desc != null && desc.toString().toLowerCase().contains(description.toLowerCase())) {
                    if (tryPerformClick(node, "描述:" + description)) return true;
                }
            }
        }
        return false;
    }

    private boolean findAndClickTopRightPhoneButton(AccessibilityNodeInfo root) {
        int rightHalfStart = screenWidth / 2;
        int topHalfEnd = screenHeight / 3;

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectNodesInRegion(root, rightHalfStart, 0, screenWidth, topHalfEnd, candidates, 0);

        for (AccessibilityNodeInfo node : candidates) {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            String textStr = text != null ? text.toString().toLowerCase() : "";
            String descStr = desc != null ? desc.toString().toLowerCase() : "";

            if (textStr.contains("电话") || textStr.contains("通话")
                    || descStr.contains("电话") || descStr.contains("通话")
                    || textStr.contains("拨打") || descStr.contains("拨打")
                    || textStr.contains("call") || descStr.contains("call")) {
                if (tryPerformClick(node, "右上角电话按钮")) return true;
            }
        }
        return false;
    }

    private boolean findClickablePhoneNodeByPosition(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> allClickable = new ArrayList<>();
        collectAllClickable(root, allClickable, 0);

        int rightAreaStart = screenWidth * 2 / 3;
        int topAreaEnd = screenHeight / 4;

        for (AccessibilityNodeInfo node : allClickable) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);

            if (bounds.left >= rightAreaStart && bounds.bottom <= topAreaEnd) {
                int width = bounds.width();
                int height = bounds.height();

                if (width > 30 && width < 250 && height > 30 && height < 250) {
                    CharSequence text = node.getText();
                    CharSequence desc = node.getContentDescription();
                    String textStr = text != null ? text.toString() : "";
                    String descStr = desc != null ? desc.toString() : "";

                    if (isPhoneRelatedText(textStr) || isPhoneRelatedText(descStr)) {
                        if (tryPerformClick(node, "右上角图标按钮")) return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean findTopRightIconButton(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> allClickable = new ArrayList<>();
        collectAllClickable(root, allClickable, 0);

        int rightAreaStart = screenWidth / 2;
        int topAreaEnd = screenHeight / 3;

        List<AccessibilityNodeInfo> topRightButtons = new ArrayList<>();

        for (AccessibilityNodeInfo node : allClickable) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);

            if (bounds.left >= rightAreaStart && bounds.bottom <= topAreaEnd) {
                int width = bounds.width();
                int height = bounds.height();
                if (width > 30 && width < 200 && height > 30 && height < 200) {
                    topRightButtons.add(node);
                }
            }
        }

        if (!topRightButtons.isEmpty()) {
            for (int i = topRightButtons.size() - 1; i >= 0; i--) {
                if (tryPerformClick(topRightButtons.get(i), "右上角图标(位置推断)")) return true;
            }
        }
        return false;
    }

    // ========== 辅助方法 ==========

    private boolean isPhoneRelatedText(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase();
        return lower.contains("电话") || lower.contains("通话")
                || lower.contains("拨打") || lower.contains("call")
                || lower.contains("phone") || lower.contains("语音");
    }

    private void collectNodesInRegion(AccessibilityNodeInfo node, int left, int top,
                                       int right, int bottom, List<AccessibilityNodeInfo> result,
                                       int depth) {
        if (node == null || depth > 20) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.left >= left && bounds.top >= top
                && bounds.right <= right && bounds.bottom <= bottom) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectNodesInRegion(child, left, top, right, bottom, result, depth + 1);
                child.recycle();
            }
        }
    }

    private void collectAllClickable(AccessibilityNodeInfo node,
                                      List<AccessibilityNodeInfo> result, int depth) {
        if (node == null || depth > 20) return;
        if (node.isClickable() && node.isVisibleToUser()) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectAllClickable(child, result, depth + 1);
                child.recycle();
            }
        }
    }

    private boolean tryPerformClick(AccessibilityNodeInfo node, String source) {
        if (node == null) return false;

        if (node.isClickable() && node.isEnabled()) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.d(TAG, "点击成功 [" + source + "]");
            return true;
        }

        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            if (parent.isClickable() && parent.isEnabled()) {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.d(TAG, "点击父节点成功 [" + source + "]");
                parent.recycle();
                return true;
            }
            parent.recycle();
        }

        if (node.isEnabled()) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "聚焦后点击成功 [" + source + "]");
                return true;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            float x = bounds.centerX();
            float y = bounds.centerY();
            if (x > 0 && y > 0) {
                if (clickAtPosition(x, y)) {
                    Log.d(TAG, "手势模拟点击成功 [" + source + "] (" + x + "," + y + ")");
                    return true;
                }
            }
        }

        return false;
    }
}
