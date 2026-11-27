# 按键自定义（KeyPilot 框架）—— 从四按键全息勘察到上下文感知、Material 3 与 Activity 自动探测

> 设备：Nothing Phone (3)（A024，代号 Metroid）| 系统：Nothing OS 5.0（Android 17，SDK 37）
> Build：`C5.0-260924-2115` | 内核：`6.6.127-android15-8` | Magisk v31.1 + JingMatrix/Vector v2.2（LSPosed）
> 记录日期：2026-11-24 – 2026-11-27
> 状态：✅ **已定稿** —— 四按键静态/动态全息勘察 + 离线 DEX 反汇编 + **KeyPilot v1（13/13 过）** + **v2 P1 上下文分流（11/11 过）** + **Material 3 全界面 5 轮真机视觉自审（10/10 全绿）** + **Activity 探测绑定器（7/7 判据全绿、回归 5/5 零破坏）**全部闭环
> 产物 APK：[`release/KeyPilot.apk`](release/KeyPilot.apk)（SHA256：`896ab6d1297779a5dc0bb3e66af5e10f5609d6b852c9865090df58ed9b5a6840`）
> 本机全量存证：`NothingPhone3-按键探查/`（127 份勘察+PoC 存证）· `KeyPilot/verify*/`（四期全量截图与 logcat）

---

## 一、起源：一颗 `input keyevent` 完全打不动的 Essential Key

Nothing Phone (3) 机身左侧多出了一颗专属物理按键 **Essential Key**。但原厂默认只绑定 Nothing 自家 Essential Space（短按截屏输入、长按按住录音、双按打开 Space），既不支持自定义动作，在排查自动化脚本时更撞上了一连串底层反直觉现象：

1. **全系统 184 个 `.kl` 键位文件零映射**：`P1-输入静态勘察` 实证 Essential Key 挂在 `/dev/input/event0`（`gpio-keys` 设备树节点 `ai_button`，`linux,code=250 (0x00fa)`，防抖 15ms，具 `wakeup` 唤醒能力），但系统里没有任何 `.kl` 为 `250` 分配 Android KeyCode，EventHub 只能吐出 **`KEYCODE_UNKNOWN (0)` + `scanCode=250`**——这导致常规 `input keyevent` 完全无效，自动化注入**只能走 root `sendevent`**，普通无障碍按键映射 App 也无法稳定消费它。
2. **硬编码 `scanCode == 250` 截胡**：对 `services.jar`、`nt-services.jar`、`EssentialRecorder.apk`、`EssentialSpace.apk` 做离线 `dexdump -d` 字节码精查（P4）后彻底破案——`PhoneWindowManager.handleKeyGesture` 早在 `getKeyCode()` 之前就硬编码判断 `event.getScanCode() == 250`，直接转发至未混淆公有接口 **`com.nothing.server.ext.INtEssentialKey.onRecord(KeyEvent)`**（实现类 `com.android.server.wm.NtEssentialKeyImpl`，位于 `nt-services.jar`），紧接着由 `EssentialRecorder` 的 11 态状态机完成短按 / 长按（`500ms`）/ 双按（`300ms`）分流。
3. **两条“想当然路线”在真机 PoC 中被证伪**：
   - **路线 A（Magisk 挂载 `.kl` 赋予 KeyCode）❌**：`handleKeyGesture` 按 `scanCode==250` 抢跑，改 `.kl` 反而与原生双触发；且 `/` 为只读 `erofs` 无热重载命令。
   - **路线 C（`settings put global side_button 0` 静音原生）❌**：反汇编证实 `NtEssentialKeyImpl` **0 处引用 `side_button`**；真机 PoC-1a 设为 `0` 后照常弹出 `FocusActivity` 并持锁 `95ms`。此前困惑的 `nt_essential_voice_action_hold=0` 也经字节码查明仅管软键盘弹出时的 `Essential Voice` 听写分支。
4. **最终选型（路线 B 主力 + 路线 F 备胎）**：
   - **主力路线 B（KeyPilot Xposed 模块）**：在 `system_server` 内直钩未混淆接口 `NtEssentialKeyImpl.onRecord(KeyEvent)`（接管 Essential Key）与 `PhoneWindowManager.interceptKeyBeforeQueueing`（接管电源 / 音量±，并早于下游 `NothingTweaks`），零系统分区改动、零原生麦克风/截屏副作用、支持热更新与单手势混搭放行。
   - **备选路线 F（可选 Magisk Native 守护 `keypilotd`）**：利用本机已就绪的 `/dev/uinput` 做 `EVIOCGRAB` 独占代理 `event0`（透传 `115` 音量+、截留 `250`），作为将来免 Xposed 的抗 OTA 兜底方案一并沉淀在 [`KeyPilot/magisk/`](KeyPilot/magisk/)。

---

## 二、四按键底层能力全景表（P1/P2/P4 实证）

> 注：重启后内核异步探测可能导致 `pmic_pwrkey` / `pmic_resin` 的 `event*` 编号动态漂移（如 `event2/3` ↔ `event1/2`）；KeyPilot Java 钩子按 `scanCode`/`keyCode` 判定，天然免疫节点漂移。

| 物理按键 | 内核驱动 / 典型节点 | Linux Scancode | KeyLayout → Android KeyCode | 原生短按 / 长按 / 双按行为（P2 17 项实测） | 硬件组合键规则 | KeyPilot 接管方式与安全边界 |
|---|---|---|---|---|---|---|
| **Essential Key** | `gpio-keys` (`ai_button`, 15ms 防抖, wakeup)<br>`/dev/input/event0` | **`250`** (`0x00fa`) | **无 `.kl` 映射** →<br>`KEYCODE_UNKNOWN (0)` | 短按：`FocusActivity` 截屏+输入<br>长按（≥500ms）：按住 16kHz 录音+松手保存<br>双按（<300ms）：`EssentialSpace` | 无原生组合键 | 钩 `NtEssentialKeyImpl.onRecord`；支持三手势任意自定义、混搭放行原生、软键盘 Essential Voice 放行、OS 5.1 共存让位 |
| **电源键 (Power)** | `pmic_pwrkey` (PMK8550)<br>`/dev/input/event2` | `116` (`0x0074`) | `Generic.kl` →<br>`KEYCODE_POWER (26)` | 短按：+300ms 息屏 AOD Doze<br>长按（500ms）：6 项电源菜单<br>双按（<300ms）：相机手势（本机曾被 `NothingTweaks` 劫持至 Google Wallet） | `+ 音量−` → 截屏<br>`+ 音量+` → 电源菜单 | 钩 `PhoneWindowManager.interceptKeyBeforeQueueing`；**安全红线①**：长按默认锁死原生电源菜单（需二次弹窗显式授权）；双按上游盖过 `NothingTweaks` |
| **音量+ (Vol Up)** | `gpio-keys` (`vol_up`, 15ms 防抖, wakeup)<br>`/dev/input/event0` | `115` (`0x0073`) | `gpio-keys.kl` →<br>`KEYCODE_VOLUME_UP (24)` | 短按：首按仅弹 `VolumeDialog` (`dir=0`)<br>长按：~450ms 后连续递增媒体音量 | `+ 电源` → 电源菜单<br>`+ 音量−` → 无障碍 3s（未绑服务） | 同上；仅在有自定义绑定时拦截，未绑定短按经 Bypass 令牌忠实回放原生 `VolumeDialog`；**安全红线②**：组合键护盾永久放行 |
| **音量− (Vol Down)** | `pmic_resin` (PMK8550)<br>`/dev/input/event3` | `114` (`0x0072`) | `Generic.kl` →<br>`KEYCODE_VOLUME_DOWN (25)` | 短按：首按仅弹 `VolumeDialog` (`dir=0`)<br>长按：~450ms 后连续递减媒体音量 | `+ 电源` → 截屏<br>`+ 音量+` → 同上 | 同上；直接拦截物理 `KeyEvent` 切歌，彻底根治 Tasker 等工具“媒体音量到顶时切歌失效”的顽疾 |

---

## 三、KeyPilot 系统架构（v1 基建 + v2 P1 上下文分流）

```text
[Linux evdev: event0 (EK=250, Vol+=115) / pmic_pwrkey (116) / pmic_resin (114)]
                                      │
                                      ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                 system_server 进程 (uid=1000, LSPosed 作用域 system)           │
│                                                                              │
│  1. ForegroundTracker (三通道无锁上下文缓存，O(1) 零阻塞)                       │
│     • DisplayContent#setFocusedApp + TaskChangeNotificationController        │
│       + ITaskStackListener + KeyguardManager + PowerManager + isExternal()   │
│                                                                              │
│  2. StandardKeysHook (PhoneWindowManager#interceptKeyBeforeQueueing 全重载)   │
│     ├─ Bypass Token 命中 (自回放事件) ──────────────────────► 原生放行         │
│     ├─ CHORD SHIELD (POWER+VOL± / VOL++VOL−) ──► 撤 FSM + 放行组合键          │
│     ├─ 黑名单 0ms 旁路 / 息锁屏总控 ────────────────────────► 原生放行         │
│     └─ GestureStateMachine (500ms 长按 / 300ms 双按 / Partial WL)             │
│                                                                              │
│  3. EssentialKeyHook (NtEssentialKeyImpl#onRecord + 三层兜底探针)              │
│     ├─ 总开关关 / 黑名单 / IME Voice / OS 5.1 让位 ─────────► invokeOriginal   │
│     └─ GestureStateMachine (零原生 FULL_WAKE_LOCK / 零录音绿点)                │
│                                                                              │
│  4. ProfileResolver (O(N) < 0.05ms 分流决策)                                  │
│     黑名单旁路 → 安全红线 → profiles[] 按 priority 降序匹配 → 回退全局 keys     │
│                                                                              │
│  5. ActionExecutor (独立后台线程，绝不阻塞 InputDispatcher)                     │
│     v1 20 项系统/应用动作 + v2 11 项上下文/直达动作（总计 31 项 ActionType）     │
│                                                                              │
│  6. ConfigBridge (热更新 Broadcast + 原子写盘 /data/system/keypilot.json)      │
└───────────────────────────────▲──────────────────────────┬───────────────────┘
          CONFIG_UPDATE 广播    │                          │ StatusProvider.call
          (v1/v2 JSON 热同步)   │                          │ (心跳 + 运行日志)
┌───────────────────────────────┴──────────────────────────▼───────────────────┐
│        KeyPilot 配置 APP (io.github.rtzhang.keypilot, 纯 SDK Material 3)       │
│  • 全局默认 (4×3) / 场景分流 Profiles 双 Tab · 8 套社区场景模板 · 6 项防坑开关   │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 3.1 配置协议（JSON v2，100% 向后兼容 v1）
- **热更新 + 冷启动双路径**：APP 修改后立即存私有 `files/keypilot_config.json` 并发送 `io.github.rtzhang.keypilot.CONFIG_UPDATE` 广播；`system_server` 内 `ConfigBridge` 毫秒级切换内存 `volatile` 配置，后台线程采用 `.tmp → fsync → rename` 原子持久化至 `/data/system/keypilot.json`（`system:system 0600`），重启后不打开 APP 即刻生效。
- **分层继承模型**：
  $$\text{EffectiveAction}(key, gesture, ctx) = \text{FirstMatch}(profiles, key, gesture, ctx) \;\Vert\; \text{GlobalKeys}(key, gesture)$$
  每个 `ActionProfile` 包含 `packages`（精确/通配）、`excludePackages`、`activityRegex`、`keyguard`、`screen`、`priority` 与稀疏 `bindings` 覆写表；未声明的手势自动继承全局矩阵。详细字段与示例见 [`docs/DESIGN.md`](docs/DESIGN.md) 与 [`docs/V2-PLAN.md`](docs/V2-PLAN.md)。

### 3.2 动作目录（v1 20 项 + v2 P1 扩展至 31 项）
- **v1 基础闭环（20 项）**：跟随系统原生、无动作（屏蔽）、启动应用、启动指定 Activity、发送自定义广播、打开相机、手电筒开关、全屏截屏、息屏锁屏、电源菜单、静音/铃声循环、媒体播放/暂停/上/下一曲、返回/主页/最近任务、打开设置、打开 Essential Space。
- **v2 P1 上下文与直达增强**：
  - `camera_shutter`（相机快门）：内置三档策略架构，P1 落地 Tier 1 系统键注入——真机实证 `com.nothing.camera` 首选 `KEYCODE_CAMERA (27)` 零冲突成片，`GoogleCamera*` 自动走 Bypass `VOLUME_DOWN (25)` 脉冲。
  - `in_app_preset`（中文高频内置功能包）：开箱即用直达 **支付宝扫一扫 / 付款码 / 收款码**、**微信扫一扫 / 收付款**、相机快门、Nothing 录音机。
  - `deep_link`（URI Scheme 直达）、`media_transport`（`MediaSessionManager` 精准媒体控制 + `simulateMediaButton` 兼容 Spotify）、`key_inject`（自定义 KeyCode 注入，如阅读器 `PAGE_UP`/`PAGE_DOWN`）、`partial_screenshot`（区域截图）、`ringer_cycle`（侧键轻 Toast 增强）、`expand_notifications` / `expand_qs`、`context_var_broadcast`（向 Tasker/MacroDroid 导出 `${fg_pkg}` / `${fg_activity}`）。
- **场景模板库（B1–B8）**：内置相机内短按快门（B4）、音乐 App 内切歌（B5）、游戏防误触还原原生（B6）、阅读器翻页（B1）、通话/录音 PTT 静音（B3）、前台变量广播（B8），以及标为实验级的 P2 导航缩放（B2）与微信按住说话（B7）。
- **`launch_activity` 交互升级（v2.1 Activity 探测绑定器）**：不再要求用户手填 `包名/类名`，改为「选 APP → 活动浏览器自动枚举（含非导出）→ 试开启验证 → 点选绑定」，命名可持久化并导出/导入；完整设计与验收见第六章。

### 3.3 6 项防坑开关（升格为 P1 必做基建）
1. **全局 App 黑名单（`blacklistPackages`）**：命中银行/支付/游戏等包名在最前端 0ms 纯原生旁路。
2. **息屏 / 锁屏独立总控**：全局 `allowWhenScreenOff` / `allowWhenKeyguardLocked` + 单个 Profile 细粒度覆写。
3. **保留默认动作不吞键（`passthroughDefault`）**：执行自定义动作同时经 Bypass 通道放行原生键（零递归）。
4. **无障碍冲突检测（`ConflictDetector`）**：自动扫描 Google Select to Speak（已证实破坏音量键拍照快门）及第三方抢键无障碍服务，展示 `errorContainer` 警示卡并一键跳转设置。
5. **快门三档回退 + 蓝牙自拍杆豁免**：按 `InputDevice.isExternal()` 放行外设蓝牙快门，防止误拦自拍杆。
6. **Nothing OS 5.1 官方重映射共存策略（`officialRemapPolicy`）**：预置 `takeover`（独占）/ `yield_single_click`（桌面单击让位官方，相机内单击仍走快门，双/长按继续由 KeyPilot 扩展）/ `yield_all` 三档热切换。

---

## 四、真机验证结果总览（v1 / v2 P1 / Material 3 / Activity 探测绑定器）

全部测试均在 Nothing Phone (3)（`C5.0-260924-2115`）真机闭环完成，全程 `system_server` 零崩溃、Clash/`tun0` 正常、10 个 Magisk 模块 0 disable：

| 阶段 | 验证范围 | 通过率 | 迭代修复的关键问题（真实踩坑） | 详报 |
|---|---|---|---|---|
| **v1 基建实测**（2026-11-24） | `TC-01 ~ TC-07`（EK 三手势、音量±双按、电源双按盖过 NothingTweaks、热更新+冷启动）<br>`REG-01 ~ REG-06`（音量/电源原生、电源长按菜单、双组合键护盾、总开关关闭恢复） | **13 / 13 PASS**<br>（7 TC + 6 REG） | ① **手电筒二次长按仍 ON**：`TorchCallback` 初始化竞态覆写 `sTorchEnabled`，改为先定目标 `cameraId` + 500ms 去抖后 `ON → OFF` 稳定通过；<br>② **Vector 作用域命名坑**：`system_server` 在 Vector 数据库中须加 `system/0`；<br>③ **节点漂移**：重启后 `event2/3` 漂移至 `event1/2`，KeyPilot 按 `scanCode`/`keyCode` 识别天然免疫 | [`docs/TESTPLAN_V1_RESULTS.md`](docs/TESTPLAN_V1_RESULTS.md) |
| **v2 P1 实测**（2026-11-25） | `V2-TC-01 ~ 06`（非相机开相机→相机内同手势快门、支付宝扫一扫直达、QQ 音乐音量满格切歌、OS 5.1 `yield_single_click` 热切换、分层继承+闪光灯占用兜底）<br>`V2-REG-01 ~ 05`（黑名单 0ms 旁路、`passthroughDefault` 不吞键、关闭 Profiles 回退 v1、安全红线①②） | **11 / 11 PASS**<br>（6 TC + 5 REG） | ① **相机占用闪光灯异常解包**：`Method.invoke` 将 `CameraAccessException(MAX_CAMERAS_IN_USE)` 包进 `InvocationTargetException`，解包 `getCause()` 后零 `[ERR]` 并弹友好提示「相机占用中，无法切换手电筒」；<br>② **Nothing Camera 快门定案**：实证 `KEYCODE_CAMERA (27)` 首次即成片（`38→39`）且不与音量缩放冲突，已升级为 `com.nothing.camera` 首选策略 | [`docs/TESTPLAN_V2_RESULTS.md`](docs/TESTPLAN_V2_RESULTS.md) |
| **Material 3 UI 美化自审**（2026-11-25） | 浅色/深色双模式、双 Tab、4×3 矩阵、动作/预设/应用选择器、场景编辑器、防坑开关弹窗 | **10 / 10 GREEN**<br>（5 轮自审闭环） | ① `MaxHeightScrollView` 解决弹窗底栏裁切；<br>② 零 padding 自绘 `ThumbCircleDrawable` 根治 Android `Switch` 误把 `InsetDrawable` 当光学缩进导致滑块外凸；<br>③ `ScrollView.setClipToPadding(true)` 消除滚动与状态栏碰撞；<br>④ 底部弹窗改全屏透明遮罩达成 Edge-to-Edge 零灰缝贴底；<br>⑤ 独立复审补修（Round 5）：精简状态条文案与解除 Profile 标题/副标题单行截断 | [`docs/UI_AUDIT.md`](docs/UI_AUDIT.md) |
| **Activity 探测绑定器实测**（2026-11-27） | `ACT-01 ~ ACT-08`（活动枚举完整性、非导出试开启、重命名持久化、绑定直达、导出/导入、回归、UI 自审、稳定性红线） | **7 / 7 GREEN**<br>（回归 5/5 零破坏） | ① 导入 MIME 过宽（`*/*` + OPEN_DOCUMENT）导致静默失败 → 收敛 `application/json`；<br>② 试开启 Toast 显示短类名 → 回查 `LabelStore` 回填命名；<br>③ `startActivity` 对不存在组件不抛异常 → `getActivityInfo()` 预校验后回执 | [`docs/ACTIVITY-BINDER-RESULTS.md`](docs/ACTIVITY-BINDER-RESULTS.md) |

---

## 五、Material 3 界面与真机效果展示

KeyPilot 坚持**零 Gradle / 零 Material 依赖**，全程用纯 Android SDK + `aapt2 + 两阶段 javac + d8` 裸编译（确保 Xposed 桩类 0 字节进入 `classes.dex`），手写完整 M3 设计系统（[`UiTheme.java`](KeyPilot/app/src/main/java/io/github/rtzhang/keypilot/ui/UiTheme.java)）：
- **动态取色**：直读 Android 12+ `system_accent1_*` / `system_neutral1_*` / `system_neutral2_*`，昼夜模式自动跟随壁纸色板。
- **统一圆角族**：卡片 `24dp` / 输入与下拉 `26dp` / 药丸按钮、Switch、Tab、Edge-to-Edge 底部弹窗顶角 `28dp`。
- **真实图标与清晰分隔**：[`AppIconCache.java`](KeyPilot/app/src/main/java/io/github/rtzhang/keypilot/ui/AppIconCache.java) + [`ActionIcons.java`](KeyPilot/app/src/main/java/io/github/rtzhang/keypilot/ui/ActionIcons.java) 在应用列表、高频内置预设、已绑定手势行及场景分流卡片上统一呈现圆角真实 APP 图标、所属应用名称与 `56dp` 缩进分隔线。

| 浅色 · 全局默认 Tab | 浅色 · 4×3 按键矩阵 | 浅色 · v2 场景分流 Profiles | 底部弹窗 · 中文高频内置功能 |
|---|---|---|---|
| ![全局默认顶部](screenshots/01_global_top.png) | ![全局4x3矩阵下段](screenshots/02_global_lower.png) | ![场景分流Tab](screenshots/03_profiles_tab.png) | ![中文高频内置预设](screenshots/04_preset_picker.png) |

| 底部弹窗 · 应用选择器（带图标分隔） | v2 实测 · 相机上下文分流 | 深色动态 Monet · 全局默认 | 深色动态 Monet · 场景分流 |
|---|---|---|---|
| ![应用选择器](screenshots/05_app_picker.png) | ![相机上下文实测](screenshots/06_v2_camera_context.png) | ![深色全局默认](screenshots/07_dark_global.png) | ![深色场景分流](screenshots/08_dark_profiles.png) |

---

## 六、Activity 探测绑定器（自动探测 / 试开启 / 重命名 / 社区反哺）

> **痛点**：`launch_activity` 要求手填 `包名/完整类名`，而用户既不知道 Activity 类名、也无从在桌面上看到它；对**非导出** Activity，`adb shell am start -n com.tencent.mm/.plugin.scanner.ui.BaseScanUI` 更是直接 `SecurityException`。本章把「手填类名」改造成「选 APP → 自动罗列 → 试开启验证 → 点选绑定」，并沉淀一套可导出、可反哺社区的活动命名协议。

### 6.1 功能设计

| 能力 | 实现 |
|---|---|
| 活动发现 | `PackageManager.GET_ACTIVITIES` 枚举目标包全部 Activity（含 `exported=false`），纯本地、零在线依赖 |
| 选择路径 | 动作目录「启动指定 Activity」→（主路径）选择 APP → 活动浏览器 → 点行绑定；旧手填表单降级为「高级」折叠区，历史绑定 100% 兼容 |
| 应用选择 | 复用 M3 应用选择器：真实图标 + 应用名 + 包名，新增「复制」药丸钮一键复制包名 |
| 搜索 / 懒加载 | 顶部搜索按**活动命名 / 类名**即时过滤（不区分大小写）；列表 60 条/页，滚动到底自动追加，计数行实时显示「共 N 个活动 · 已显示 M 个」 |
| 行结构 | APP 图标 · 主行（用户/社区命名，未命名回退短类名）· 副行完整组件 `package/class`（10sp，点按复制）· `导出/非导出` chip · 「试开启」「重命名」双药丸钮 |
| 命名解析 | 持久化到 APP 缓存并镜像 `/data/system/keypilot_labels.json`；优先级 `user > community > ActionSpec.label > 短类名` |
| 绑定回显 | 全局矩阵值文本显示命名、副行小字显示组件；场景分流卡摘要显示命名 + 独立组件行 |

实测规模（`PackageManager` 口径）：**微信 2047 条**、支付宝 949 条、Nothing 相机 16 条；`BaseScanUI` 搜索 1 条、`scan` 17 条。

### 6.2 用户流程

```text
手势行 →「启动指定 Activity」
   → 选择 APP（图标 / 名称 / 包名，包名可一键复制）
   → 活动浏览器：自动罗列全部 Activity（导出 + 非导出）
        ├─ 搜索 scan → 17 条；BaseScanUI → 1 条
        ├─ 试开启 → Toast「已拉起 微信扫一扫」，目标页真实进入前台
        └─ 重命名 → 输入「微信扫一扫」→ 列表即时刷新并持久化
   → 点选行完成绑定
   → 全局矩阵：主行「微信扫一扫」+ 副行 com.tencent.mm/.plugin.scanner.ui.BaseScanUI
```

### 6.3 独家能力：非导出 Activity 的「试开启」

| 路径 | 执行身份 | 结果 |
|---|---|---|
| `adb shell am start -n com.tencent.mm/.plugin.scanner.ui.BaseScanUI` | shell（uid 2000） | ❌ `SecurityException: Permission Denial: … not exported from uid 10328` |
| `CONFIG_UPDATE` + `command=TRY_LAUNCH`（KeyPilot 广播桥） | system_server（uid 1000） | ✅ `TRY_LAUNCH ok`，`mCurrentFocus=com.tencent.mm/…BaseScanUI` |

桥实现四个关键点：
1. **命令多路复用**：`CONFIG_UPDATE` extras 含 `command` 时按命令分发（`TRY_LAUNCH` / `LABELS_UPDATE`），不含则仍按整份配置热更新 —— v1/v2 既有链路零改动。
2. **身份切换**：`Binder.clearCallingIdentity()` → `startActivityAsUser(intent, CURRENT_USER)` → `restoreCallingIdentity()`。ActivityManager 按 system_server 的 `START_ANY_ACTIVITY` 权限校验，因此 `exported=false` 的活动也能拉起。
3. **严格失败语义**：`PackageManager.getActivityInfo()` 先预校验组件存在性（`startActivity` 对**不存在**的组件并不抛异常、只返回 result code，极易误报成功）；`startActivityStrict()` 解包 `InvocationTargetException` 后重抛真实 cause，失败回执 `组件不存在: … (NameNotFoundException)`。
4. **稳定性红线**：全程跑在 `KeyPilot-ConfigIO` 后台线程并 `try/catch(Throwable)`，结果经 `StatusProvider.call("try_launch_result", token)` 回执；UI 侧 6s 未回执判「钩子未响应」，异常绝不进入 system_server 主循环。

### 6.4 命名持久化与社区反哺

`keypilot.labels.v1` 协议（完整字段见 [`docs/DESIGN.md`](docs/DESIGN.md) §3.4）：

| 位置 | 角色 | 路径 |
|---|---|---|
| 随包名单 | 只读社区 / 内置命名 | [`KeyPilot/app/src/main/assets/community_labels.json`](KeyPilot/app/src/main/assets/community_labels.json)（4 条：微信扫一扫、支付宝扫一扫 / 首页、Nothing 相机） |
| APP 缓存 | 用户命名读写真源 | `/data/data/io.github.rtzhang.keypilot/files/keypilot_labels.json` |
| 钩子镜像 | 重启备份 / Toast / 日志取名 | `/data/system/keypilot_labels.json`（`system:system 0600`） |

- **格式**：扁平 map，键 = `package/full.ClassName`（兼容 `pkg/.Cls` 简写，加载时展开为全限定类名）；值 = `{label, source: user|community, updatedAt}`；`_meta` 等下划线前缀键为元数据，加载时忽略。
- **写入链路**：改名 → APP 缓存 → `LABELS_UPDATE` 广播 → `ConfigBridge` 以 `.tmp → fsync → rename` 原子镜像；force-stop 与重启后矩阵、场景卡仍显命名。
- **社区反哺**：设置 →「活动命名管理」查看 / 编辑 / 删除本地命名，并折叠查看内置名单；导出 JSON（SAF `ACTION_CREATE_DOCUMENT`，另提供文本分享），导入（SAF `ACTION_OPEN_DOCUMENT`，MIME 收敛 `application/json`）采用**补空缺优先**策略，冲突时弹「导入冲突提示」并可选「覆盖冲突项」——同一份 JSON 即可在用户间流转，逐步把常用 Activity 沉淀成社区名单。

### 6.5 真机验收（ACT-01 ~ ACT-08，7/7 全绿）

设备 Nothing Phone (3) / Android 17 / NOS 5.0；测试窗口 2026-11-27 00:38–02:05（CST）；全量存证 `verify_activity/`（截图 + XML + `stage*.json` + logcat）：

| # | 判据 | 结果 | 关键证据 |
|---|---|---|---|
| ① | activities_listed（列表完整） | ✅ | 微信 2047 / 支付宝 949 / 相机 16；`非导出` chip 与搜索过滤（`scan`→17、`BaseScanUI`→1）；微信首屏 60 条滚动追加 |
| ② | try_launch（试开启） | ✅ | 非导出 `BaseScanUI` 经桥拉起成功（同组件 shell `am start` 报 SecurityException）；Toast「已拉起 微信扫一扫」 |
| ③ | rename_persist（重命名持久化） | ✅ | force-stop + 重启后仍显命名；APP 缓存与 `/data/system/keypilot_labels.json` 内容一致 |
| ④ | bind_trigger（绑定直达） | ✅ | `volume_up.long` → `EXEC … -> Activity：微信扫一扫`，前台切至目标；场景卡显命名 + 小字组件 |
| ⑤ | export_import（导出导入） | ✅ | 261B `keypilot.labels.v1` 导出 → 删除 → 导入补缺 → 冲突提示 → 覆盖回原值 |
| ⑥ | regression（回归） | ✅ 5/5 | v1 截屏、电源长按红线、`POWER+VOL_DOWN` 组合键、普通 `CONFIG_UPDATE`、v2 相机分流全部零破坏 |
| ⑦ | ui_audit（UI 自审） | ✅ | 两轮 vision 审（审 → 修 → 复审）无碰撞 / 贴边 / 截断；修复试开启 Toast 未回填命名 |

3 轮迭代（每轮一个根因）：

| # | 根因 | 修复 |
|---|---|---|
| 1 | 导入用 `ACTION_OPEN_DOCUMENT` + `*/*` → DocumentsUI 弹「打开方式」不回传 URI，导入静默失败 | 导入 MIME 收敛为 `application/json` |
| 2 | 试开启成功 Toast 显示短类名，未体现用户命名 | `findName()` 回查 `LabelStore`（user > community > 短类名） |
| 3 | `startActivity` 对不存在的组件不抛异常 → 误报「ok」 | 钩子端 `getActivityInfo()` 预校验，缺失回执 `组件不存在` |

稳定性：单次开机会话内 `system_server` PID 恒定；全程 0 `FATAL EXCEPTION`、0 `KeyPilot: [ERR]`（两次 PID 变化均来自计划内重启）。产物 `release/KeyPilot.apk` SHA256 `896ab6d1…`。

### 6.6 真机截图

| 活动浏览器（微信 2047 条 · 60 条/页懒加载） | 命名回填 + 非导出 chip + 双药丸钮 |
|---|---|
| ![活动浏览器](screenshots/09_activity_browser_2047.png) | ![重命名行](screenshots/10_activity_renamed_row.png) |

| 试开启 Toast（非导出活动直达） | 绑定后矩阵显示命名 + 小字组件 |
|---|---|
| ![试开启](screenshots/11_trylaunch_toast.png) | ![矩阵回显](screenshots/12_matrix_bound_label.png) |

---

## 七、安装、激活与使用指南

### 7.1 直接安装（推荐）
1. 下载 [`release/KeyPilot.apk`](release/KeyPilot.apk) 并核对 [`release/KeyPilot.apk.sha256`](release/KeyPilot.apk.sha256)：
   ```bash
   sha256sum release/KeyPilot.apk
   # 预期：896ab6d1297779a5dc0bb3e66af5e10f5609d6b852c9865090df58ed9b5a6840
   adb install -r release/KeyPilot.apk
   ```
2. 在 **LSPosed / Vector 管理器** 中启用 **KeyPilot**，勾选作用域：
   - **系统框架**（`system` / `android`，核心必选）
   - `com.nothing.ntessentialrecorder`（可选，仅用于零唤醒遥测日志）
3. **重启手机**一次使 `system_server` 挂载钩子。
4. 打开 KeyPilot，确认顶部状态条显示 **`已连接 system_server · HH:mm:ss 已同步`**，即可自由配置（所有修改均为毫秒级热更新，无需再重启）：
   - 点 **「v2 相机预设」** 一键体验「非相机短按 EK 打开相机 → 进入相机同手势变快门、双按支付宝扫一扫、长按手电筒、音乐 App 切歌、游戏防误触」。
   - 点右上角 **「防坑开关」** 配置银行/支付黑名单、息锁屏开关与 Nothing OS 5.1 共存模式。

### 7.2 从源码自行构建
源码位于 [`KeyPilot/`](KeyPilot/)（已排除 keystore 与编译中间产物，首次运行会自动生成本地签名证书）：
```bash
cd KeyPilot
# 需要 Android SDK Build-Tools 37.0.0 + android-37 + JDK 17 + Python 3 (Pillow)
bash build.sh
```
构建管线执行两阶段 `javac`（`app/src/stubs` 与模块源码隔离编译），并在末尾自动运行 `apksigner verify`、`aapt2 badging` 与 `dexdump` 审计，强制校验 `de/robv/android/xposed` 桩类在 `classes.dex` 中为 **0 matches**。

---

## 八、后续演进路线图（P2 / P3）

详细技术论证见 [`docs/V2-CONTEXT-RESEARCH.md`](docs/V2-CONTEXT-RESEARCH.md)、[`docs/RESEARCH-community.md`](docs/RESEARCH-community.md) 与 [`docs/V2-PLAN.md`](docs/V2-PLAN.md)：

- **Phase 2（P2）—— 全机快捷方式引擎 + 可选无障碍插件**
  1. **`ShortcutBridge`**：在 `system_server` 反射调用 `ShortcutServiceInternal`，无需默认桌面权限即可枚举并直启任意 App 长按菜单快捷方式（彻底覆盖无公开 Deep Link 的应用内入口）。
  2. **`AccessBridge`（可选无障碍服务，默认关闭不污染 P1）**：支持 `a11y_click`（按 ViewId/文本点击控件，快门 Tier 2）、`a11y_gesture`（归一化坐标点按/滑动/长按/双指缩放，快门 Tier 3 + 实验级模板 **B2 导航缩放** / **B7 微信按住说话**）与悬浮准星节点拾取器。
- **Phase 3（P3）—— 进阶宿主直调与规则分享生态**
  - 对极少数无 Shortcut/Deep Link/快捷键的开源或系统 App 提供可选 `InAppHookBridge`（严格将银行/支付/强风控超级 App 列为禁止持钩黑名单），并支持 JSON Profile 场景包导入导出。

---

## 九、证据与文档索引

### 9.1 仓库内文档与资产（本章目录）
| 路径 | 说明 |
|---|---|
| [`KeyPilot/`](KeyPilot/) | 完整源码快照（`app/`、`magisk/`、`scripts/`、`build.sh`，不含 keystore 与 `out/`） |
| [`release/KeyPilot.apk`](release/KeyPilot.apk) + [`.sha256`](release/KeyPilot.apk.sha256) | 最终签名安装产物（v2+v3，SHA256 `896ab6d1...`） |
| [`docs/DESIGN.md`](docs/DESIGN.md) | KeyPilot v1/v2 总体架构、钩点选型、JSON 协议规范（含 §3.4 labels 协议、§3.5 `TRY_LAUNCH` 桥命令） |
| [`docs/TESTPLAN.md`](docs/TESTPLAN.md) | v1/v2 测试计划 + Activity 探测绑定器 ACT-01 ~ ACT-08 测试矩阵 |
| [`docs/V2-CONTEXT-RESEARCH.md`](docs/V2-CONTEXT-RESEARCH.md) | v2 全网技术机制调研（前台追踪 / 快门注入 / Shortcut / 无障碍对比） |
| [`docs/RESEARCH-community.md`](docs/RESEARCH-community.md) | 社区 22 用例、竞品踩坑实证与 6 项防坑开关来源 |
| [`docs/V2-PLAN.md`](docs/V2-PLAN.md) | v2 产品规划与 P1/P2/P3 分期路线图（v1.1 定稿） |
| [`docs/TESTPLAN_V1_RESULTS.md`](docs/TESTPLAN_V1_RESULTS.md) | v1 真机实测报告（13/13 PASS） |
| [`docs/TESTPLAN_V2_RESULTS.md`](docs/TESTPLAN_V2_RESULTS.md) | v2 P1 真机实测报告（11/11 PASS） |
| [`docs/UI_AUDIT.md`](docs/UI_AUDIT.md) | Material 3 全界面 5 轮真机视觉自审报告（10/10 GREEN） |
| [`docs/ACTIVITY-BINDER-RESULTS.md`](docs/ACTIVITY-BINDER-RESULTS.md) | Activity 探测绑定器验收报告（7/7 判据、回归 5/5、3 轮迭代） |
| [`screenshots/`](screenshots/) | 12 张精选真机截图（浅/深色 M3、相机上下文分流、活动浏览器/试开启/命名回显） |

### 9.2 本机全量原始存证索引（未全量入库以控制仓库体积）
- **四按键勘察全量档案（127 文件）**：`D:\Hermes Desktop\NothingPhone3-按键探查\`
  - 分报告与总报告：`P1-输入静态勘察.md`、`P2-触发行为实测.md`、`00-按键总报告.md`、`EssentialKey-自定义方案.md`（P4 离线 DEX 反汇编 + 6 路线矩阵 + PoC-1a/1b/2）
  - 原始 Dump（37 份）+ P2 动态前后截图/XML/logcat/WakeLock（88 份）
- **KeyPilot 四期实测原始存证**：
  - v1 矩阵全量前后截图、XML、logcat 与 `report.json`：`D:\Hermes Desktop\KeyPilot\verify\`
  - v2 P1 矩阵全量前后截图、XML、logcat 与 `report_v2.json`：`D:\Hermes Desktop\KeyPilot\verify_v2\`
  - M3 UI 全 10 屏高清截图与自动抓屏脚本：`D:\Hermes Desktop\KeyPilot\verify_ui\`
  - Activity 探测绑定器全量截图、XML、`stage*.json`、logcat 与 `REPORT.md`：`D:\Hermes Desktop\KeyPilot\verify_activity\`
