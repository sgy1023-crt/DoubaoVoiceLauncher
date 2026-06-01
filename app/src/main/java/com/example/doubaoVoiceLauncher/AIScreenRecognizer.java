package com.example.doubaoVoiceLauncher;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AI 视觉识别
 * 截图发给AI大模型，让AI识别打电话按钮的位置
 *
 * 支持的API:
 * 1. 豆包(Doubao) API - 字节跳动
 * 2. 通义千问 API - 阿里
 * 3. 其他兼容 OpenAI 格式的 API
 */
public class AIScreenRecognizer {

    private static final String TAG = "AIScreenRecognizer";

    // API配置（可在设置中修改）
    private String apiUrl = "https://ark.cn-beijing.volces.com/api/v3/chat/completions";
    private String apiKey = "";
    private String modelId = "doubao-vision-pro-32k";

    private OkHttpClient client;

    public interface RecognizeCallback {
        /**
         * @param x 按钮中心X坐标
         * @param y 按钮中心Y坐标
         * @param width 按钮宽度
         * @param height 按钮高度
         * @param description AI的描述
         */
        void onResult(int x, int y, int width, int height, String description);
        void onError(String error);
    }

    public AIScreenRecognizer() {
        client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 设置API配置
     */
    public void setConfig(String apiUrl, String apiKey, String modelId) {
        if (apiUrl != null && !apiUrl.isEmpty()) this.apiUrl = apiUrl;
        if (apiKey != null && !apiKey.isEmpty()) this.apiKey = apiKey;
        if (modelId != null && !modelId.isEmpty()) this.modelId = modelId;
    }

    /**
     * 识别屏幕截图中的打电话按钮
     */
    public void recognizeCallButton(Bitmap screenshot, int screenWidth, int screenHeight,
                                     RecognizeCallback callback) {
        if (apiKey == null || apiKey.isEmpty()) {
            callback.onError("未配置AI API Key，请在设置中配置");
            return;
        }

        // 缩小截图以减少传输大小
        Bitmap resized = resizeBitmap(screenshot, 720);
        String base64Image = bitmapToBase64(resized);
        resized.recycle();

        String prompt = buildPrompt(screenWidth, screenHeight);

        try {
            JSONObject requestBody = buildRequestBody(base64Image, prompt);
            Request request = new Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(
                            MediaType.parse("application/json"),
                            requestBody.toString()))
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "AI请求失败", e);
                    callback.onError("网络请求失败: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String body = response.body().string();
                        if (!response.isSuccessful()) {
                            callback.onError("API返回错误: " + response.code() + "\n" + body);
                            return;
                        }

                        Point result = parseResponse(body, screenWidth, screenHeight);
                        if (result != null) {
                            // 宽高给默认估计值（AI只返回坐标，不返回尺寸）
                            callback.onResult(result.x, result.y, 80, 80, "AI识别");
                        } else {
                            callback.onError("AI未能识别到打电话按钮");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "解析AI响应失败", e);
                        callback.onError("解析响应失败: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "构建请求失败", e);
            callback.onError("构建请求失败: " + e.getMessage());
        }
    }

    /**
     * 构建提示词
     */
    private String buildPrompt(int screenWidth, int screenHeight) {
        return "这是一张Android手机屏幕截图，屏幕分辨率是 " + screenWidth + "x" + screenHeight + "。\n\n" +
                "请找到屏幕上的「打电话」或「语音通话」按钮（通常是一个电话图标，一般在界面右上角）。\n\n" +
                "请严格按以下JSON格式返回按钮的中心坐标：\n" +
                "{\"x\": 坐标数值, \"y\": 坐标数值, \"found\": true}\n\n" +
                "如果找不到，返回：\n" +
                "{\"x\": 0, \"y\": 0, \"found\": false}\n\n" +
                "注意：\n" +
                "1. x是水平坐标（从左到右），y是垂直坐标（从上到下）\n" +
                "2. 坐标要基于实际屏幕分辨率 " + screenWidth + "x" + screenHeight + "\n" +
                "3. 只返回JSON，不要其他文字";
    }

    /**
     * 构建请求体（兼容OpenAI格式）
     */
    private JSONObject buildRequestBody(String base64Image, String prompt) throws Exception {
        JSONObject imageContent = new JSONObject();
        imageContent.put("type", "image_url");
        JSONObject imageUrl = new JSONObject();
        imageUrl.put("url", "data:image/png;base64," + base64Image);
        imageContent.put("image_url", imageUrl);

        JSONObject textContent = new JSONObject();
        textContent.put("type", "text");
        textContent.put("text", prompt);

        JSONArray contentArray = new JSONArray();
        contentArray.put(imageContent);
        contentArray.put(textContent);

        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", contentArray);

        JSONArray messages = new JSONArray();
        messages.put(message);

        JSONObject body = new JSONObject();
        body.put("model", modelId);
        body.put("messages", messages);
        body.put("max_tokens", 500);

        return body;
    }

    /**
     * 解析AI响应
     */
    private Point parseResponse(String responseBody, int screenWidth, int screenHeight) {
        try {
            JSONObject response = new JSONObject(responseBody);
            JSONArray choices = response.getJSONArray("choices");
            if (choices.length() == 0) return null;

            String content = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim();

            Log.d(TAG, "AI响应: " + content);

            // 提取JSON部分
            int jsonStart = content.indexOf('{');
            int jsonEnd = content.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String jsonStr = content.substring(jsonStart, jsonEnd + 1);
                JSONObject result = new JSONObject(jsonStr);

                if (result.optBoolean("found", false)) {
                    int x = result.optInt("x", 0);
                    int y = result.optInt("y", 0);

                    // 验证坐标合理性
                    if (x > 0 && x < screenWidth && y > 0 && y < screenHeight) {
                        return new Point(x, y);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析AI响应失败", e);
        }
        return null;
    }

    /**
     * 缩放Bitmap到指定宽度（保持比例）
     */
    private Bitmap resizeBitmap(Bitmap bitmap, int maxWidth) {
        if (bitmap.getWidth() <= maxWidth) return bitmap;

        float ratio = (float) maxWidth / bitmap.getWidth();
        int newHeight = (int) (bitmap.getHeight() * ratio);
        return Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true);
    }

    /**
     * Bitmap转Base64
     */
    private String bitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 80, baos);
        byte[] bytes = baos.toByteArray();
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
}
