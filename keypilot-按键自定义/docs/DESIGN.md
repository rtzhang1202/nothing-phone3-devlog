# KeyPilot 架构与详细设计文档（DESIGN）

> **适用机型与基线**：Nothing Phone (3)（`A024 / Metroid`），Android 17（API 37）/ Nothing OS 5.0（`C5.0-260924-2115`），Magisk + Vector/LSPosed（Xposed API 82）  
> **应用包名**：`io.github.rtzhang.keypilot`（Label：`KeyPilot`）  
> **前置实证依据**：`P1-输入静态勘察.md`、`P2-触发行为实测.md`、`EssentialKey-自定义方案.md`（P4 离线 DEX 反汇编）

---

## 1. 系统总体架构图（文字版）

```text
+-----------------------------------------------------------------------------------+
|                            物理硬件与内核输入层 (Linux evdev)                        |
|  /dev/input/event0 (gpio-keys)    : scancode 250 (Essential Key), 115 (Vol+)      |
|  /dev/input/event2 (pmic_pwrkey)  : scancode 116 (Power)                          |
|  /dev/input/event3 (pmic_resin)   : scancode 114 (Vol-)                           |
+-----------------------------------------+-----------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                        Native 输入子系统 (InputReader / Dispatcher)                 |
|  scancode 250 -> KEYCODE_UNKNOWN(0) + scanCode=250 (全系统 184 个 .kl 无映射)        |
|  scancode 116 -> KEYCODE_POWER(26)  |  115 -> VOLUME_UP(24)  |  114 -> VOL_DOWN(25)|
+-----------------------------------------+-----------------------------------------+
                                          |
                                          v
+===================================================================================+
|                      system_server 进程 (uid=1000, scope="android")                |
|                                                                                   |
|  [PhoneWindowManager.interceptKeyBeforeQueueing]  <-- StandardKeysHook (全重载钩)  |
|    │                                                    │                         |
|    ├─(1) Bypass Token 命中 (KeyPilot 自身回放事件) ──────┴─► 原生放行               |
|    ├─(2) 硬件组合键实时监测 (POWER+VOL_DOWN / POWER+VOL_UP / VOL_UP+VOL_DOWN)      |
|    │       └─ 立即取消双键 FSM + 补发已拦截首键 DOWN ──────► KeyCombinationManager  |
|    ├─(3) Power / Vol+ / Vol- (仅当该键有自定义绑定时接管)                            |
|    │       └─ return 0 截断 (早于 GestureLauncherService 与 NothingTweaks!)        |
|    │           └─► GestureStateMachine (500ms 长按 / 300ms 双按 / Partial WL)      |
|    │                  ├─ 命中自定义动作 ──────────────► ActionExecutor (BG Thread) |
|    │                  └─ 命中 system (原生) ──────────► Bypass 注入 / powerLongPress|
|    │                                                                              |
|    └─(4) handleKeyGesture: scanCode == 250 ──► INtEssentialKey.onRecord(event)    |
|                                                      │                            |
|            [com.android.server.wm.NtEssentialKeyImpl.onRecord] <-- EssentialKeyHook|
|              ├─ 未启用 / 全原生 / IME Voice 放行 ───────► invokeOriginal (原生链)   |
|              └─ 截断 (return null，零原生 WakeLock/录音)                            |
|                    └─► GestureStateMachine (500ms / 300ms / Partial WL)           |
|                           ├─ 自定义动作 ──────────────► ActionExecutor (BG Thread) |
|                           └─ 混搭原生 (SHORT 补发 / LONG 透传 / DOUBLE 启 Space)   |
|                                                                                   |
|  [ConfigBridge (system_server 内)]                                                |
|    • 冷启动: 读 /data/system/keypilot.json                                         |
|    • 热更新: 动态注册 BroadcastReceiver (RECEIVER_EXPORTED) 接收 CONFIG_UPDATE      |
|              -> 更新内存 volatile 配置 -> 异步原子写盘 (.tmp -> fsync -> rename)     |
+=========================================+=========================================+
          ▲                               |
          | Broadcast:                    | ContentProvider.call (异步非阻塞):
          | io.github.rtzhang.keypilot    | content://io.github.rtzhang.keypilot.status
          | .CONFIG_UPDATE (config_json)  | (heartbeat + execution logs)
          |                               v
+-----------------------------------------------------------------------------------+
|                        KeyPilot 配置 APP (io.github.rtzhang.keypilot)              |
|  • MainActivity (纯代码 Nothing 美学深色 UI：4键 × 3手势矩阵 + 总开关/单键开关)       |
|  • ActionPickerDialog (20 项动作目录 + 可搜索应用列表 + Activity/Intent 编辑器)      |
|  • 本地持久化: files/keypilot_config.json  |  StatusProvider: files/status.log      |
+-----------------------------------------------------------------------------------+

+-----------------------------------------------------------------------------------+
|             [可选附加件] magisk/ Companion Module (主功能完全不依赖)                 |
|  • sepolicy.rule: /dev/uinput & input_device & /data/system/keypilot.json 权限     |
|  • keypilotd (C 原生备胎，opt-in): EVIOCGRAB event0 + uinput 透传 Vol+(115)        |
+-----------------------------------------------------------------------------------+
```

---

## 2. 钩点选择与技术理由

### 2.1 Essential Key：`com.android.server.wm.NtEssentialKeyImpl.onRecord(KeyEvent)`
- **实证来源**：P4 离线 DEX 反汇编证实，`PhoneWindowManager.handleKeyGesture` 硬编码判断 `event.getScanCode() == 250` 后直接分发至 `INtEssentialKey.onRecord(KeyEvent)`，其唯一实现类位于 `/system/framework/nt-services.jar` 的 `com.android.server.wm.NtEssentialKeyImpl`。
- **选型理由**：
  1. **未混淆公有接口**：`onRecord(KeyEvent)` 是跨 jar 接口契约方法，抗 R8 混淆能力远优于应用层 `EssentialRecorder`（其内部状态机全混淆为 `Lb7/u;`、`Le7/s0;`）。
  2. **零原生副作用**：在 `NtEssentialKeyImpl.onRecord` 入口消费事件可彻底避免原厂申请 `FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`（3s 亮屏锁）、绑定 `MainService`、执行 `captureDisplay` 截屏与 16kHz `AudioRecord` 麦克风绿点闪烁。
  3. **支持按手势混搭原生**：短按未绑定时补发新时间戳 `DOWN+UP`（避免 `MainService` 的 `EVENT_TIME` 超时校验）；长按未绑定时透传 `DOWN`/`UP`；双按未绑定时直接按 P4 实证契约拉起 `com.nothing.ntessentialspace/.MainActivity`（带 `FROM_ESSENTIAL_KEY=true`）。
  4. **三层兜底探针**：直接 `hookAllMethods(NtEssentialKeyImpl, "onRecord")` → 若未来 OTA 改名则通过 `NtExtServiceFactory.getOrCreate` 运行时动态探针捕获具体实现类 → 最终仍可由 `PhoneWindowManager.interceptKeyBeforeQueueing` 按 `scanCode==250` 兜底接管。

### 2.2 电源 / 音量±：`com.android.server.policy.PhoneWindowManager.interceptKeyBeforeQueueing`
- **选型理由**：
  1. **全局唯一上游咽喉**：P1/P2 日志证实 `KEYCODE_POWER (26 / scancode 116)`、`KEYCODE_VOLUME_UP (24 / 115)`、`KEYCODE_VOLUME_DOWN (25 / 114)` 均第一时间进入 `interceptKeyBeforeQueueing(KeyEvent, int)`。
  2. **可彻底盖过 NothingTweaks 电源双击劫持**：P2-C1 证实 NothingTweaks 在下游 `com.android.systemui` 的 `CommandQueue.onCameraLaunchGestureDetected` 劫持电源双击去启动 Google Wallet。KeyPilot 在 `system_server` 最上游 `interceptKeyBeforeQueueing` 即拦截并消费 `POWER` 双击，`GestureLauncherService` 不会收到该双击序列，从而 100% 干净地覆盖 NothingTweaks。
  3. **全重载钩 + 运行时字段探测**：使用 `XposedBridge.hookAllMethods` 钩住 `interceptKeyBeforeQueueing`、`init`、`systemReady` 的全部重载，并通过反射类型探针提取 `Context` 与 `powerLongPress` / `showGlobalActions` / `takeScreenshot`，最大化兼容 Android 15–17 演进。
  4. **原生未绑定手势忠实还原**：因 `interceptKeyBeforeQueueing` 返回 `0` 会阻止事件进入 `InputDispatcher`，当某键仅绑定了双按而短按为 `system` 时，KeyPilot 通过 `InputManagerGlobal.getInstance().injectInputEvent`（配 `sBypassTokens` 防重入令牌）回放原生 `KeyEvent`，确保音量短按正常弹出 `VolumeDialog`、电源短按正常息屏进入 AOD Doze。

### 2.3 辅助观测：`com.nothing.ntessentialrecorder.MainService`
- 在 `xposed_scope` 中包含 `com.nothing.ntessentialrecorder`，仅对 `MainService.onBind/onUnbind` 挂载只读日志钩子，不篡改任何行为，用于测试验证 Essential Key 自定义手势期间原生录音服务确实“零唤醒”。

---

## 3. 配置持久化、下发与 JSON 协议

### 3.1 热更新 + 冷启动双路径
1. **APP 端存储与推送**：
   - 每次修改配置均写入私有文件 `/data/user/0/io.github.rtzhang.keypilot/files/keypilot_config.json`。
   - 同步发出全局广播：
     - Action：`io.github.rtzhang.keypilot.CONFIG_UPDATE`
     - Extras：`config_json`（String，完整 JSON）、`version`（int，`1`）、`timestamp`（long）
2. **钩子端（`system_server` 内 `ConfigBridge`）接收与落盘**：
   - 使用 `Context.RECEIVER_EXPORTED`（API 33+）动态注册 `BroadcastReceiver`（若开机早期 AMS 尚未就绪则静默延后并在 `systemReady()` / 首次按键时重试注册）。
   - 收到 `CONFIG_UPDATE` 后立即解析更新 `volatile KeyPilotConfig`，同时在后台 `KeyPilot-ConfigIO` 线程采用 **写 `.tmp` → `fsync()` → `renameTo()`** 原子落盘至 `/data/system/keypilot.json`。
3. **冷启动 / 进程重启加载**：
   - `system_server` 启动后首次初始化 `ConfigBridge` 即从 `/data/system/keypilot.json` 恢复完整映射表；文件不存在或损坏时自动回落为全原生默认配置。

### 3.2 JSON 协议字段说明

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `version` | int | `1` | 协议版本号 |
| `masterEnabled` | boolean | `true` | 全局总开关，`false` 时四键全原生 |
| `showToast` | boolean | `true` | 动作执行后是否显示短暂 Toast |
| `hapticFeedback` | boolean | `true` | 自定义动作触发时是否触发 22ms 轻振动 |
| `preserveImeVoice` | boolean | `false` | 软键盘弹出时是否放行 Essential Key 原生听写分支 |
| `longPressMs` | int | `500` | 长按阈值（毫秒，范围 250–1500，对标原生 `Lz6/n;`） |
| `doublePressWindowMs` | int | `300` | 双击判定窗口（毫秒，范围 150–600，对标原生 `Le7/s0;`） |
| `powerLongPressExplicitlyBound` | boolean | `false` | **安全红线开关**：仅显式为 `true` 时才允许覆盖电源键长按 |
| `updatedAt` | long | Epoch ms | 最后更新时间戳 |
| `keys.<keyId>.enabled` | boolean | `true` | 单键独立开关（`essential`/`power`/`volume_up`/`volume_down`） |
| `keys.<keyId>.gestures.<gesture>` | object | `{"type":"system"}` | 手势绑定对象（`short`/`long`/`double`） |

### 3.3 完整 JSON 示例（对应 TESTPLAN 预设）

```json
{
  "version": 1,
  "masterEnabled": true,
  "showToast": true,
  "hapticFeedback": true,
  "preserveImeVoice": false,
  "longPressMs": 500,
  "doublePressWindowMs": 300,
  "powerLongPressExplicitlyBound": false,
  "updatedAt": 1795411200000,
  "keys": {
    "essential": {
      "enabled": true,
      "gestures": {
        "short": { "type": "camera" },
        "long": { "type": "flashlight" },
        "double": {
          "type": "launch_app",
          "package": "com.google.android.calculator",
          "label": "计算器"
        }
      }
    },
    "power": {
      "enabled": true,
      "gestures": {
        "short": { "type": "system" },
        "long": { "type": "system" },
        "double": {
          "type": "launch_app",
          "package": "io.github.rtzhang.keypilot",
          "label": "KeyPilot"
        }
      }
    },
    "volume_up": {
      "enabled": true,
      "gestures": {
        "short": { "type": "system" },
        "long": { "type": "system" },
        "double": { "type": "screenshot" }
      }
    },
    "volume_down": {
      "enabled": true,
      "gestures": {
        "short": { "type": "system" },
        "long": { "type": "system" },
        "double": { "type": "media_play_pause" }
      }
    }
  }
}
```

---

## 4. 动作目录与执行器实现表（`system_server` uid=1000）

所有自定义动作均在独立 `HandlerThread("KeyPilot-Exec")` 上以 `Binder.clearCallingIdentity()` 执行，绝不阻塞 `InputDispatcher` 线程：

| `type` | 名称 | 附加字段 | `system_server` 执行方式与兜底 |
|---|---|---|---|
| `system` | 跟随系统 (原生) | — | 状态机放行或回放原事件 |
| `none` | 无动作 (屏蔽) | — | 消费事件并记日志，不执行操作 |
| `launch_app` | 启动应用 | `package`, `label` | `PackageManager.getLaunchIntentForPackage` + `FLAG_ACTIVITY_NEW_TASK` → `startActivityAsUser(CURRENT)` |
| `launch_activity` | 启动指定 Activity | `package`, `class` | `ComponentName(package, class)` + `FLAG_ACTIVITY_NEW_TASK` → `startActivityAsUser(CURRENT)` |
| `custom_intent` | 自定义 Intent | `intentType`, `action`, `package`, `class`, `dataUri`, `extras` | 按 `activity`/`broadcast`/`service` 构造带类型 Extras 的 Intent 并以当前用户派发 |
| `flashlight` | 手电筒开关 | — | 注册 `CameraManager.TorchCallback` 跟踪状态，反射调用 `CameraManager.setTorchMode(backCamId, !on)` |
| `screenshot` | 截屏 | — | 3 级执行：① `PhoneWindowManager.takeScreenshot` → ② `ScreenshotHelper.takeScreenshot` → ③ Bypass 注入 `POWER+VOL_DOWN` 组合键 |
| `camera` | 相机 | — | 锁屏态用 `STILL_IMAGE_CAMERA_SECURE`，解锁态用 `STILL_IMAGE_CAMERA`，兜底 `com.nothing.camera` |
| `voice_assistant` | 语音助手 | — | `ACTION_VOICE_COMMAND` 兜底 `ACTION_ASSIST` |
| `media_play_pause` | 媒体播放/暂停 | — | `InputManagerGlobal.injectInputEvent(KEYCODE_MEDIA_PLAY_PAUSE)`，兜底 `AudioManager.dispatchMediaKeyEvent` |
| `media_prev` | 媒体上一曲 | — | `InputManagerGlobal.injectInputEvent(KEYCODE_MEDIA_PREVIOUS)` + `AudioManager` 兜底 |
| `media_next` | 媒体下一曲 | — | `InputManagerGlobal.injectInputEvent(KEYCODE_MEDIA_NEXT)` + `AudioManager` 兜底 |
| `volume_up` | 音量加 | — | `AudioManager.adjustSuggestedStreamVolume(ADJUST_RAISE, USE_DEFAULT, FLAG_SHOW_UI \| FLAG_PLAY_SOUND)` |
| `volume_down` | 音量减 | — | `AudioManager.adjustSuggestedStreamVolume(ADJUST_LOWER, USE_DEFAULT, FLAG_SHOW_UI \| FLAG_PLAY_SOUND)` |
| `ringer_cycle` | 响铃/振动/静音循环 | — | `NORMAL → VIBRATE → SILENT → NORMAL`，优先反射 `AudioManager.setRingerModeInternal`，兜底 `setRingerMode` |
| `nav_back` | 返回 | — | `InputManagerGlobal.injectInputEvent(KEYCODE_BACK)` |
| `nav_home` | 主页 | — | `InputManagerGlobal.injectInputEvent(KEYCODE_HOME)`，兜底 `ACTION_MAIN + CATEGORY_HOME` |
| `nav_recents` | 最近任务 | — | `InputManagerGlobal.injectInputEvent(KEYCODE_APP_SWITCH)` |
| `open_settings` | 打开系统设置 | — | `Settings.ACTION_SETTINGS` + `FLAG_ACTIVITY_NEW_TASK` |
| `lock_screen` | 锁屏 | — | 反射 `PowerManager.goToSleep(uptime, 4, 0)`，兜底 Bypass 注入 `KEYCODE_POWER` |

---

## 5. 安全设计与 Fail-Safe 机制

1. **安全红线 ①：电源键长按防误锁双门禁**
   - 配置默认 `power.long = system` 且 `powerLongPressExplicitlyBound = false`。
   - UI 侧在电源卡片展示醒目红色安全警告；用户试图绑定电源长按时必须通过二次确认弹窗才会置位 `powerLongPressExplicitlyBound = true`。
   - 钩子侧 `getEffectiveAction(POWER, LONG)` 强制校验 `powerLongPressExplicitlyBound`，即使 JSON 被误编辑未置位该标志仍永久放行原生电源菜单。
2. **安全红线 ②：硬件组合键永久放行护盾（Chord Shield）**
   - `StandardKeysHook` 实时维护 `POWER` / `VOLUME_UP` / `VOLUME_DOWN` 三键物理按下集合 `sPhysicallyDown`。
   - 一旦检测到 `POWER+VOL_DOWN`（截屏）、`POWER+VOL_UP`（电源菜单）或 `VOL_UP+VOL_DOWN`（无障碍快捷）并发按下：立即取消双键 `GestureStateMachine`、若首键此前被 KeyPilot 暂扣则毫秒内向 `InputManager` 补发首键 Bypass `DOWN`、放行次键 `DOWN` 与后续双键 `UP`，确保 `KeyCombinationManager` 原生 100% 命中。
3. **安全红线 ③：异常全兜底回原生（Zero-Crash Guarantee）**
   - 所有 Xposed 钩子回调、`Handler` 定时任务、反射调用、磁盘 I/O 与动作执行均被 `try / catch (Throwable)` 包裹。
   - `beforeHookedMethod` 中发生任何异常均不调用 `param.setResult(...)`，系统无缝回落原生链路，绝不导致 `system_server` 崩溃或 Watchdog ANR。
4. **防递归回环保护**
   - `StandardKeysHook` 使用带 `±2ms` 误差容限与 `3s` TTL 的 `sBypassTokens` 注册自身注入的回放事件；`EssentialKeyHook` 使用 `ThreadLocal<Boolean> sBypassing` 隔离 `invokeOriginalMethod` 回放，彻底杜绝无限递归。
5. **可选 Magisk 守护进程严格隔离**
   - `magisk/service.sh` 默认不拉起 `keypilotd`，仅当用户主动创建 `/data/system/keypilot_use_daemon` 标记文件（如停用 Xposed 时走路线 F 备胎）才会启动，且 `keypilotd` 注册完整信号与 `atexit` 处理器确保退出即释放 `EVIOCGRAB`。

---

## 6. v2 Phase 1（P1）增量设计：上下文分流、P1 动作集与 6 项防坑开关

> 详细规划见 [V2-PLAN.md](file:///D:/Hermes%20Desktop/KeyPilot/docs/V2-PLAN.md)，社区与技术调研见 [V2-CONTEXT-RESEARCH.md](file:///D:/Hermes%20Desktop/KeyPilot/docs/V2-CONTEXT-RESEARCH.md) 与 [RESEARCH-community.md](file:///D:/Hermes%20Desktop/KeyPilot/docs/RESEARCH-community.md)。

### 6.1 `system_server` 上下文追踪与分流链路
- **`ForegroundTracker`（无锁事件缓存）**：
  - 主通道钩 `DisplayContent#setFocusedApp(ActivityRecord)`，辅以 `TaskChangeNotificationController#notifyTaskMovedToFront` 与 `ATMS` 动态代理 `ITaskStackListener`，实时更新 `volatile CachedTop`，按键路径读取 `ContextSnapshot`（`<0.1ms`，零 `WindowManagerGlobalLock` 锁竞争、零跨进程 IPC）。
  - 实时合成 `KeyguardManager.isKeyguardLocked()`、`PowerManager.isInteractive()` 与 `InputDevice.isExternal()`（外设蓝牙自拍杆自动直通放行）。
- **`ProfileResolver`（确定性优先级解析）**：
  1. 总开关 / 单键开关校验 → 关闭则 `SYSTEM`。
  2. 安全红线 ①（`POWER + LONG` 未显式授权 → 强制 `SYSTEM`）。
  3. 外设蓝牙快门直通保护（`isExternalDevice && VOLUME±` → `SYSTEM`）。
  4. **防坑开关 ① 全局 App 黑名单 (`blacklistPackages`)**：命中立即返回 `SYSTEM`，实现 0ms 纯原生透传。
  5. **防坑开关 ⑥ OS 5.1 共存 (`officialRemapPolicy=yield_all`)**：EK 全手势让位官方。
  6. 有序 `profiles[]` 按 `priority` 降序匹配 `ContextMatcher`（`packages` 精确/通配 `*`、`excludePackages`、`activityRegex`、`keyguard`、`screen`）；未覆写或声明 `inherit` 的槽位自动穿透下一层。
  7. **防坑开关 ② 全局息屏/锁屏门禁 (`allowWhenScreenOff` / `allowWhenKeyguardLocked`)**。
  8. **防坑开关 ⑥ OS 5.1 单击让位 (`officialRemapPolicy=yield_single_click`)**：未命中任何 per-app Profile 时的常规 EK 短按让位官方，同时保留相机内快门等场景覆写与双/长按。
  9. 回退 v1 全局 `keys` 4×3 矩阵。

### 6.2 JSON v2 协议增量字段（100% 向后兼容 v1）
`KeyPilotConfig.SCHEMA_VERSION = 2`（加载 `version: 1` 无 `profiles` 配置时行为与 v1 完全一致）：

| 顶层 / 对象字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `version` | int | `2` | 协议版本号（向下兼容 `1`） |
| `allowWhenScreenOff` | boolean | `true` | 全局息屏生效开关 |
| `allowWhenKeyguardLocked` | boolean | `true` | 全局锁屏生效开关 |
| `officialRemapPolicy` | string | `"takeover"` | OS 5.1 官方 EK 重映射策略：`takeover` \| `yield_single_click` \| `yield_all` |
| `blacklistPackages` | string[] | `[]` | 全局黑名单（银行/支付/游戏 0ms 旁路，支持 `*` 通配） |
| `profiles[]` | object[] | `[]` | 场景分流列表：`{id, name, enabled, priority, experimental?, match, bindings}` |
| `ActionSpec.passthroughDefault` | boolean | `false` | 防坑开关 ③：触发动作后同时放行原生按键事件（不吞键） |
| `ActionSpec.shutterTier / shutterKey / pulseMs` | string/int | `auto/auto/35` | 防坑开关 ⑤：快门三档策略（Tier 1 系统键；GCam 自动避开 27 走 Bypass 音量−；OpenCamera/Aperture 走 `KEYCODE_CAMERA=27`） |
| `ActionSpec.command / seekMs / simulateMediaButton` | string/int/bool | `toggle/15000/false` | `media_transport` 精准媒体控制（`MediaSessionManager` 直控 + Spotify 模拟键） |
| `ActionSpec.presetId / keyCode / edgeToast` | string/int/bool | — | A6 中文内置包（微信/支付宝扫一扫收付款）、按键码注入、A8 侧边 Toast |

### 6.3 v2 P1 新增动作与防坑组件一览
- **新增动作（11 项）**：`inherit`、`camera_shutter`、`key_inject`、`media_transport`、`deep_link`、`in_app_preset`（微信/支付宝扫一扫与收付款码内置包）、`partial_screenshot`、`expand_notifications`、`expand_qs`、`context_var_broadcast`（`${fg_pkg}`/`${fg_app_name}`/`${fg_activity}`）、`mic_mute_ptt`；增强 `camera`（A2 指定相机包名）与 `ringer_cycle`（A8 侧边 Toast）。
- **防坑组件**：`OfficialRemapDetector`（OS 5.1 官方键只读探针）、`ConflictDetector`（检测 Google Select to Speak 与第三方无障碍按键服务冲突）、`SceneTemplates`（B1–B8 社区场景模板库，B2/B7 标注实验级）、`ProfileEditorDialog` & `SafetySettingsDialog`。
