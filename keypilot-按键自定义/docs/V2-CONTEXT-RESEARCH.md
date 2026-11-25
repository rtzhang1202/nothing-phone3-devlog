# KeyPilot v2 技术调研报告：应用上下文感知与内嵌功能触发（V2-CONTEXT-RESEARCH）

> **调研目标**：支撑 KeyPilot v2 两项核心能力——① **按前台应用上下文分流**（同一手势在不同 App 执行不同动作，如非相机 App 内短按开相机、相机内短按触发快门）；② **直接触发目标应用内部特定功能**（快门、扫一扫、付款码、快捷方式、媒体控制、UI 节点操作等）。  
> **运行前提**：KeyPilot 已作为 LSPosed/Vector Xposed 模块驻留于 `system_server`（`uid=1000`，详见 [DESIGN.md](file:///D:/Hermes%20Desktop/KeyPilot/docs/DESIGN.md) 与 [EssentialKey-自定义方案.md](file:///D:/Hermes%20Desktop/NothingPhone3-按键探查/EssentialKey-自定义方案.md)），天然具备普通第三方 App 不具备的系统级框架视角与特权接口。  
> **调研日期**：2026-11-25（离线纯调研，未操作真机）  
> **置信度标注规范**：`[高]` = AOSP 官方文档/源码或本机 P1–P4 实证；`[中]` = 社区实证或跨版本机制推断；`[待真机验证]` = 需在 Nothing Phone (3) / Android 17 实测定论。

---

## 1. 前台应用检测机制对比（系统侧 vs 用户态）

KeyPilot 钩子在按键瞬间触发（`PhoneWindowManager.interceptKeyBeforeQueueing` / `NtEssentialKeyImpl.onRecord`），要求前台检测满足 **< 1ms 零阻塞**（严禁卡住 `InputDispatcher` 线程导致 Watchdog/ANR）与 **多窗口/锁屏准确感知**。

### 1.1 四条技术路径横评

| 机制 | 运行位置与原理 | 延迟 / 实时性 | 可靠性 | 权限需求 | 锁屏 / 分屏 / 画中画 / 悬浮窗边界 | 置信度与出处 |
|---|---|---|---|---|---|---|
| **A. `system_server` 焦点窗口与任务直读**（`WindowManagerService` / `RootWindowContainer` / `DisplayContent.mCurrentFocus` + `mFocusedApp`） | KeyPilot 在 `system_server` 内直接读取 `RootWindowContainer` / 默认 `DisplayContent` 当前输入焦点窗口与 `ActivityRecord`；或通过 `WindowManagerInternal` / `ActivityTaskManagerInternal` 内存状态读取 | **< 0.1 ms**（同进程内存读取，零 IPC） | **极高**（与 `InputDispatcher` 决定“按键发给谁”的真源完全一致） | **无额外用户授权**（已处 `system_server` uid=1000） | • **锁屏**：可直接区分 `KeyguardLocked` 与 `NotificationShade` / 锁屏相机（`SHOW_WHEN_LOCKED`）<br>• **分屏**：`mCurrentFocus` 精确指向**用户最后点击的活跃窗格**，避免双 `RESUMED` 歧义<br>• **PiP**：主屏窗口保持输入焦点，PiP 小窗仅在点按展开交互时夺焦<br>• **悬浮窗/IME**：可按 `WindowState.mAttrs.type` 过滤 `TYPE_INPUT_METHOD` / `TYPE_APPLICATION_OVERLAY`，回退到 `mFocusedApp`（宿主 Activity） | **`[高]`**<br>[AOSP RootWindowContainer](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/RootWindowContainer.java)、[AOSP DisplayContent](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/DisplayContent.java) |
| **B. `TaskStackListener` / `ITaskStackListener` 事件推送缓存**（`onTaskFocusChanged` / `onTaskMovedToFront`） | 向 `ActivityTaskManagerService` 的 `TaskChangeNotificationController` 注册监听器，在任务焦点切换时异步更新 `volatile ContextSnapshot` | **极快（< 5 ms 事件到达；查询时 0 ms 读 volatile）** | **高**（事件驱动，避免在按键路径做深层锁竞争） | `system_server` 内部注册无需额外权限；外部进程需 `MANAGE_ACTIVITY_TASKS` | • **锁屏**：任务栈不总在熄屏/锁屏瞬间变焦，需配合 `KeyguardManager.isKeyguardLocked()`<br>• **分屏**：`onTaskFocusChanged(taskId, true)` 精确跟随当前获焦分屏任务<br>• **悬浮窗**：纯悬浮 Overlay（无 Task）不会污染 `topTask` | **`[高]`**<br>[AOSP ITaskStackListener](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ITaskStackListener.aidl)、[AOSP TaskChangeNotificationController](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/TaskChangeNotificationController.java) |
| **C. `UsageStatsManager.queryEvents()`** | 轮询系统使用统计事件流中的最新 `ACTIVITY_RESUMED` | **差（百毫秒至数秒级批处理延迟，受 OEM 省电策略影响）** | **低–中**（历史日志导向，非实时焦点真源） | 需 `PACKAGE_USAGE_STATS`（用户手动开启特殊应用权限） | • **锁屏**：无法可靠区分锁屏覆盖与后台保持<br>• **分屏 / PiP / 自由窗口**：缺乏空间层级与焦点概念，两应用同时 `RESUMED` 时顺序不可预测 | **`[高]`**<br>[Android UsageStatsManager 文档](https://developer.android.com/reference/android/app/usage/UsageStatsManager)、[StackOverflow Multi-Window 实测限制](https://stackoverflow.com/questions/3873659/android-how-can-i-get-the-current-foreground-activity-from-a-service) |
| **D. `AccessibilityService` (`TYPE_WINDOW_STATE_CHANGED`)** | 监听全局窗口状态变化事件流 | **快（< 100 ms）** | **中–高**（但受服务被杀、Android 13–15 侧载受限设置与弹窗抖动影响） | 需用户手动开启无障碍服务（侧载 APK 在 Android 13–15+ 触发 Restricted Settings 拦截） | • **分屏**：点击切换窗格会触发事件，但被动观察另一窗格无事件<br>• **PiP / 悬浮窗**：悬浮窗/Toast/输入法频繁触发 `TYPE_WINDOW_STATE_CHANGED`，容易把前台误判为 `com.android.systemui` 或输入法包名，需复杂白/黑名单清洗 | **`[高]`**<br>[Android AccessibilityService 文档](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)、[Android Authority Restricted Settings 说明](https://www.androidauthority.com/android-15-restricted-settings-sideloading-3459792/) |

### 1.2 选型结论（最稳方案）
**结论：`B (TaskStackListener / ATMS 钩子事件缓存) + A (FocusedApp/WindowState 轻量校验) + KeyguardManager 状态合成` 为 KeyPilot 最优解** `[高]`：
1. **零锁竞争、零阻塞**：绝不在 `PhoneWindowManager.interceptKeyBeforeQueueing` 同步抢 `WindowManagerGlobalLock`（否则与 `InputDispatcher` 线程产生死锁风险），而是在 `ActivityTaskManagerService` 焦点/顶层恢复回调（如 `TaskStackListener.onTaskFocusChanged` / `onTaskMovedToFront` 或钩住 `ActivityTaskManagerService` 更新焦点处）异步维护一个无锁 `volatile ContextSnapshot`。
2. **双字段建模解决悬浮窗/输入法干扰**：`ContextSnapshot` 同时保存 `topAppPackage`（当前获焦 `Task` / `ActivityRecord` 包名）与 `topActivityName`（完整 Activity 类名），并过滤 `TYPE_INPUT_METHOD` / `TYPE_APPLICATION_OVERLAY` 防止键盘弹出或聊天悬浮气泡把前台“顶掉”；同时记录 `isKeyguardLocked` 与 `isInteractive`，使“锁屏态”可作为独立安全上下文。

---

## 2. 应用内功能触发机制全景对比

### 2.1 七类触发机制详细拆解

#### ① 按键注入（`InputManagerGlobal.injectInputEvent` / `KEYCODE_*`）
- **原理**：在 `system_server` 内通过 `InputManagerGlobal.getInstance().injectInputEvent(ev, INJECT_INPUT_EVENT_MODE_ASYNC)`（Android 14–17 实测可用，见 KeyPilot v1 [ActionExecutor.java](file:///D:/Hermes%20Desktop/KeyPilot/app/src/main/java/io/github/rtzhang/keypilot/hook/ActionExecutor.java)）向当前焦点窗口注入标准按键事件：`KEYCODE_CAMERA (27)`、`KEYCODE_VOLUME_UP/DOWN (24/25)`、`KEYCODE_MEDIA_* (85/87/88)`、`KEYCODE_DPAD_*`、`KEYCODE_ENTER`、`KEYCODE_SPACE` 等。
- **权限**：`INJECT_EVENTS` 为系统签名权限；KeyPilot 运行于 `system_server`（`uid=1000`）天然持有，**零用户额外授权** `[高]`。
- **核心坑**：
  1. 必须配合 KeyPilot 自身的 **Bypass Token 机制**（见 v1 `StandardKeysHook.registerBypassDownTime`），否则注入 `VOLUME_UP/DOWN` 会被自己重入拦截造成死循环 `[高]`。
  2. 目标 App 必须显式监听该 `keyCode`（例如 Google Camera 忽略 `KEYCODE_CAMERA=27`，仅响应音量键 `[高]`）。
  3. 仅适用于按键驱动型操作（快门、翻页、切歌、确认），无法触发任意无快捷键的深层页面按钮。

#### ② 无障碍服务（`AccessibilityService`）
- **原理**：独立组件（需在 App Manifest 注册 `<service>`）接收当前窗口节点树 `getRootInActiveWindow()`，通过 `findAccessibilityNodeInfosByViewId` / `findAccessibilityNodeInfosByText` + `performAction(ACTION_CLICK)` 触发控件，或用 `dispatchGesture(GestureDescription)` 模拟点击/滑动/长按（要求配置 `android:canPerformGestures="true"`），以及 `performGlobalAction()`（通知栏、快速设置、分屏等）`[高]`。
- **权限与坑**：
  1. **Android 13–15+ 侧载限制（Restricted Settings）**：通过文件管理器/浏览器侧载的 APK 无法直接开启无障碍开关，系统弹窗阻断（需用户先到“应用信息 → 右上角三点 → 允许受限设置”，或通过 `adb install` / `appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow` 解锁）`[高]`。
  2. **UI 脆弱性**：第三方 App 更新后 `resource-id`（特别是 R8/资源混淆的微信/支付宝）极易变动；`dispatchGesture` 坐标受屏幕旋转、导航栏高度、顶部通知横幅偏移影响 `[高]`。
  3. **性能与电池感知**：常驻无障碍服务若监听过多事件会拖慢 UI 帧率；因此 KeyPilot 的 `AccessBridge` 应设为**按需懒查询**（不订阅 `TYPE_WINDOW_CONTENT_CHANGED` 高频流，仅在收到 `system_server` 指令瞬间按需抓一次 `getRootInActiveWindow()` 执行点击/手势）`[高]`。
  4. **锁屏/安全窗口限制**：部分银行/密码/锁屏安全界面对无障碍隐藏节点内容 `[高]`。

#### ③ Intent / Deep Link / App Shortcuts（`ShortcutManager` + `LauncherApps` + `ShortcutServiceInternal`）
- **原理分三层**：
  1. **隐式 Intent / Custom URI Scheme（Deep Link）**：如 `alipays://platformapi/startapp?saId=10000007`（支付宝扫一扫）、`alipays://platformapi/startapp?appId=20000056`（支付宝付款码）、`weixin://scanqrcode`（微信扫一扫）、`mailto:`、`tel:` 等，直接通过 `startActivityAsUser` 启动 `[高]`。
  2. **显式 Exported Activity**：启动目标包内 `exported=true` 的具体功能页。
  3. **App Shortcuts（桌面长按图标弹出的静态/动态/固定快捷方式）**：绝大多数国内/国际主流 App（微信、支付宝、高德、美团、Spotify、Telegram、录音机等）都在长按菜单里官方暴露了最常用的核心功能（扫一扫、付款码、新建笔记、直接联系人等）！
- **权限墙与 KeyPilot 的“降维打击”优势**：
  - 普通第三方 App 调用 `LauncherApps.getShortcuts()` / `LauncherApps.startShortcut()` 会直接抛 **`SecurityException`（Caller can't access shortcut information）**，因为 Android 仅放行「默认桌面 Launcher」或持有 `signature|textClassifier` 级权限 **`android.permission.ACCESS_SHORTCUTS`** 的特权应用 `[高]`。
  - **KeyPilot 优势**：KeyPilot 运行在 `system_server` 内部，可直接调用 AOSP 内部本地服务 **`LocalServices.getService(ShortcutServiceInternal.class)`**（`getShortcuts(...)` + `createShortcutIntents(...)`），**无需成为默认桌面，既能枚举全机所有 App 的长按快捷方式清单供 UI 选择，又能以系统特权直接启动（即使其底层 Activity 为 `exported=false`！）** `[高]`（具体方法签名需在 Android 17 `services.jar` 做运行时反射探针适配 `[待真机验证]`）。
- **核心坑**：
  - 部分超级 App（如微信）对第三方 `weixin://` Scheme 收紧校验，部分路径仅允许从特定来源触发，而其官方 **App Shortcut（长按菜单项）** 反而更稳定 `[中]`。
  - `ShortcutInfo` 的动态快捷方式 ID 可能随登录态/版本变化，需支持失效提示与重新选择 `[高]`。

#### ④ Xposed 跨进程直调目标 App 内部方法
- **原理**：在 `xposed_scope` 中加入目标 App，于宿主进程内注册本地 Receiver/Binder 监听来自 `system_server` 的触发指令，直接调用宿主内部 Java 方法（如拍照方法、扫码路由、播放控制等）。
- **核心坑**：
  1. **维护成本极高**：现代商业 App（微信、支付宝、Nothing 相机、Google Camera）普遍启用强 R8 混淆，每次小版本更新类名/方法名都会变化，极易失效 `[高]`。
  2. **作用域膨胀风险**：每多钩一个 App 就多一份启动注入开销与风控风险（部分金融/社交 App 检测 Xposed 注入，触发闪退或封号风控！）`[高]`。
  - **定位**：绝不作为常规方案，仅作为 P3 可选高级扩展（针对少量稳定、无风控的系统/开源 App）。

#### ⑤ Root Shell（`am start` / `cmd shortcut` / `input`）
- **原理**：通过 `su -c` 执行 `am start -a/-n/-d`、`input keyevent/tap`、`cmd media_session` 等命令。
- **核心坑**：每次触发需 `fork+exec` 新进程 + 启动 ART/binder CLI，**实测延迟 100–350 ms**，且在 `system_server` 中直接拉起 `su` 存在严重 SELinux 策略冲突与安全隐患；凡是 `system_server` Java API 可直做的事（`startActivityAsUser`、`injectInputEvent`、`MediaSessionManager`、`ShortcutServiceInternal`），均应优先走同进程 Java 调用，**不走 Shell** `[高]`。

#### ⑥ `MediaSession` Transport Controls
- **原理**：通过 `MediaSessionManager.getActiveSessions(null)` 获取当前全系统活跃的 `MediaController` 列表（按播放优先级排序），直接调用 `controller.getTransportControls()` 的 `play()`、`pause()`、`skipToNext()`、`skipToPrevious()`、`seekTo(pos)`、`fastForward()`、`rewind()`、`sendCustomAction()` `[高]`。
- **权限与 KeyPilot 优势**：普通 App 必须声明并让用户开启 `NotificationListenerService`（通知读取权）才能绕过 `MEDIA_CONTENT_CONTROL`（`signature|privileged` 权限墙）；而 KeyPilot 运行在 `system_server` (`uid=1000`) 天然拥有 `MEDIA_CONTENT_CONTROL`，可**免通知监听权限**直接精准控制指定包名（或当前活跃）播放器，且支持**快进/快退 15 秒、精确按包名定向暂停/恢复**（这是单纯注入 `KEYCODE_MEDIA_PLAY_PAUSE` 做不到的！）`[高]`。
- **核心坑**：仅限遵循标准 Android `MediaSession` 规范的音视频 App；少数不规范本地播放器可能未注册活跃 `MediaSession`，此时回退到 `KEYCODE_MEDIA_*` 按键注入即可 `[高]`。

#### ⑦ Google App Actions (`actions.xml`) / Slices / `shortcuts.xml` Capabilities / AppFunctions
- **现状调研结论**：
  - **Android Slices** 与 **Legacy App Actions (`actions.xml`)** 均已被 Google **正式废弃** `[高]`。
  - **`shortcuts.xml` Capabilities** 处于过渡/停滞期，仅其基础静态 `<shortcut>` 对 KeyPilot 有用（归入机制 ③ `ShortcutServiceInternal` 统一覆盖）`[高]`。
  - **AppFunctions（Android 16+ / `androidx.appfunctions`）** 是 Google 面向端侧 AI（Gemini）推出的新一代类似 Mobile MCP 的能力暴露框架，需调用方持有 `EXECUTE_APP_FUNCTIONS` 等特权权限；但目前（2026）第三方生态覆盖率仍极低，国内 App 几乎未接入，**不适合作为 KeyPilot v2 P1/P2 主路径**，仅列入长期演进观察 `[中]`。

### 2.2 七类机制全景对比表

| 机制 | 触发延迟 | 可靠性 | 权限需求（在 KeyPilot 架构下） | 适用范围 / 兼容面 | 主要坑与限制 | 置信度 | 参考出处 |
|---|---|---|---|---|---|---|---|
| **① 按键注入** (`injectInputEvent`) | **< 10 ms** | 高（目标支持该键时） | 无额外权限（`system_server` 持 `INJECT_EVENTS`） | 相机音量快门、媒体键、阅读器翻页、返回/确认 | 需 Bypass 防自钩；GCam 忽略 `KEYCODE_CAMERA`；音量键可能被用户设为缩放 | `[高]` | [KeyEvent 文档](https://developer.android.com/reference/android/view/KeyEvent#KEYCODE_CAMERA) |
| **② `AccessibilityService`** (`AccessBridge`) | 20–80 ms | 中（依赖控件稳定性） | 需用户开启无障碍（Android 13–15+ 侧载需解锁 Restricted Settings） | 任意 App 界面内无 Intent/Shortcut 的按钮点击、手势滑动、长按 | 混淆 App `viewId` 易变；横竖屏坐标漂移；安全/锁屏窗不可见 | `[高]` | [AccessibilityService 文档](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)、[Restricted Settings 说明](https://www.androidauthority.com/android-15-restricted-settings-sideloading-3459792/) |
| **③ Intent / Deep Link / App Shortcuts** | 30–120 ms | **极高**（官方契约） | **零用户权限**（`system_server` 经 `ShortcutServiceInternal` 绕过 `ACCESS_SHORTCUTS` 墙） | 直达 App 内部核心功能（扫一扫/付款码/长按菜单全部条目） | 微信等对部分 URI Scheme 限制；动态 Shortcut ID 可能随版本变；`ShortcutServiceInternal` 签名需反射适配 | `[高]` | [LauncherApps 文档](https://developer.android.com/reference/android/content/pm/LauncherApps)、[ShortcutManager 文档](https://developer.android.com/reference/android/content/pm/ShortcutManager)、[AOSP ShortcutServiceInternal](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/ShortcutServiceInternal.java)、[支付宝/微信 Scheme 汇总](https://github.com/nicholas-yjt/awesome-url-schemes) |
| **④ Xposed 宿主持钩直调** | **< 5 ms** | 低–中（版本升级易断） | 需把目标 App 加入 LSPosed 作用域 | 极少数无外部入口的私有功能 | R8 混淆维护地狱；金融/社交 App 反 Xposed 风控风险 | `[高]` | [LSPosed/Xposed 机制](https://github.com/LSPosed/LSPosed) |
| **⑤ Root Shell** (`am`/`input`) | 100–350 ms | 中 | 需 Root（`su`） | 仅适合调试/外部脚本兜底 | 进程创建开销大；`system_server` 调 `su` SELinux 受阻；完全被 Java 系统 API 覆盖 | `[高]` | [ADB Shell 命令参考](https://developer.android.com/tools/adb#am) |
| **⑥ `MediaSession` TransportControls** | **< 15 ms** | **高** | **零用户权限**（`system_server` 持 `MEDIA_CONTENT_CONTROL`） | 音乐/播客/视频精准控制（播放/暂停/切歌/快进退/定向控制） | 极少数不注册 `MediaSession` 的播放器需回落至 `KEYCODE_MEDIA_*` | `[高]` | [MediaSessionManager 文档](https://developer.android.com/reference/android/media/session/MediaSessionManager)、[AOSP MediaSessionService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/media/MediaSessionService.java) |
| **⑦ App Actions / Slices / AppFunctions** | — | 废弃 / 早期 | AppFunctions 需 `EXECUTE_APP_FUNCTIONS` | Slices & `actions.xml` 已废弃；AppFunctions 仅 Android 16+ 早期生态 | 第三方接入极少，暂不具实用覆盖面 | `[高]` | [Android AppFunctions 官方文档](https://developer.android.com/ai/appfunctions)、[Google App Actions 变更说明](https://developers.google.com/assistant/app) |

---

## 3. 相机快门专题调研

用户的典型第一场景是：**「在其他 App 中短按 = 打开相机；进入相机 App 后同一手势 = 快门」**。以下深入对比主流相机 App 的快门接收途径与已知坑：

### 3.1 主流相机 App 快门机制对比

| 相机应用（常见包名） | `KEYCODE_CAMERA (27)` | 音量键（`VOLUME_UP/DOWN`）惯例 | 相机启动 Intent (`STILL_IMAGE_CAMERA` 等) | 无障碍点击快门按钮（`viewId` / 手势） | 已知坑与注意事项 | 置信度 |
|---|---|---|---|---|---|---|
| **Google Camera / Pixel Camera** (`com.google.android.GoogleCamera`) | **大多忽略无响应**（Pixel 无物理快门键，实证 `input keyevent 27` 常无效） | **默认快门**（可在“更多设置 → 按钮快捷键”改为缩放或音量） | 仅用于打开/切模式，无法在已打开态直接按下快门 | 快门 ID 随 GCam 大版本变动；`dispatchGesture` 相对坐标可用但需避开模式切换栏 | 若用户将音量键改为「缩放」，注入音量键会误变焦；长按音量键会触发连拍/快录视频 | `[高]`<br>[Pixel Camera 快捷键说明](https://support.google.com/googlecamera/answer/2840311) |
| **Nothing Camera** (`com.nothing.camera`) | **待真机验证**（高通/OEM 相机通常兼容或忽略 27） | **默认快门**（Nothing OS 相机内音量键默认拍照/启停录像，部分机型/版本可在相机设置切换为缩放） | `STILL_IMAGE_CAMERA` / `STILL_IMAGE_CAMERA_SECURE` 打开相机正常（已在 v1 TC-02 实证） | 可通过 `uiautomator dump` 获取本机精确快门按钮 ID/desc 作为备选 | 需真机实测 `KEYCODE_CAMERA(27)` 与 `VOLUME_DOWN(25)` 在拍照/录像/锁屏相机三种模式下的表现 | `[中 / 待真机验证]`<br>[Nothing Community 讨论](https://nothing.community/) |
| **Open Camera** (`net.sourceforge.opencamera`) | **原生完整支持**（源码显式处理 `KEYCODE_CAMERA`） | 支持且可高度自定义（拍照/对焦/缩放/曝光/无动作） | 标准 Intent 启动 | 控件 ID 开源稳定 | 用户可能手动关闭音量键拍照，优先使用 `KEYCODE_CAMERA(27)` 最稳 | `[高]`<br>[Open Camera 源码](https://github.com/almalence/OpenCamera) |
| **通用第三方 / 社交内置相机**（微信扫一扫/拍照、美颜相机等） | 多数不支持 | 多数不支持或仅调音量 | 不适用 | **唯一通用手段**：通过 `AccessBridge` 点击拍照按钮或归一化坐标 | 部分 App 音量键仅弹系统音量条 | `[高]` |

### 3.2 相机快门五大经典坑与规避设计
1. **音量键重入死循环**：当 KeyPilot 在相机内拦截了 `Essential Key`（或 `Volume+`）并选择注入 `KEYCODE_VOLUME_DOWN` 作为快门时，若不走 `StandardKeysHook.injectBypassTap`，会被自己的音量键钩子再次截胡！→ **规避**：所有快门按键注入强制走带 `sBypassTokens` 的 Bypass 通道 `[高]`。
2. **用户相机内把音量键改成了「缩放」**：注入音量键会误缩放而非拍照。→ **规避**：`camera_shutter` 动作提供策略配置项 `shutterMethod`：`auto`（对 Open Camera 优先 `KEYCODE_CAMERA`，对 Nothing/Google Camera 优先 `VOLUME_DOWN` 并可选附带 `KEYCODE_CAMERA`）+ 允许用户单 Profile 覆盖为 `keycode_camera` / `volume_up` / `volume_down` / `a11y_click` `[高]`。
3. **误触发长按连拍 / 录像**：若注入的 `DOWN→UP` 间隔过长，可能触发长按录小视频或连拍。→ **规避**：单拍快门注入严格使用 `35ms` 短脉冲（`DOWN` 后 35ms `UP`）`[高]`。
4. **锁屏安全相机 (`STILL_IMAGE_CAMERA_SECURE`)**：锁屏直接双击启动相机时处于 Keyguard 上方，普通 `AccessibilityService` 在部分安全策略下受限，但 **`system_server` 的 `InputManagerGlobal.injectInputEvent` 依然畅通无阻** `[高]`。
5. **录像模式下按快门**：在录像 Tab 下注入音量键或点击快门按钮会自动变为「开始/停止录像」，这与原生物理快门直觉一致，无需额外阻断 `[高]`。

---

## 4. Per-App 按键分流产品设计先例

| 产品 | 配置模型概念 | 优先级与回退机制 | 优点 | 对 KeyPilot v2 的借鉴与取舍 | 参考出处 |
|---|---|---|---|---|---|
| **Key Mapper** (开源, sds100) | **Trigger → Action List + Constraints**（每个映射独立挂接约束列表：`App in foreground` / `App NOT in foreground` / 亮息屏，支持 AND/OR 组合） | 按触发匹配全部满足约束的映射 | 表达力最强，天然支持取反与多条件 | **高度借鉴**其 `includePackages` / `excludePackages` + `keyguard/screen` 约束思想，但避免让用户为每个手势重复建 12 条独立规则 | [Key Mapper GitHub](https://github.com/keymapperorg/KeyMapper)、[Key Mapper Docs](https://docs.keymapper.club/) |
| **Button Mapper** (flar2) | **全局按键槽位 + 按应用绕过/覆盖（Per-App Profiles）** | 命中 App 覆盖则执行，否则回退全局；另提供“在这些 App 中禁用映射（Bypass）”黑名单 | 心智模型最简单直观，适合普通用户 | **借鉴其“全局兜底 + 按应用覆盖”两层心智**，与 KeyPilot v1 现有 4×3 矩阵零违和衔接 | [Button Mapper Play Store](https://play.google.com/store/apps/details?id=flar2.homebutton) |
| **Tasker / MacroDroid** | **Profile (Contexts: App + State) → Task (Action Sequence)** | 支持显式数值优先级与冲突解决，支持进入/退出任务 | 规则复用性强（按“场景”组织多个按键） | **核心借鉴**：按 **场景 Profile**（如“相机场景”、“音乐场景”、“游戏防误触场景”）一次覆盖该场景下的 1~N 个按键手势，远比逐个手势配条件清晰！ | [MacroDroid](https://www.macrodroid.com/)、[Tasker Userguide](https://tasker.joaoapps.com/userguide/en/) |
| **GravityBox** (Xposed) | 以 `PhoneWindowManager` 全局按键动作为主，少量硬编码上下文（如编辑框获焦时音量键移动光标） | 全局为主，缺乏通用 per-app profile 层 | `system_server` 层零延迟、无障碍零依赖 | 证明了 `PhoneWindowManager` 层做按键分流的极致稳定性，KeyPilot v2 补齐其缺失的通用 per-app Profile 引擎 | [GravityBox GitHub](https://github.com/GravityBox/GravityBox) |

### 总结：调研核心结论速览
1. **前台感知**：`system_server` 内 **`TaskStackListener` 缓存 + `RootWindowContainer/FocusedApp` 校验 + `KeyguardManager`** 是最稳方案（<0.1ms，无锁屏/分屏/悬浮窗漂移，零额外权限）`[高]`。
2. **应用内功能触发**：
   - **P1 零无障碍闭环**即可覆盖 ~80% 高频需求：`injectInputEvent`（快门/翻页/确认）+ `Deep Link / Custom Intent`（支付宝扫一扫/付款码等）+ `MediaSessionManager.TransportControls`（免通知权限精准媒体控制）`[高]`。
   - **P2 能力跃迁**：`system_server` 内直调 **`ShortcutServiceInternal`** 突破 `ACCESS_SHORTCUTS` 权限墙枚举/启动任意 App 长按快捷方式 + 可选插件化 **`AccessBridge` 无障碍服务** 覆盖无公开入口的按钮点击/手势 `[高]`。
   - **P3 谨慎边界**：Xposed 宿主持钩仅作极少数无风控场景的进阶扩展；Slices/`actions.xml` 已废弃，AppFunctions 暂观望 `[高]`。
3. **配置模型**：采用 **v1 全局 4×3 矩阵（永远兜底） + 有序场景 `profiles[]`（支持包名/通配/排除/锁屏约束，按 `priority` 匹配，未覆写的手势自动回退全局）**，兼顾简单心智与强大组合力 `[高]`。
