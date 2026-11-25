# KeyPilot 真机部署与 TESTPLAN 全矩阵实测报告

> **实测时间**：2026-11-24 13:37 – 13:53 (CST)  
> **目标设备**：Nothing Phone (3)（`A024 / Metroid`，Serial `00019157L001724`）  
> **系统基线**：Android 17 (API 37) / Nothing OS 5.0 (`C5.0-260924-2115`)，Magisk + Vector/LSPosed (`zygisk_vector` v2.2)  
> **最终产物**：[KeyPilot.apk](file:///D:/Hermes%20Desktop/KeyPilot/release/KeyPilot.apk)（SHA256：`04585c09666ece3be81ce98dd186448ef19d466b86e40db551d2a979b526ba4c`）  
> **总评**：**TC 7/7 全部通过 · REG 6/6 全部通过 · 迭代修复 1 轮 · `system_server` 零崩溃**

---

## 1. 部署与激活核验

| 检查项 | 实测结果 | 存证 |
|---|---|---|
| APK 安装与初始 UI | `adb install -r release/KeyPilot.apk` 成功；纯代码 Nothing 深色 4×3 矩阵渲染正常 | [01_app_ui_initial.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/01_app_ui_initial.png)、[01_app_ui_matrix_lower.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/01_app_ui_matrix_lower.png)、[04_app_ui_polished.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/04_app_ui_polished.png) |
| Vector 模块启用与作用域 | `/data/adb/lspd/cli modules enable io.github.rtzhang.keypilot`；作用域含 `system/0`、`android/0`、`com.nothing.ntessentialrecorder/0`（实证：Vector 数据库中 `system_server` 作用域包名为 `system`） | [tc_01_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_01_logcat.txt) |
| 重启后钩子注入 | `EssentialKeyHook installed on com.android.server.wm.NtEssentialKeyImpl#onRecord`、`StandardKeysHook installed on PhoneWindowManager#interceptKeyBeforeQueueing (1 overloads)`、`ConfigBridge ready: registered io.github.rtzhang.keypilot.CONFIG_UPDATE`、`EssentialRecorder telemetry installed on MainService` | [tc_07_cold_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_07_cold_logcat.txt) |
| UI 一键套用测试预设 | 点击「套用测试预设」→「立即套用」，`system_server` 毫秒内落盘 `/data/system/keypilot.json`（`1439 bytes`，`system:system 0600`） | [03_app_configured.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/03_app_configured.png) |

---

## 2. 核心自定义手势测试矩阵（TC-01 ~ TC-07：**7 / 7 PASS**）

| 用例 | 绑定动作与判据 | 实测现象与核心日志 | 结论 | 证据文件 |
|---|---|---|---|---|
| **TC-01** | **EK 双按 → 计算器**（`com.google.android.calculator`）；零原生 EssentialSpace/MainService | 前台由 `SearchLauncher` 切至 `com.google.android.calculator/com.android.calculator2.Calculator`；日志命中 `KeyPilot: EXEC [essential.double] -> 启动应用：计算器 (com.google.android.calculator)`，零 `MainService.onBind` 与 `ntessentialspace` | **PASS** | [tc_01_before.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_01_before.png) / [tc_01_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_01_after.png) / [tc_01_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_01_logcat.txt) |
| **TC-02** | **EK 短按 → 相机**；未弹原生 `FocusActivity` | 前台切至 `com.nothing.camera/com.nothing.camera.activity.CameraActivity`；日志命中 `KeyPilot: EXEC [essential.short] -> 相机`，零 `FocusActivity` / `MainService.onBind` | **PASS** | [tc_02_before.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_02_before.png) / [tc_02_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_02_after.png) / [tc_02_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_02_logcat.txt) |
| **TC-03** | **EK 长按 (≥500ms) → 手电筒开关**（连续两次应 `ON → OFF`）；零原生录音 | 首轮发现 `TorchCallback` 初始化竞态导致二次仍 `ON` → **Iter 1 修复后复验**：第 1 次 `KeyPilot: flashlight: cameraId=0 -> ON`，第 2 次 `KeyPilot: flashlight: cameraId=0 -> OFF`，零 `FocusActivity` | **PASS**（Iter 1 修复通过） | [tc_03_before.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_03_before.png) / [tc_03_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_03_after.png) / [tc_03_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_03_logcat.txt) / [tc_03_off_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_03_off_logcat.txt) |
| **TC-04** | **音量+ 双按 → 截屏**；相册截图数 +1、无 `VolumeDialog` | `KeyPilot: EXEC [volume_up.double] -> 截屏` → `ScreenshotHelper` 绑定 `TakeScreenshotService`；截图数 `16 → 17 (+1)`，左下角出现 `ScreenshotUI` 浮窗，无 `VolumeDialog` | **PASS** | [tc_04_before.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_04_before.png) / [tc_04_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_04_after.png) / [tc_04_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_04_logcat.txt) |
| **TC-05** | **音量− 双按 → 媒体播放/暂停**；注入 `KEYCODE_MEDIA_PLAY_PAUSE (85)` | `KeyPilot: EXEC [volume_down.double] -> 媒体：播放 / 暂停`；框架日志确认 `keyCode:85 down:true` 与 `down:false` 完整注入（动态解析 `pmic_resin=/dev/input/event2`） | **PASS** | [tc_05_before.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_05_before.png) / [tc_05_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_05_after.png) / [tc_05_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_05_logcat.txt) |
| **TC-06** | **电源键双按 → 启动 KeyPilot 自身**；**盖过 NothingTweaks 的 Google Wallet 劫持** | 前台切至 `io.github.rtzhang.keypilot/io.github.rtzhang.keypilot.MainActivity`；`KeyPilot: EXEC [power.double] -> 启动应用：KeyPilot`；**零命中** `NothingTweaks`/`onCameraLaunchGestureDetected`/`com.google.android.apps.walletnfcrel` | **PASS** | [tc_06_before.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_06_before.png) / [tc_06_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_06_after.png) / [tc_06_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_06_logcat.txt) |
| **TC-07** | **配置热更新（不重启生效）+ 冷启动持久化（重启后不启 APP 生效）** | **热更新**：广播改 `essential.short=open_settings` 后立即短按即打开 `com.android.settings/.homepage.SettingsHomepageActivity` 并落盘 `/data/system/keypilot.json`。<br>**冷启动**：重启后日志即现 `KeyPilot: cold start: loaded /data/system/keypilot.json`，未打开 KeyPilot 直接双按 EK 立即启动计算器并弹 Toast `KeyPilot: 启动 计算器` | **PASS** | [tc_07_hot_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_07_hot_after.png) / [tc_07_hot_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_07_hot_logcat.txt) / [tc_07_cold_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_07_cold_after.png) / [tc_07_cold_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/tc_07_cold_logcat.txt) |

---

## 3. 原生回归与安全红线矩阵（REG-01 ~ REG-06：**6 / 6 PASS**）

| 回归项 | 验证目标与安全红线 | 实测现象与核心日志 | 结论 | 证据文件 |
|---|---|---|---|---|
| **REG-01** | **音量+ / 音量− 短按 → 原生 `VolumeDialog`** | `volume_up.short` 与 `volume_down.short` 均命中 `native bypass tap`，`dumpsys window` 与截图清晰捕获左侧原生 `VolumeDialog` 音量滑条 | **PASS** | [reg_01a_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/reg_01a_after.png) / [reg_01a_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/reg_01a_logcat.txt) / [reg_01b_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/reg_01b_after.png) / [reg_01b_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/reg_01b_logcat.txt) |
| **REG-02** | **电源键短按 → 原生息屏 AOD Doze** | `power.short unbound -> native bypass tap` → `PowerManagerService: Going to sleep due to power_button` → `mWakefulness=Dozing`，`mLastSleepReason=power_button` | **PASS** | [reg_02_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/reg_02_after.png) / [reg_02_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/reg_02_logcat.txt) |
| **REG-03** | **安全红线①：电源键长按 → 原生电源菜单** | 按住 800ms 于 500ms 时刻命中 `power.long unbound -> invoking native GlobalActions`，弹出完整 6 项原生 `GlobalActionsDialogLite`（紧急呼叫/锁定/关机/重启/Recovery/Bootloader） | **PASS** | [reg_03_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/reg_03_after.png) / [reg_03_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/reg_03_logcat.txt) |
| **REG-04** | **安全红线②：组合键 `POWER+VOL_DOWN` → 原生截屏** | `KeyPilot: CHORD SHIELD activated: power + volume_down -> native passthrough` → `KeyCombinationManager: Performing combination rule : KEYCODE_VOLUME_DOWN + KEYCODE_POWER` → `TakeScreenshotService`，截图数 `17 → 18 (+1)` | **PASS** | [reg_04_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/reg_04_after.png) / [reg_04_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/reg_04_logcat.txt) |
| **REG-05** | **安全红线②：组合键 `POWER+VOL_UP` → 原生电源菜单** | `KeyPilot: CHORD SHIELD activated: power + volume_up -> native passthrough` → `KeyCombinationManager: Performing combination rule : KEYCODE_VOLUME_UP + KEYCODE_POWER` → 弹出 `GlobalActionsDialogLite` | **PASS** | [reg_05_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/reg_05_after.png) / [reg_05_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/reg_05_logcat.txt) |
| **REG-06** | **总开关关闭 → 全键恢复原生** | 广播 `masterEnabled=false` 后短按 EK 立即弹出原生 `com.nothing.ntessentialrecorder/.FocusActivity`，`[RecorderTelemetry] MainService.onBind invoked` 命中 | **PASS** | [reg_06_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/reg_06_after.png) / [reg_06_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify/reg_06_logcat.txt) |

---

## 4. 问题定位与迭代修复记录（共 1 轮迭代）

1. **Bug-1（代码缺陷，已修复）— 手电筒开关第二次长按仍为 `ON`**：
   - **现象**：首轮测试 `TC-03` 中，首次 EK 长按记录 `flashlight: cameraId=0 -> ON`，6 秒后再次长按仍记录 `-> ON`。
   - **根因**：`ActionExecutor.execFlashlight()` 先调用 `registerTorchCallback()` 再调用 `findTorchCameraId()`；Android 异步派发所有摄像头（`0, 1, 2...`）初始 `enabled=false` 回调时，`sTorchCameraId` 尚未赋值或覆盖了刚写入的 `sTorchEnabled=true`。
   - **修复**：在 [ActionExecutor.java](file:///D:/Hermes%20Desktop/KeyPilot/app/src/main/java/io/github/rtzhang/keypilot/hook/ActionExecutor.java) 中先解析 `targetCamId` 再注册回调，仅接受 `targetCamId.equals(cameraId)` 的回调，并对自身切换后 `500ms` 内的初始陈旧回调做去抖保护。重建重装复验 `ON → OFF` 100% 通过。
2. **Polish-1（UI 适配，已修复）— Android 17 Edge-to-Edge 顶部标题与状态栏重叠 & 同步状态实时刷新**：
   - **修复**：在 [MainActivity.java](file:///D:/Hermes%20Desktop/KeyPilot/app/src/main/java/io/github/rtzhang/keypilot/MainActivity.java) 增加 `OnApplyWindowInsetsListener` 避让状态栏/导航栏，并在 `saveAndBroadcast()` 后 600ms 自动刷新顶部心跳状态。复验截图见 [04_app_ui_polished.png](file:///D:/Hermes%20Desktop/KeyPilot/verify/04_app_ui_polished.png)。
3. **环境发现（非模块缺陷）— 重启后 `/dev/input/event*` 节点动态漂移 & Vector `system` 作用域命名**：
   - 重启后因 `qcom-hv-haptics` 异步探测，内核节点从 `pmic_pwrkey=event2 / pmic_resin=event3` 漂移为 `event1 / event2`。KeyPilot Java 钩子按 `scanCode`/`keyCode` 识别，天然免疫漂移；测试脚本 `verify/run_matrix.py` 已改为运行时查 `/proc/bus/input/devices` 动态解析。
   - Vector/LSPosed 数据库内 `system_server` 作用域包名为 `system`（`vector-cli scope add io.github.rtzhang.keypilot system/0`）。

---

## 5. 终检设备状态核验

| 核验项 | 最终实测值 | 状态 |
|---|---|---|
| 配置目标态（`/data/system/keypilot.json`） | `masterEnabled=true`, `pwrLongOverride=false`, 6/12 项与 TESTPLAN 完全一致 | ✅ |
| Clash / `tun0` | `26: tun0: <POINTOPOINT,UP,LOWER_UP> ... inet 172.19.0.1/30` | ✅ |
| Magisk 模块状态（root 核验） | 10 个模块全部启用，**`disable` 标记数 = `0`** | ✅ |
| `system_server` 稳定性 | PID `2795` 全程无重启、无 `FATAL EXCEPTION` / `Watchdog` | ✅ |
| 终态前台 | `com.nothing.launcher/com.android.searchlauncher.SearchLauncher` | ✅ |
