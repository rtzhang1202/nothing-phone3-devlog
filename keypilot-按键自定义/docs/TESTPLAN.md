# KeyPilot 真机功能与回归测试计划（TESTPLAN）

> **说明**：本测试计划供后续真机验证阶段执行（本轮仅编制，不操作真机）。  
> **目标设备**：Nothing Phone (3)（`A024 / Metroid`，Android 17 / NOS 5.0 `C5.0-260924-2115`，Magisk + Vector/LSPosed）  
> **待测产物**：`release/KeyPilot.apk`（包名 `io.github.rtzhang.keypilot`）

---

## 0. 测试前置准备（Pre-flight）

1. **安装模块并激活作用域**：
   ```bash
   adb install -r release/KeyPilot.apk
   ```
   - 打开 Vector / LSPosed 管理器 → 启用 **KeyPilot** → 确认作用域已勾选：
     - `android`（系统框架 / `system_server`）
     - `com.nothing.ntessentialrecorder`
   - 重启设备（软重启或完整重启，使 `system_server` 加载钩子）。

2. **验证钩子就绪**：
   ```bash
   adb logcat -d | grep -E "KeyPilot:"
   ```
   - **通过判据**：日志中出现：
     - `KeyPilot: MainHook loaded in system_server`
     - `KeyPilot: EssentialKeyHook installed on com.android.server.wm.NtEssentialKeyImpl#onRecord`
     - `KeyPilot: StandardKeysHook installed on PhoneWindowManager#interceptKeyBeforeQueueing`
     - `KeyPilot: ConfigBridge ready: registered io.github.rtzhang.keypilot.CONFIG_UPDATE`

3. **下发本测试矩阵预设配置**（二选一）：
   - **方式 A（推荐）**：打开 KeyPilot App → 点击「套用测试预设」→ 确认弹窗 → 检查顶部状态显示「已连接 system_server」。
   - **方式 B（命令行广播热更新）**：
     ```bash
     adb shell am broadcast -a io.github.rtzhang.keypilot.CONFIG_UPDATE --es config_json '{"version":1,"masterEnabled":true,"showToast":true,"hapticFeedback":true,"preserveImeVoice":false,"longPressMs":500,"doublePressWindowMs":300,"powerLongPressExplicitlyBound":false,"keys":{"essential":{"enabled":true,"gestures":{"short":{"type":"camera"},"long":{"type":"flashlight"},"double":{"type":"launch_app","package":"com.google.android.calculator","label":"计算器"}}},"power":{"enabled":true,"gestures":{"short":{"type":"system"},"long":{"type":"system"},"double":{"type":"launch_app","package":"io.github.rtzhang.keypilot","label":"KeyPilot"}}},"volume_up":{"enabled":true,"gestures":{"short":{"type":"system"},"long":{"type":"system"},"double":{"type":"screenshot"}}},"volume_down":{"enabled":true,"gestures":{"short":{"type":"system"},"long":{"type":"system"},"double":{"type":"media_play_pause"}}}}}'
     ```
   - **配置落盘复核**：
     ```bash
     adb shell "su -c 'cat /data/system/keypilot.json'"
     ```
     **判据**：文件存在、为合法 JSON，且 `masterEnabled=true`、`powerLongPressExplicitlyBound=false`。

4. **公共基线约束**：每项测试前确认屏幕已亮屏解锁并回到桌面（`mWakefulness=Awake`，`mCurrentFocus` 为 `SearchLauncher`），并在触发前执行 `adb logcat -c` 清空日志缓冲。

---

## 1. 核心自定义手势测试矩阵（TC-01 ~ TC-07）

### TC-01：Essential Key 双按 → 启动计算器（用户指定核心项）
- **配置绑定**：`essential.double` → `launch_app`（`com.google.android.calculator`）
- **触发命令**（`sendevent` 注入 `/dev/input/event0` scancode `250`，双击间隔 120ms）：
  ```bash
  adb shell "su -c '
    sendevent /dev/input/event0 1 250 1; sendevent /dev/input/event0 0 0 0; sleep 0.06
    sendevent /dev/input/event0 1 250 0; sendevent /dev/input/event0 0 0 0; sleep 0.12
    sendevent /dev/input/event0 1 250 1; sendevent /dev/input/event0 0 0 0; sleep 0.06
    sendevent /dev/input/event0 1 250 0; sendevent /dev/input/event0 0 0 0
  '"
  ```
- **验证命令**：
  ```bash
  sleep 1
  adb shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
  adb logcat -d | grep -E "KeyPilot:|NtEssentialKeyImpl|ntessentialspace|RecorderTelemetry"
  ```
- **通过判据**：
  1. `mCurrentFocus` 切换为 `com.google.android.calculator/...`（计算器前台可见）。
  2. `logcat` 含 `KeyPilot: EXEC [essential.double] -> 启动应用：计算器 (com.google.android.calculator)`。
  3. **零原生串扰**：`logcat` 中无 `NtEssentialKeyImpl: acquireWakeLock`、无 `[RecorderTelemetry] MainService.onBind`、未启动 `com.nothing.ntessentialspace`。

---

### TC-02：Essential Key 短按 → 启动相机
- **配置绑定**：`essential.short` → `camera`
- **触发命令**（按住 80ms 松手，等待 >300ms 双击窗口超时）：
  ```bash
  adb shell "su -c '
    sendevent /dev/input/event0 1 250 1; sendevent /dev/input/event0 0 0 0; sleep 0.08
    sendevent /dev/input/event0 1 250 0; sendevent /dev/input/event0 0 0 0
  '"
  ```
- **验证命令**：
  ```bash
  sleep 1.2
  adb shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
  adb logcat -d | grep -E "KeyPilot:|FocusActivity|RecorderTelemetry"
  ```
- **通过判据**：
  1. `mCurrentFocus` 变为相机应用（如 `com.nothing.camera/...`）。
  2. `logcat` 含 `KeyPilot: EXEC [essential.short] -> 相机`。
  3. 未弹出原生 `com.nothing.ntessentialrecorder/.FocusActivity`，无 `MainService.onBind`。

---

### TC-03：Essential Key 长按（≥500ms）→ 手电筒开关
- **配置绑定**：`essential.long` → `flashlight`
- **触发命令**（连续执行两次，验证开 → 关切换，每次按住 700ms）：
  ```bash
  # 第 1 次：开启手电筒
  adb shell "su -c '
    sendevent /dev/input/event0 1 250 1; sendevent /dev/input/event0 0 0 0; sleep 0.7
    sendevent /dev/input/event0 1 250 0; sendevent /dev/input/event0 0 0 0
  '"
  sleep 1
  adb logcat -d | grep -E "KeyPilot:|flashlight"
  # 第 2 次：关闭手电筒（恢复）
  adb shell "su -c '
    sendevent /dev/input/event0 1 250 1; sendevent /dev/input/event0 0 0 0; sleep 0.7
    sendevent /dev/input/event0 1 250 0; sendevent /dev/input/event0 0 0 0
  '"
  ```
- **验证命令**：
  ```bash
  adb shell "dumpsys media.camera | grep -iE 'torch|flash' | head -n 20"
  adb logcat -d | grep -E "KeyPilot:|RecorderTelemetry"
  ```
- **通过判据**：
  1. 第 1 次触发后日志出现 `KeyPilot: EXEC [essential.long] -> 手电筒开关` 与 `KeyPilot: flashlight: cameraId=... -> ON`，后置闪光灯点亮。
  2. 第 2 次触发后日志出现 `KeyPilot: flashlight: cameraId=... -> OFF`，闪光灯熄灭。
  3. 两次均未触发 `FocusActivity` / 16kHz 录音 / 麦克风绿点。

---

### TC-04：音量+ 双按 → 系统截屏
- **配置绑定**：`volume_up.double` → `screenshot`
- **触发前基线**：
  ```bash
  BEFORE=$(adb shell "ls /sdcard/Pictures/Screenshots/ 2>/dev/null | wc -l")
  echo "Before screenshots: $BEFORE"
  ```
- **触发命令**（`/dev/input/event0` scancode `115` 双按，间隔 120ms）：
  ```bash
  adb shell "su -c '
    sendevent /dev/input/event0 1 115 1; sendevent /dev/input/event0 0 0 0; sleep 0.06
    sendevent /dev/input/event0 1 115 0; sendevent /dev/input/event0 0 0 0; sleep 0.12
    sendevent /dev/input/event0 1 115 1; sendevent /dev/input/event0 0 0 0; sleep 0.06
    sendevent /dev/input/event0 1 115 0; sendevent /dev/input/event0 0 0 0
  '"
  ```
- **验证命令**：
  ```bash
  sleep 2
  AFTER=$(adb shell "ls /sdcard/Pictures/Screenshots/ 2>/dev/null | wc -l")
  echo "After screenshots: $AFTER"
  adb logcat -d | grep -E "KeyPilot:|TakeScreenshotService|Screenshot"
  ```
- **通过判据**：
  1. `logcat` 含 `KeyPilot: EXEC [volume_up.double] -> 截屏` 及 `TakeScreenshotService` 截屏保存记录。
  2. `/sdcard/Pictures/Screenshots/` 下截图文件数精确 `+1`（`AFTER == BEFORE + 1`）。
  3. 未误弹 `VolumeDialog` 且未改变 `STREAM_MUSIC` 音量值。

---

### TC-05：音量− 双按 → 媒体播放 / 暂停
- **配置绑定**：`volume_down.double` → `media_play_pause`
- **前置准备**：启动任一音乐/播客播放器并进入播放或暂停就绪态（或监测 `MediaSessionService` 日志）。
- **触发命令**（`/dev/input/event3` scancode `114` 双按，间隔 120ms）：
  ```bash
  adb shell "su -c '
    sendevent /dev/input/event3 1 114 1; sendevent /dev/input/event3 0 0 0; sleep 0.06
    sendevent /dev/input/event3 1 114 0; sendevent /dev/input/event3 0 0 0; sleep 0.12
    sendevent /dev/input/event3 1 114 1; sendevent /dev/input/event3 0 0 0; sleep 0.06
    sendevent /dev/input/event3 1 114 0; sendevent /dev/input/event3 0 0 0
  '"
  ```
- **验证命令**：
  ```bash
  sleep 1
  adb logcat -d | grep -E "KeyPilot:|KEYCODE_MEDIA_PLAY_PAUSE|MediaSession|dispatchMediaKey"
  adb shell "dumpsys media_session | grep -E 'state=PlaybackState' | head -n 5"
  ```
- **通过判据**：
  1. `logcat` 含 `KeyPilot: EXEC [volume_down.double] -> 媒体：播放 / 暂停`。
  2. 系统收到 `KEYCODE_MEDIA_PLAY_PAUSE (85)` 且活跃媒体会话状态发生切换（`PLAYING ↔ PAUSED`）。

---

### TC-06：电源键双按 → 启动 KeyPilot 自身（验证盖过 NothingTweaks 的 Wallet 劫持）
- **配置绑定**：`power.double` → `launch_app`（`io.github.rtzhang.keypilot`）
- **背景**：P2-C1 实测本机已安装 NothingTweaks，会拦截 SystemUI `CommandQueue.onCameraLaunchGestureDetected` 并拉起 Google Wallet (`com.google.android.apps.walletnfcrel`)。
- **触发命令**（确保当前在桌面而非 KeyPilot 界面；注入 `/dev/input/event2` scancode `116` 双按，间隔 120ms）：
  ```bash
  adb shell input keyevent 3
  sleep 0.5
  adb logcat -c
  adb shell "su -c '
    sendevent /dev/input/event2 1 116 1; sendevent /dev/input/event2 0 0 0; sleep 0.06
    sendevent /dev/input/event2 1 116 0; sendevent /dev/input/event2 0 0 0; sleep 0.12
    sendevent /dev/input/event2 1 116 1; sendevent /dev/input/event2 0 0 0; sleep 0.06
    sendevent /dev/input/event2 1 116 0; sendevent /dev/input/event2 0 0 0
  '"
  ```
- **验证命令**：
  ```bash
  sleep 1.2
  adb shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
  adb logcat -d | grep -E "KeyPilot:|NothingTweaks|walletnfcrel|GestureLauncherService"
  ```
- **通过判据**：
  1. `mCurrentFocus` 切换为 **`io.github.rtzhang.keypilot/io.github.rtzhang.keypilot.MainActivity`**。
  2. `logcat` 含 `KeyPilot: EXEC [power.double] -> 启动应用：KeyPilot (io.github.rtzhang.keypilot)`。
  3. **覆盖成功铁证**：`logcat` 中**零命中** `NothingTweaks: [PowerButton] Intercepted onCameraLaunchGestureDetected` 且**零启动** `com.google.android.apps.walletnfcrel`。

---

### TC-07：配置热更新与冷启动持久化校验
- **测试步骤**：
  1. 在 KeyPilot App 中将 `essential.short` 改为 `open_settings`（打开系统设置）→ 立即按 Essential Key 短按。
  2. 复查 `/data/system/keypilot.json` 中 `essential.gestures.short.type` 已变为 `"open_settings"`。
  3. 重启设备后不打开 KeyPilot App，直接按 Essential Key 短按。
- **验证命令**：
  ```bash
  adb shell "su -c 'grep -A 5 \"essential\" /data/system/keypilot.json'"
  adb logcat -d | grep -E "KeyPilot: cold start|KeyPilot: CONFIG_UPDATE"
  ```
- **通过判据**：
  1. 修改后无需重启立即热生效（打开系统设置）。
  2. 重启后日志出现 `KeyPilot: cold start: loaded /data/system/keypilot.json`，未打开 App 直接按 Essential Key 仍能打开系统设置。

---

## 2. 原生回归与安全红线测试矩阵（REG-01 ~ REG-06）

> 所有回归项均在 **已启用 TESTPLAN 预设**（即 4 颗键均有部分手势被自定义绑定）的前提下执行，专门验证「同键混搭时未绑定手势与安全红线 100% 不受损」。

### REG-01：音量+ / 音量− 短按 → 原生弹出 `VolumeDialog`
- **触发命令**：
  ```bash
  adb logcat -c
  adb shell "su -c 'sendevent /dev/input/event0 1 115 1; sendevent /dev/input/event0 0 0 0; sleep 0.08; sendevent /dev/input/event0 1 115 0; sendevent /dev/input/event0 0 0 0'"
  sleep 0.6
  adb shell "dumpsys window windows | grep -i VolumeDialog"
  # 同样验证音量−短按 (event3, scancode 114)
  adb shell "su -c 'sendevent /dev/input/event3 1 114 1; sendevent /dev/input/event3 0 0 0; sleep 0.08; sendevent /dev/input/event3 1 114 0; sendevent /dev/input/event3 0 0 0'"
  sleep 0.6
  adb shell "dumpsys window windows | grep -i VolumeDialog"
  ```
- **通过判据**：
  1. 日志出现 `KeyPilot: StandardKeysHook: volume_up.short unbound -> native bypass tap`（及 `volume_down.short`）。
  2. 两次均正常弹出左侧系统 `VolumeDialog` 音量条，无死循环回放。

---

### REG-02：电源键短按 → 原生息屏进入 AOD Doze
- **触发命令**：
  ```bash
  adb logcat -c
  adb shell "su -c 'sendevent /dev/input/event2 1 116 1; sendevent /dev/input/event2 0 0 0; sleep 0.08; sendevent /dev/input/event2 1 116 0; sendevent /dev/input/event2 0 0 0'"
  sleep 1.2
  adb shell "dumpsys power | grep -E 'mWakefulness=|mLastSleepReason='"
  ```
- **通过判据**：
  1. `mWakefulness=Dozing`（或 `Asleep`），`mLastSleepReason=power_button`。
  2. 测试后执行 `adb shell input keyevent 224` 唤醒解锁恢复基线。

---

### REG-03：电源键长按（安全红线 ①）→ 原生全局电源菜单 `GlobalActionsDialogLite`
- **触发命令**（按住 800ms，严禁超 3 秒避免 PMIC 硬重启）：
  ```bash
  adb logcat -c
  adb shell "su -c 'sendevent /dev/input/event2 1 116 1; sendevent /dev/input/event2 0 0 0; sleep 0.8; sendevent /dev/input/event2 1 116 0; sendevent /dev/input/event2 0 0 0'"
  sleep 0.5
  adb shell "dumpsys window windows | grep -i GlobalActionsDialogLite"
  adb logcat -d | grep -E "KeyPilot:|powerLongPress|GlobalActionsDialogLite"
  ```
- **通过判据**：
  1. 满 500ms 弹出原生 `GlobalActionsDialogLite`（含紧急呼叫 / 锁定 / 关机 / 重启等项）。
  2. 日志含 `KeyPilot: StandardKeysHook: power.long unbound -> invoking native GlobalActions`。
  3. 按 `input keyevent 4` 正常关闭菜单。

---

### REG-04：组合键 `POWER + VOLUME_DOWN`（安全红线 ②）→ 原生截屏
- **触发命令**（跨节点 `event2` + `event3` 同步按下 350ms）：
  ```bash
  BEFORE=$(adb shell "ls /sdcard/Pictures/Screenshots/ 2>/dev/null | wc -l")
  adb logcat -c
  adb shell "su -c '
    sendevent /dev/input/event2 1 116 1; sendevent /dev/input/event2 0 0 0
    sendevent /dev/input/event3 1 114 1; sendevent /dev/input/event3 0 0 0
    sleep 0.35
    sendevent /dev/input/event3 1 114 0; sendevent /dev/input/event3 0 0 0
    sendevent /dev/input/event2 1 116 0; sendevent /dev/input/event2 0 0 0
  '"
  sleep 1.5
  AFTER=$(adb shell "ls /sdcard/Pictures/Screenshots/ 2>/dev/null | wc -l")
  echo "Screenshots: $BEFORE -> $AFTER"
  adb logcat -d | grep -E "KeyPilot: CHORD SHIELD|KeyCombinationManager|TakeScreenshotService"
  ```
- **通过判据**：
  1. 日志出现 `KeyPilot: CHORD SHIELD activated: power + volume_down -> native passthrough`。
  2. `KeyCombinationManager: Performing combination rule : KEYCODE_VOLUME_DOWN + KEYCODE_POWER` 命中并绑定 `TakeScreenshotService`，截图数 `+1`。
  3. 未误触发电源短按息屏或音量−动作。

---

### REG-05：组合键 `POWER + VOLUME_UP`（安全红线 ②）→ 原生电源菜单
- **触发命令**（跨节点 `event2` + `event0` 同步按下 350ms）：
  ```bash
  adb logcat -c
  adb shell "su -c '
    sendevent /dev/input/event2 1 116 1; sendevent /dev/input/event2 0 0 0
    sendevent /dev/input/event0 1 115 1; sendevent /dev/input/event0 0 0 0
    sleep 0.35
    sendevent /dev/input/event0 1 115 0; sendevent /dev/input/event0 0 0 0
    sendevent /dev/input/event2 1 116 0; sendevent /dev/input/event2 0 0 0
  '"
  sleep 0.8
  adb shell "dumpsys window windows | grep -i GlobalActionsDialogLite"
  adb logcat -d | grep -E "KeyPilot: CHORD SHIELD|KeyCombinationManager|GlobalActionsDialogLite"
  ```
- **通过判据**：
  1. 日志出现 `KeyPilot: CHORD SHIELD activated: power + volume_up -> native passthrough`。
  2. `KeyCombinationManager: Performing combination rule : KEYCODE_VOLUME_UP + KEYCODE_POWER` 命中并弹出 `GlobalActionsDialogLite`。
  3. 按 `input keyevent 4` 正常关闭菜单。

---

### REG-06：总开关关闭 → 全四键零延迟恢复原生
- **操作**：在 KeyPilot App 关闭顶部「总开关」，再执行 Essential Key 短按（80ms）。
- **验证命令**：
  ```bash
  adb shell "su -c 'sendevent /dev/input/event0 1 250 1; sendevent /dev/input/event0 0 0 0; sleep 0.08; sendevent /dev/input/event0 1 250 0; sendevent /dev/input/event0 0 0 0'"
  sleep 1
  adb shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
  ```
- **通过判据**：
  1. 立即恢复原生 `com.nothing.ntessentialrecorder/.FocusActivity` 截屏捕获界面。
  2. `logcat` 出现 `NtEssentialKeyImpl: acquireWakeLock` 与 `[RecorderTelemetry] MainService.onBind`，证明总开关关闭后钩子进入 0ms 透传模式。

---

## 3. 异常与稳定性抽检
- **连续压测**：快速交替执行 Essential 双按 / 音量+ 双按 / 电源短按各 10 次后检查：
  ```bash
  adb shell "pidof system_server"
  adb logcat -d | grep -E "KeyPilot: \[ERR\]|FATAL EXCEPTION|Watchdog"
  ```
- **通过判据**：`system_server` PID 未变化（零软重启）、无 `FATAL EXCEPTION`、无 `Watchdog` ANR、无 `KeyPilot: [ERR]` 未处理异常。

---

## 4. KeyPilot v2 Phase 1（P1）上下文分流测试矩阵（待下一轮真机执行）

> **前置**：在 KeyPilot UI 点击「套用 v2 相机预设」（或通过 `CONFIG_UPDATE` 广播下发 `version: 2` JSON），确认 `/data/system/keypilot.json` 中 `"version": 2` 且 `profiles` 含 `prof_camera_shutter`、`prof_reader_page_turn`、`prof_music_track_skip`、`prof_game_bypass`。

### 4.1 v2 核心用例（V2-TC-01 ~ V2-TC-06）

#### V2-TC-01：用户指定核心语义 —— 非相机 App 短按 EK = 打开相机 / 相机内短按 EK = 快门拍照
- **阶段 A（非相机 App 前台）**：回到桌面或打开计算器 → 注入 EK 短按（80ms）。
  - **判据**：`logcat` 命中 `KeyPilot: EXEC [essential.short] (resolved=global, fg=...) -> 相机`，前台切至 `com.nothing.camera`（未命中相机 Profile，回退全局）。
- **阶段 B（Nothing Camera 前台拍照模式）**：先记录 `/sdcard/DCIM/Camera/` 照片数 `BEFORE` → 注入同一手势 EK 短按（80ms）→ 等 1.5s 检查照片数 `AFTER`。
  - **判据**：
    1. `logcat` 命中 `KeyPilot: EXEC [essential.short] (resolved=profile:prof_camera_shutter, fg=com.nothing.camera) -> 相机快门`。
    2. `KeyPilot: camera_shutter: fg=com.nothing.camera ... -> injected keyCode=25 pulse=35ms bypass=true`。
    3. `/sdcard/DCIM/Camera/` 照片数精确 `+1`（`AFTER == BEFORE + 1`），无音量条弹出、无死循环递归。

#### V2-TC-02：直达应用内功能 —— 双按 EK 触发中文内置包（支付宝扫一扫 / Deep Link）
- **触发**：在桌面注入 EK 双按（间隔 120ms）。
- **判据**：
  1. `logcat` 命中 `KeyPilot: EXEC [essential.double] (resolved=global, ...) -> 内置功能：支付宝：扫一扫`。
  2. 若本机已安装支付宝，直接跳转扫码页而非首页；若未安装支付宝，优雅提示 Toast「未安装支持该链接的应用」，零异常崩溃。

#### V2-TC-03：场景模板抽测 —— 阅读器翻页（B1）与音乐 App 内切歌（B5，含音量到顶校验）
- **测试步骤**：
  1. 启动 `prof_reader_page_turn` 覆盖的阅读器（或临时将设置/浏览器加入该 Profile 测试 `key_inject`）→ 短按 EK。
  2. 将媒体音量拉至最大（到顶），切到音乐播放器播放音乐 → 双击音量+。
- **判据**：
  1. 阅读器场景日志命中 `resolved=profile:prof_reader_page_turn` 并注入 `KEYCODE_PAGE_DOWN (93)`。
  2. 音乐场景在媒体音量已到顶前提下仍命中 `resolved=profile:prof_music_track_skip -> 精准媒体：next`，成功切换至下一曲（验证根治 Tasker 音量到顶失效坑）。

#### V2-TC-04：Nothing OS 5.1 官方重映射共存策略（`officialRemapPolicy=yield_single_click`）
- **测试步骤**：在「防坑开关设置」切换为 `yield_single_click` 并同步：
  1. 在桌面短按 EK（80ms）→ 观察是否让位原生/官方。
  2. 在桌面长按 EK（700ms）→ 观察是否仍触发 KeyPilot 手电筒。
  3. 进入 Nothing Camera 短按 EK（80ms）→ 观察是否仍命中相机快门 Profile。
- **判据**：
  1. 桌面短按日志含 `EssentialKeyHook: SHORT -> native (official_yield_single)` 并走原生链。
  2. 桌面长按仍执行手电筒（`resolved=global`）。
  3. 相机内短按仍命中 `resolved=profile:prof_camera_shutter` 拍照（验证「常规单击让位 + 场景覆写与双长按归 KeyPilot」）。

#### V2-TC-05：保留默认动作不吞键（`passthroughDefault=true`）与冲突检测（`ConflictDetector`）
- **测试步骤**：
  1. 将某动作勾选 `passthroughDefault=true` 触发一次。
  2. 在系统设置临时开启 Select to Speak（或同类无障碍服务）后打开 KeyPilot App（测完立即关闭恢复）。
- **判据**：
  1. 日志同时记录自定义动作执行与 `passthroughDefault=true -> also replaying native`，无无限递归。
  2. KeyPilot 主页顶部与防坑设置页准确展示红色冲突警示卡片。

#### V2-TC-06：相机内未覆写手势自动回退全局（分层继承）
- **触发**：在 Nothing Camera 前台长按 EK（700ms，Profile 内未覆写 `essential.long`）。
- **判据**：自动回退全局 `flashlight`；若相机占用闪光灯触发 `CAMERA_IN_USE`，优雅提示 Toast「相机占用中，无法切换手电筒」，`system_server` 零异常。

---

### 4.2 v2 必测回归项（V2-REG-01 ~ V2-REG-05）

- **V2-REG-01（黑名单 0ms 旁路回归）**：将计算器（`com.google.android.calculator`）加入 `blacklistPackages` → 打开计算器 → 短按/双按 EK 与双击音量+。**判据**：0ms 原生透传（EK 走原生 `FocusActivity`，音量+ 不触发截屏，无 `KeyPilot: EXEC`）。
- **V2-REG-02（关闭 Profile 回退全局回归）**：在「场景分流 Profiles」关闭 `prof_camera_shutter` 开关 → 进入相机短按 EK。**判据**：不再触发快门，回退执行全局 `essential.short`（`resolved=global`）。
- **V2-REG-03（v1 JSON 配置向后兼容回归）**：向 `CONFIG_UPDATE` 广播不带 `profiles`/`blacklistPackages` 字段的旧版 `version: 1` JSON。**判据**：`ConfigBridge` 正常解析并落盘，v1 全部 6 项映射与热更新/冷启动零退化。
- **V2-REG-04（息屏/锁屏防坑开关回归）**：关闭 `allowWhenScreenOff` → 息屏下短按 EK。**判据**：日志含 `screen_off_gate`，不执行自定义动作。
- **V2-REG-05（安全红线 ①② 回归）**：在命中 `prof_camera_shutter` 与 `prof_game_bypass` 前台下，长按电源键必出 `GlobalActionsDialogLite`，`POWER+VOL_DOWN` 必截屏、`POWER+VOL_UP` 必出电源菜单。

---

## 5. Activity 探测绑定器测试矩阵（ACT-01 ~ ACT-08）

> **前置**：`adb install -r release/KeyPilot.apk` 并重启（覆盖安装后需重启才吃到新钩子）；确认
> `adb logcat -d | grep "KeyPilot: ConfigBridge ready"` 出现；屏幕亮屏解锁。
> **证据目录**：`verify_activity/`（截图 + `REPORT.md` + `report.json`）。

### ACT-01：选 APP → 活动列表完整（抽查多包，含非导出活动）
- **操作**：全局矩阵点任意手势 → 动作目录「启动指定 Activity」→「选择 APP → 活动浏览器」→ 依次选微信 / 支付宝 / 相机。
- **判据**：
  1. 每行 = APP 图标 + 主行命名（有内置/用户命名显命名，否则 Activity 短类名）+ 副行完整 `package/class`（小字、点按复制）+ 「导出 / 非导出」chip + 「试开启」「重命名」药丸钮。
  2. 顶部搜索框按活动名 / 类名过滤即时生效；计数行显示「共 N 个活动」。
  3. 抽查包的活动总数与 `adb shell dumpsys package <pkg> | grep -c Activity`（或 `cmd package dump`）同量级，且列表中确实出现 `exported=false` 的活动（chip 显示「非导出」）。
  4. 大包（数百活动）滚动流畅、滚动到底自动追加分页（计数行「已显示 M 个」递增）。

### ACT-02：试开启真实拉起目标活动
- **操作**：在活动浏览器点某活动的「试开启」（至少抽 1 个非导出活动 + 1 个导出活动）。
- **判据**：
  1. 目标活动真实进入前台：`adb shell dumpsys window | grep mCurrentFocus` 切换为该组件。
  2. UI 该行短暂高亮（primaryContainer）+ Toast「已拉起 …」。
  3. 失败场景（如组件不存在）：Toast 显示具体错误原因（异常类名 + 消息），行高亮 errorContainer。
  4. `logcat` 含 `KeyPilot: TRY_LAUNCH ok ...`（或 `TRY_LAUNCH FAILED ... -> <原因>`）。

### ACT-03：重命名持久化（重启 / 杀进程后仍在）
- **操作**：给某活动重命名（如「扫一扫」）→ 列表即时刷新 → `adb shell am force-stop io.github.rtzhang.keypilot` → 重开 App → 再 `adb reboot` → 重开 App。
- **判据**：
  1. APP 缓存：`adb shell "su -c 'cat /data/data/io.github.rtzhang.keypilot/files/keypilot_labels.json'"` 含该组件键与新命名。
  2. 钩子镜像：`adb shell "su -c 'cat /data/system/keypilot_labels.json'"` 同步含该条目，且 `logcat` 出现 `KeyPilot: LABELS_UPDATE applied: N labels`。
  3. force-stop 与重启后列表、矩阵显示仍为新命名。

### ACT-04：绑定带命名活动，手势触发直达
- **操作**：在活动浏览器点选行完成绑定 → 回矩阵确认该手势值文本 = 命名、副行 = 小字组件名 → 触发该手势（sendevent 注入）。
- **判据**：
  1. 前台切换到目标活动（`mCurrentFocus`）。
  2. `logcat` 含 `KeyPilot: EXEC [<key>.<gesture>] ... -> Activity：<命名> (<pkg>/<short>)` 或 `启动 <命名>`。
  3. 场景分流 Profile 卡的覆写摘要显示命名，并附小字组件名行。

### ACT-05：导出 / 导入往返验证
- **操作**：设置 → 活动命名管理 → 导出 JSON（保存为文件，如 `Download/keypilot_labels_export.json`）→ `adb pull` 核对 → 删除本地一条命名 → 导入该文件 → 再导出比对。
- **判据**：
  1. 导出文件为合法 JSON，条目数 = 本地命名数，字段符合 `keypilot.labels.v1`。
  2. 导入补空缺成功（Toast「导入完成：新增 N 条」）；若存在冲突弹出「导入冲突提示」，选择覆盖后本地值被替换。
  3. 往返后再次导出内容与原文件等价（条目数与 label 一致）。

### ACT-06：回归（原有动作 / 手势 / v2 分流零破坏，抽测 ≥3 项）
- **V2-REG-A（热更新回归）**：改任一手势绑定 → 立即触发生效；`/data/system/keypilot.json` 同步。
- **V2-REG-B（v2 分流回归）**：套用 v2 相机预设 → 相机内短按 EK 触发快门（`resolved=profile:prof_camera_shutter`）。
- **V2-REG-C（安全红线回归）**：长按电源键仍弹 `GlobalActionsDialogLite`；`POWER+VOL_DOWN` 仍截屏。
- **V2-REG-D（桥命令不串扰）**：普通 `CONFIG_UPDATE`（无 `command`）与 `TRY_LAUNCH`/`LABELS_UPDATE` 混发，
  配置仍正常落盘，日志无 `CONFIG_UPDATE unknown command`。

### ACT-07：UI 自审截图（vision 审 → 修 → 复审）
- **截图面**：活动编辑器（当前绑定卡 + 主路径按钮 + 高级折叠）、应用选择器（含复制 pill）、活动浏览器（搜索 + 行 + chip + 药丸钮）、重命名弹层、活动命名管理（我的 / 内置 / 导入导出）、全局矩阵（命名 + 组件副行）、场景卡（摘要 + 组件行）、深色模式。
- **判据**：无控件碰撞 / 贴边 / 截断 / 圆角不一致 / 间距失序；行间距符合 8dp 节奏；值文本不重复标签名；发现的问题修后复审通过。

### ACT-08：system_server 稳定性红线
```bash
adb shell pidof system_server   # 测试前后 PID 必须一致
adb logcat -d | grep -E "KeyPilot: \[ERR\]|FATAL EXCEPTION|Watchdog"
```
- **判据**：PID 未变（零软重启）、无 `FATAL EXCEPTION` / `Watchdog` / `KeyPilot: [ERR]`；试开启失败全部被回执捕获而非抛进 system_server 主循环。
