# 豆包语音唤醒 (DoubaoVoiceLauncher)

一个 Android 小工具，对着手机说「豆包豆包」，自动打开豆包 APP 并点击语音通话按钮。

## 功能

- **语音唤醒**：说出「豆包豆包」即可触发，无需手动操作
- **自动跳转**：唤醒后自动打开豆包 APP
- **自动点击**：通过无障碍服务 + 模板匹配，自动点击聊天页右上角的打电话按钮
- **后台监听**：APP 退到后台仍可继续监听（前台用 SpeechRecognizer，后台自动切换 AudioRecord 本地检测）
- **开机自启**：支持开机自动启动监听服务
- **状态栏通知**：前台服务常驻通知，保持后台存活
- **电池优化豁免**：自动请求忽略电池优化，降低被系统杀掉的概率

## 工作原理

```
语音监听 (VoiceListenerService)
    ↓ 检测到「豆包豆包」
打开豆包 APP (Intent)
    ↓ 跳转到聊天页
无障碍服务 (DoubaoAccessibilityService)
    ↓ 通过界面信息和电话图标模板定位按钮
自动点击 (performClick / 手势点击)
```

模板匹配的原理类似 OpenCV 的模板识别：APP 截取当前屏幕后，拿 `app/src/main/assets/templates/` 里的电话按钮小图作为参考，在屏幕里找到最像的位置，然后点击匹配到的坐标。

## 环境要求

- Android 7.0+ (API 24)
- 需要开启无障碍服务权限
- 需要授予录音权限
- 建议将 APP 加入电池优化白名单

## 使用方法

1. 安装 APK
2. 打开 APP，点击「开启无障碍服务」按钮，跳转到系统设置开启
3. 授予录音权限
4. 点击右下角麦克风 FAB 按钮开始监听
5. 对着手机说「豆包豆包」即可

## 项目结构

```
app/src/main/java/com/example/doubaoVoiceLauncher/
├── MainActivity.java              # 主界面
├── VoiceListenerService.java      # 语音监听前台服务
├── DoubaoAccessibilityService.java # 无障碍服务（自动点击）
├── ImageMatcher.java              # 模板匹配（找打电话按钮）
├── AIScreenRecognizer.java        # AI 屏幕识别
├── ScreenCaptureHelper.java       # 屏幕截图辅助
├── AppUtils.java                  # 工具类
├── BootReceiver.java              # 开机自启广播
└── SettingsActivity.java          # 设置页面

app/src/main/assets/templates/
└── call_icon.png                  # 打电话按钮模板图片
```

## 模板素材说明

模板图片是自动点击成功率的关键素材。项目会优先读取：

```text
app/src/main/assets/templates/call_icon.png
app/src/main/assets/templates/phone_icon.png
app/src/main/assets/templates/call.png
app/src/main/assets/templates/custom_call_icon.png
```

这些图片都应该是「电话 / 语音通话按钮」的小图标模板，不要放整屏截图。当前项目内置了 `call_icon.png` 作为默认模板；如果你的豆包版本按钮样式不同、自动点击不准，可以自己准备一张新的电话按钮小图，替换 `call_icon.png`，或按上面的文件名新增备用模板。

建议：

- 只截取按钮图标区域，不要包含聊天内容或个人信息
- 图片尺寸建议 50x50 ~ 200x200 像素
- 背景尽量接近按钮真实背景
- 豆包 APP 更新 UI 后，可能需要重新准备模板
- 文件名必须是上面列出的名称之一，否则代码不会加载

如果 assets 模板都匹配不上，APP 会继续尝试内置矢量电话图标作为兜底模板，但准确率可能不如实际按钮模板。

## 权限与隐私说明

本项目需要较敏感的 Android 权限，原因如下：

- 录音权限：监听「豆包豆包」唤醒词
- 无障碍服务：读取豆包界面节点，并模拟点击语音通话按钮
- 截图能力：用于模板匹配或 AI 视觉识别按钮位置
- 网络权限：仅 AI 识别模式下调用用户配置的视觉模型 API
- 开机自启 / 电池优化：用于保持后台监听服务稳定

默认的文字匹配、位置匹配和模板匹配都在本机完成。只有切换到 AI 识别模式时，APP 才会把当前屏幕截图发送到你配置的 API 地址；截图里可能包含聊天内容或其他个人信息，请只在信任对应 API 服务时使用 AI 识别模式。

## 技术栈

- Java
- Android SDK (API 24-34)
- SpeechRecognizer（前台语音识别）
- AudioRecord（后台声音检测）
- AccessibilityService（自动点击）
- 模板匹配（像素级图像对比）
- Material Design 3

## 编译

```bash
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

## 已知限制

- 模板匹配依赖豆包 APP 的 UI 布局，豆包更新后可能需要重新截图模板
- 后台监听在部分国产手机（Vivo、OPPO 等）上可能被系统杀掉，需要手动设置电池白名单
- 「豆包豆包」的唤醒词是硬编码的，暂不支持自定义

## License

MIT
