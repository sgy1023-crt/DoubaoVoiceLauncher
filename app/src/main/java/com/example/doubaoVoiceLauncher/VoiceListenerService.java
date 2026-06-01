package com.example.doubaoVoiceLauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 语音监听服务
 * 前台: SpeechRecognizer 识别"豆包"关键词
 * 后台: AudioRecord 声音检测（SpeechRecognizer在后台不能用，Android硬限制）
 */
public class VoiceListenerService extends Service {

    private static final String TAG = "VoiceListenerService";
    private static final String CHANNEL_ID = "voice_listener_channel";
    private static final int NOTIFICATION_ID = 1001;

    private static volatile boolean running = false;
    private PowerManager.WakeLock wakeLock;

    // SpeechRecognizer 相关（仅前台用）
    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;
    private boolean isListening = false;

    // AudioRecord 本地检测相关（前台+后台都能用）
    private AudioRecord audioRecord;
    private boolean isLocalListening = false;
    private short[] audioBuffer;
    private static final int SAMPLE_RATE = 16000;
    private static final int AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE_FACTOR = 2;

    // 音量阈值
    private static final double VOICE_THRESHOLD = 500.0;
    private static final int VOICE_FRAME_COUNT = 10;
    private int voiceFrameCounter = 0;
    private static final long COOL_DOWN = 5000;
    private long lastTriggerTime = 0;

    // 前台/后台状态
    private boolean isAppForeground = true;
    private List<ComponentName> availableEngines;
    private int currentEngineIndex = 0;
    private Handler handler = new Handler(Looper.getMainLooper());

    private static final long RESTART_DELAY = 500;
    private static final long ENGINE_SWITCH_DELAY = 1000;

    private static int currentModeStatic = -1;

    public static boolean isRunning() { return running; }
    public static int getCurrentMode() { return currentModeStatic; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        setupRecognizerIntent();
        detectAvailableEngines();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_STOP".equals(intent.getAction())) {
            requestStop();
            return START_NOT_STICKY;
        }

        // 处理前台/后台切换
        if (intent != null) {
            if ("ACTION_FOREGROUND".equals(intent.getAction())) {
                setForegroundState(true);
                return START_STICKY;
            }
            if ("ACTION_BACKGROUND".equals(intent.getAction())) {
                setForegroundState(false);
                return START_STICKY;
            }
        }

        startForeground(NOTIFICATION_ID, createNotification());
        running = true;
        currentModeStatic = isAppForeground ? 0 : 1;

        // 获取WakeLock
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "doubao:voiceListener");
            wakeLock.acquire();
            Log.d(TAG, "WakeLock已获取");
        }

        startListeningByMode();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        stopAllListening();
        releaseWakeLock();
        super.onDestroy();
    }

    /**
     * 通知服务：APP切到前台/后台
     */
    public void setForegroundState(boolean foreground) {
        boolean wasBackground = !isAppForeground;
        isAppForeground = foreground;
        Log.d(TAG, "APP状态: " + (foreground ? "前台" : "后台"));

        if (running) {
            stopAllListening();
            if (foreground && hasEngines()) {
                // 前台：用SpeechRecognizer，能识别"豆包"
                currentModeStatic = 0;
                startSpeechRecognizer();
            } else {
                // 后台：用AudioRecord，只检测声音
                currentModeStatic = 1;
                startLocalAudioDetection();
            }
            updateNotification();
        }
    }

    private boolean hasEngines() {
        return availableEngines != null && !availableEngines.isEmpty();
    }

    private void startListeningByMode() {
        if (isAppForeground && hasEngines()) {
            currentModeStatic = 0;
            startSpeechRecognizer();
        } else {
            currentModeStatic = 1;
            startLocalAudioDetection();
        }
        updateNotification();
    }

    private void stopAllListening() {
        isListening = false;
        destroyRecognizer();
        stopLocalAudioDetection();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
            Log.d(TAG, "WakeLock已释放");
        }
    }

    public void requestStop() {
        running = false;
        stopAllListening();
        currentModeStatic = -1;
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_DETACH);
        stopSelf();
    }

    // ===== SpeechRecognizer 模式（仅前台） =====

    private void startSpeechRecognizer() {
        if (isListening) return;
        if (!hasEngines()) {
            startLocalAudioDetection();
            return;
        }

        try {
            destroyRecognizer();
            ComponentName engine = getCurrentEngine();
            if (engine != null) {
                Log.d(TAG, "使用语音引擎: " + engine.getPackageName());
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this, engine);
            } else {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            }
            speechRecognizer.setRecognitionListener(new VoiceRecognitionListener());
            speechRecognizer.startListening(recognizerIntent);
            isListening = true;
            Log.d(TAG, "前台: SpeechRecognizer监听启动");
        } catch (Exception e) {
            Log.e(TAG, "启动语音识别失败", e);
            destroyRecognizer();
            switchToNextEngine();
            if (running) handler.postDelayed(this::startListeningByMode, ENGINE_SWITCH_DELAY);
        }
    }

    private void switchToNextEngine() {
        if (!hasEngines()) return;
        currentEngineIndex++;
        if (currentEngineIndex >= availableEngines.size()) {
            currentEngineIndex = 0;
        }
        destroyRecognizer();
    }

    private void destroyRecognizer() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer.stopListening();
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            } catch (Exception e) { }
            speechRecognizer = null;
        }
        isListening = false;
    }

    private ComponentName getCurrentEngine() {
        if (!hasEngines()) return null;
        if (currentEngineIndex >= availableEngines.size()) currentEngineIndex = 0;
        return availableEngines.get(currentEngineIndex);
    }

    private void setupRecognizerIntent() {
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
    }

    // ===== AudioRecord 模式（前台+后台都能用） =====

    private void startLocalAudioDetection() {
        if (isLocalListening) return;

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "无法获取音频缓冲区大小");
            return;
        }

        audioBuffer = new short[bufferSize * BUFFER_SIZE_FACTOR / 2];

        try {
            audioRecord = new AudioRecord(AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG,
                    AUDIO_FORMAT, bufferSize * BUFFER_SIZE_FACTOR);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败");
                return;
            }

            audioRecord.startRecording();
            isLocalListening = true;
            voiceFrameCounter = 0;
            Log.d(TAG, "本地音频检测启动 (前台/后台均可)");
            new Thread(this::readAudioLoop).start();
        } catch (Exception e) {
            Log.e(TAG, "启动AudioRecord失败", e);
        }
    }

    private void stopLocalAudioDetection() {
        isLocalListening = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) { }
            audioRecord = null;
        }
    }

    private void readAudioLoop() {
        while (isLocalListening && running) {
            try {
                int readCount = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                if (readCount > 0) {
                    double sum = 0;
                    for (int i = 0; i < readCount; i++) {
                        sum += audioBuffer[i] * audioBuffer[i];
                    }
                    double rms = Math.sqrt(sum / readCount);

                    if (rms > VOICE_THRESHOLD) {
                        voiceFrameCounter++;
                        if (voiceFrameCounter >= VOICE_FRAME_COUNT) {
                            long now = System.currentTimeMillis();
                            if (now - lastTriggerTime > COOL_DOWN) {
                                Log.d(TAG, "声音检测触发，音量=" + (int)rms);
                                lastTriggerTime = now;
                                voiceFrameCounter = 0;
                                handler.post(this::onKeywordDetected);
                            }
                        }
                    } else {
                        voiceFrameCounter = 0;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "读取音频数据异常", e);
                break;
            }
        }
    }

    // ===== 触发逻辑 =====

    private void onKeywordDetected() {
        Log.d(TAG, "触发！打开豆包APP");

        Intent clickIntent = new Intent("com.example.doubaoVoiceLauncher.ACTION_LAUNCH_DOUBAO");
        sendBroadcast(clickIntent);

        DoubaoAccessibilityService.triggerAutoClick();

        boolean launched = false;
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(AppUtils.DOUBAO_PACKAGE);
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launchIntent);
            launched = true;
            Log.d(TAG, "已通过getLaunchIntent打开豆包APP");
        }

        if (!launched) {
            try {
                Intent directIntent = new Intent(Intent.ACTION_MAIN);
                directIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                directIntent.setComponent(new ComponentName(
                        AppUtils.DOUBAO_PACKAGE,
                        "com.larus.home.impl.alias.AliasActivity1"));
                directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(directIntent);
                launched = true;
                Log.d(TAG, "已通过具体Activity打开豆包APP");
            } catch (Exception e) {
                Log.e(TAG, "具体Activity启动也失败", e);
            }
        }

        if (!launched) {
            Log.e(TAG, "无法打开豆包APP，请确认已安装");
        }

        stopAllListening();
        handler.postDelayed(() -> {
            if (running) startListeningByMode();
        }, COOL_DOWN);
    }

    // ===== 通知 =====

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, createNotification());
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        String modeText;
        if (isAppForeground && hasEngines()) {
            modeText = "语音识别(前台)";
        } else {
            modeText = "声音检测(后台)";
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText("模式: " + modeText + " - 等待「豆包豆包」")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("语音监听服务通知");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void detectAvailableEngines() {
        availableEngines = new ArrayList<>();
        PackageManager pm = getPackageManager();
        Intent serviceIntent = new Intent(RecognitionService.SERVICE_INTERFACE);
        List<ResolveInfo> resolveInfos = pm.queryIntentServices(serviceIntent, PackageManager.MATCH_ALL);

        if (resolveInfos != null && !resolveInfos.isEmpty()) {
            for (ResolveInfo info : resolveInfos) {
                String packageName = info.serviceInfo.packageName;
                String className = info.serviceInfo.name;
                availableEngines.add(new ComponentName(packageName, className));
                Log.d(TAG, "发现语音引擎: " + packageName);
            }
        }
        sortEnginesByPriority();
    }

    private void sortEnginesByPriority() {
        String[] priorityPackages = {
                "com.iflytek.speechcloud", "com.iflytek.vflynote",
                "com.baidu.speech", "com.baidu.duersdk",
                "com.miui.voiceassist", "com.xiaomi.aiasst.service",
                "com.huawei.vassistant", "com.huawei.hiassistant",
                "com.heytap.speech", "com.vivo.voiceassistant",
                "com.samsung.android.bixby.agent", "com.samsung.android.svoice",
        };
        List<ComponentName> sorted = new ArrayList<>();
        List<ComponentName> others = new ArrayList<>(availableEngines);
        for (String priorityPkg : priorityPackages) {
            for (int i = others.size() - 1; i >= 0; i--) {
                if (others.get(i).getPackageName().equals(priorityPkg)) {
                    sorted.add(others.remove(i));
                }
            }
        }
        sorted.addAll(others);
        availableEngines = sorted;
    }

    // ===== SpeechRecognizer 回调 =====

    private class VoiceRecognitionListener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { Log.d(TAG, "前台: 准备就绪"); }
        @Override public void onBeginningOfSpeech() { Log.d(TAG, "前台: 检测到语音输入"); }
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {
            Log.d(TAG, "前台: 语音输入结束");
            isListening = false;
        }

        @Override
        public void onError(int error) {
            String errorMsg;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH: errorMsg = "未识别到语音"; break;
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: errorMsg = "语音输入超时"; break;
                case SpeechRecognizer.ERROR_AUDIO: errorMsg = "音频错误"; break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: errorMsg = "网络错误"; break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: errorMsg = "权限不足"; break;
                case SpeechRecognizer.ERROR_CLIENT: errorMsg = "客户端错误"; break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: errorMsg = "识别引擎忙"; break;
                case SpeechRecognizer.ERROR_SERVER: errorMsg = "服务器错误"; break;
                default: errorMsg = "错误代码: " + error; break;
            }
            Log.d(TAG, "前台: 语音识别错误: " + errorMsg);

            if (error == SpeechRecognizer.ERROR_CLIENT
                    || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    || error == SpeechRecognizer.ERROR_SERVER) {
                destroyRecognizer();
                switchToNextEngine();
                if (running) handler.postDelayed(VoiceListenerService.this::startListeningByMode, ENGINE_SWITCH_DELAY);
                return;
            }

            if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                isListening = false;
                handler.postDelayed(() -> {
                    if (running) startSpeechRecognizer();
                }, RESTART_DELAY);
            }
        }

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                for (String result : matches) {
                    Log.d(TAG, "前台识别结果: " + result);
                    if (result.contains("豆包") || result.contains("doubao")) {
                        onKeywordDetected();
                        return;
                    }
                }
            }
            isListening = false;
            handler.postDelayed(() -> {
                if (running) startSpeechRecognizer();
            }, RESTART_DELAY);
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (partial != null && !partial.isEmpty()) {
                for (String result : partial) {
                    if (result.contains("豆包") || result.contains("doubao")) {
                        onKeywordDetected();
                        return;
                    }
                }
            }
        }

        @Override public void onEvent(int eventType, Bundle params) {}
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
