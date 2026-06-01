package com.example.doubaoVoiceLauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 图像匹配工具类
 * 使用多尺度模板匹配，不依赖OpenCV
 * 支持不同分辨率和屏幕尺寸
 */
public class ImageMatcher {

    private static final String TAG = "ImageMatcher";

    // 匹配置信度阈值
    private static final float MATCH_THRESHOLD = 0.7f;
    // 多尺度匹配的缩放比例范围
    private static final float SCALE_MIN = 0.5f;
    private static final float SCALE_MAX = 2.0f;
    private static final float SCALE_STEP = 0.1f;

    private Context context;

    public ImageMatcher(Context context) {
        this.context = context;
    }

    /**
     * 匹配结果
     */
    public static class MatchResult {
        public float confidence;  // 置信度 0-1
        public int x;            // 中心X坐标
        public int y;            // 中心Y坐标
        public int width;        // 匹配区域宽度
        public int height;       // 匹配区域高度
        public float scale;      // 匹配时的缩放比例

        public MatchResult(float confidence, int x, int y, int width, int height, float scale) {
            this.confidence = confidence;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.scale = scale;
        }

        public Rect toRect() {
            return new Rect(x - width / 2, y - height / 2, x + width / 2, y + height / 2);
        }

        @Override
        public String toString() {
            return String.format("Match{conf=%.2f, center=(%d,%d), size=%dx%d, scale=%.2f}",
                    confidence, x, y, width, height, scale);
        }
    }

    /**
     * 加载内置的打电话图标作为模板（从drawable资源）
     * 这样不需要用户手动放置模板文件
     */
    public Bitmap loadBuiltinCallIconTemplate() {
        try {
            Drawable drawable = ContextCompat.getDrawable(context, R.drawable.ic_phone_call);
            if (drawable == null) return null;

            // 生成多个尺寸中选择最合适的中等大小（80x80）作为基础模板
            int size = 80;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            // 白色背景（与豆包界面背景接近）
            canvas.drawColor(Color.WHITE);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);

            Log.d(TAG, "成功加载内置电话图标模板: " + size + "x" + size);
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "加载内置图标失败", e);
            return null;
        }
    }

    /**
     * 在屏幕截图中查找电话图标（优先内置图标，然后尝试assets文件）
     * @param screenshot 屏幕截图
     * @return 最佳匹配结果，null表示未找到
     */
    public MatchResult findCallIcon(Bitmap screenshot) {
        // 优先尝试 assets 中的用户自定义模板（精度最高）
        String[] assetTemplates = {"call_icon.png", "phone_icon.png", "call.png", "custom_call_icon.png"};
        for (String templateName : assetTemplates) {
            Bitmap template = loadTemplate(templateName);
            if (template != null) {
                Log.d(TAG, "使用assets模板: " + templateName);
                MatchResult result = findTemplate(screenshot, template);
                template.recycle();
                if (result != null) return result;
            }
        }

        // 然后使用内置drawable图标
        Bitmap builtinIcon = loadBuiltinCallIconTemplate();
        if (builtinIcon != null) {
            Log.d(TAG, "使用内置drawable图标模板");
            MatchResult result = findTemplate(screenshot, builtinIcon);
            builtinIcon.recycle();
            return result;
        }

        return null;
    }

    /**
     * 在屏幕截图中查找模板图标
     * @param screenshot 屏幕截图
     * @param templateName 模板图片文件名（放在 assets/templates/ 目录下）
     * @return 最佳匹配结果，null表示未找到
     */
    public MatchResult findTemplate(Bitmap screenshot, String templateName) {
        Bitmap template = loadTemplate(templateName);
        if (template == null) {
            return null;
        }

        MatchResult result = findTemplate(screenshot, template);
        template.recycle();
        return result;
    }

    /**
     * 在屏幕截图中查找模板图标（直接传入Bitmap）
     */
    public MatchResult findTemplate(Bitmap screenshot, Bitmap template) {
        if (screenshot == null || template == null) return null;

        Log.d(TAG, "屏幕: " + screenshot.getWidth() + "x" + screenshot.getHeight()
                + ", 模板: " + template.getWidth() + "x" + template.getHeight());

        List<MatchResult> candidates = new ArrayList<>();

        // 多尺度匹配
        for (float scale = SCALE_MIN; scale <= SCALE_MAX; scale += SCALE_STEP) {
            int scaledWidth = (int) (template.getWidth() * scale);
            int scaledHeight = (int) (template.getHeight() * scale);

            // 跳过太小或太大的缩放
            if (scaledWidth < 10 || scaledHeight < 10) continue;
            if (scaledWidth > screenshot.getWidth() || scaledHeight > screenshot.getHeight()) continue;

            // 缩放模板
            Bitmap scaledTemplate = Bitmap.createScaledBitmap(template, scaledWidth, scaledHeight, true);

            // 滑动窗口匹配
            MatchResult bestAtScale = slidingWindowMatch(screenshot, scaledTemplate, scale);
            if (bestAtScale != null) {
                candidates.add(bestAtScale);
            }

            if (scaledTemplate != template) {
                scaledTemplate.recycle();
            }
        }

        if (candidates.isEmpty()) {
            Log.d(TAG, "未找到匹配结果");
            return null;
        }

        // 返回置信度最高的匹配
        Collections.sort(candidates, (a, b) -> Float.compare(b.confidence, a.confidence));
        MatchResult best = candidates.get(0);
        Log.d(TAG, "最佳匹配: " + best);

        return best.confidence >= MATCH_THRESHOLD ? best : null;
    }

    /**
     * 在屏幕截图中查找模板（指定搜索区域）
     */
    public MatchResult findTemplateInRegion(Bitmap screenshot, Bitmap template, Rect region) {
        Bitmap cropped = ScreenCaptureHelper.cropRegion(screenshot,
                region.left, region.top, region.right, region.bottom);
        if (cropped == null) return null;

        MatchResult result = findTemplate(cropped, template);
        cropped.recycle();

        // 转换坐标到全屏坐标系
        if (result != null) {
            result.x += region.left;
            result.y += region.top;
        }

        return result;
    }

    /**
     * 滑动窗口模板匹配
     * 使用归一化互相关（NCC）算法
     */
    private MatchResult slidingWindowMatch(Bitmap screen, Bitmap template, float scale) {
        int tw = template.getWidth();
        int th = template.getHeight();
        int sw = screen.getWidth();
        int sh = screen.getHeight();

        if (tw > sw || th > sh) return null;

        // 步长（为了性能，不逐像素扫描）
        int stepX = Math.max(1, tw / 8);
        int stepY = Math.max(1, th / 8);

        float bestScore = -1;
        int bestX = 0;
        int bestY = 0;

        // 获取模板像素
        int[] templatePixels = new int[tw * th];
        template.getPixels(templatePixels, 0, tw, 0, 0, tw, th);

        // 预计算模板统计信息
        float[] templateR = new float[templatePixels.length];
        float[] templateG = new float[templatePixels.length];
        float[] templateB = new float[templatePixels.length];
        float templateMeanR = 0, templateMeanG = 0, templateMeanB = 0;

        for (int i = 0; i < templatePixels.length; i++) {
            templateR[i] = Color.red(templatePixels[i]);
            templateG[i] = Color.green(templatePixels[i]);
            templateB[i] = Color.blue(templatePixels[i]);
            templateMeanR += templateR[i];
            templateMeanG += templateG[i];
            templateMeanB += templateB[i];
        }
        templateMeanR /= templatePixels.length;
        templateMeanG /= templatePixels.length;
        templateMeanB /= templatePixels.length;

        // 计算模板标准差
        float templateStdR = 0, templateStdG = 0, templateStdB = 0;
        for (int i = 0; i < templatePixels.length; i++) {
            float dr = templateR[i] - templateMeanR;
            float dg = templateG[i] - templateMeanG;
            float db = templateB[i] - templateMeanB;
            templateStdR += dr * dr;
            templateStdG += dg * dg;
            templateStdB += db * db;
        }
        templateStdR = (float) Math.sqrt(templateStdR / templatePixels.length);
        templateStdG = (float) Math.sqrt(templateStdG / templatePixels.length);
        templateStdB = (float) Math.sqrt(templateStdB / templatePixels.length);

        // 滑动窗口
        int[] screenPixels = new int[tw * th];

        for (int y = 0; y <= sh - th; y += stepY) {
            for (int x = 0; x <= sw - tw; x += stepX) {
                // 获取当前窗口像素
                screen.getPixels(screenPixels, 0, tw, x, y, tw, th);

                // 计算NCC
                float score = calculateNCC(screenPixels, templateR, templateG, templateB,
                        templateMeanR, templateMeanG, templateMeanB,
                        templateStdR, templateStdG, templateStdB,
                        templatePixels.length);

                if (score > bestScore) {
                    bestScore = score;
                    bestX = x;
                    bestY = y;
                }
            }
        }

        if (bestScore < 0) return null;

        // 在最佳位置附近精细搜索
        int refineX = bestX, refineY = bestY;
        float refineScore = bestScore;
        int searchRadius = Math.max(stepX, stepY);

        for (int dy = -searchRadius; dy <= searchRadius; dy++) {
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                int nx = bestX + dx;
                int ny = bestY + dy;
                if (nx < 0 || ny < 0 || nx + tw > sw || ny + th > sh) continue;

                screen.getPixels(screenPixels, 0, tw, nx, ny, tw, th);
                float score = calculateNCC(screenPixels, templateR, templateG, templateB,
                        templateMeanR, templateMeanG, templateMeanB,
                        templateStdR, templateStdG, templateStdB,
                        templatePixels.length);

                if (score > refineScore) {
                    refineScore = score;
                    refineX = nx;
                    refineY = ny;
                }
            }
        }

        return new MatchResult(refineScore, refineX + tw / 2, refineY + th / 2, tw, th, scale);
    }

    /**
     * 计算归一化互相关系数（NCC）
     */
    private float calculateNCC(int[] screenPixels,
                                float[] tR, float[] tG, float[] tB,
                                float tMeanR, float tMeanG, float tMeanB,
                                float tStdR, float tStdG, float tStdB,
                                int length) {
        float sMeanR = 0, sMeanG = 0, sMeanB = 0;

        for (int i = 0; i < length; i++) {
            sMeanR += Color.red(screenPixels[i]);
            sMeanG += Color.green(screenPixels[i]);
            sMeanB += Color.blue(screenPixels[i]);
        }
        sMeanR /= length;
        sMeanG /= length;
        sMeanB /= length;

        float sStdR = 0, sStdG = 0, sStdB = 0;
        float corrR = 0, corrG = 0, corrB = 0;

        for (int i = 0; i < length; i++) {
            float sr = Color.red(screenPixels[i]) - sMeanR;
            float sg = Color.green(screenPixels[i]) - sMeanG;
            float sb = Color.blue(screenPixels[i]) - sMeanB;
            float tr = tR[i] - tMeanR;
            float tg = tG[i] - tMeanG;
            float tb = tB[i] - tMeanB;

            sStdR += sr * sr;
            sStdG += sg * sg;
            sStdB += sb * sb;
            corrR += sr * tr;
            corrG += sg * tg;
            corrB += sb * tb;
        }

        sStdR = (float) Math.sqrt(sStdR / length);
        sStdG = (float) Math.sqrt(sStdG / length);
        sStdB = (float) Math.sqrt(sStdB / length);

        // 避免除零
        if (sStdR < 0.001f || tStdR < 0.001f) return 0;
        if (sStdG < 0.001f || tStdG < 0.001f) return 0;
        if (sStdB < 0.001f || tStdB < 0.001f) return 0;

        float nccR = corrR / (length * sStdR * tStdR);
        float nccG = corrG / (length * sStdG * tStdG);
        float nccB = corrB / (length * sStdB * tStdB);

        // 三个通道的平均NCC
        return (nccR + nccG + nccB) / 3.0f;
    }

    /**
     * 加载assets中的模板图片（静默失败，文件不存在时返回null）
     */
    private Bitmap loadTemplate(String templateName) {
        try {
            InputStream is = context.getAssets().open("templates/" + templateName);
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            return bitmap;
        } catch (IOException e) {
            // 文件不存在时静默返回null（用户可能没放模板文件）
            return null;
        }
    }

    /**
     * 将Bitmap保存到文件（调试用）
     */
    public static void saveBitmap(Bitmap bitmap, String path) {
        try {
            FileOutputStream fos = new FileOutputStream(path);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "保存图片失败", e);
        }
    }
}
