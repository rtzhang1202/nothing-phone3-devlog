# KeyPilot v2 产品与架构规划：应用上下文感知动作（V2-PLAN）

> **v1.1 修订记录**：合流社区调研（[RESEARCH-community.md](file:///D:/Hermes%20Desktop/KeyPilot/docs/RESEARCH-community.md)，22 用例）——扩充动作目录（吸收 A1–A10 未覆盖项）、新增「场景模板」章节（B1–B8，B2/B7 标为实验级）、新增升格为 P1 的「防坑开关」小节（含 Nothing OS 5.1 官方 Essential Key 重映射共存/让位策略）、补齐黑名单/OS 5.1/场景模板测试项，并同步更新分期切片归属与风险清单。  
> **规划依据**：[V2-CONTEXT-RESEARCH.md](file:///D:/Hermes%20Desktop/KeyPilot/docs/V2-CONTEXT-RESEARCH.md)（全网技术调研）、[RESEARCH-community.md](file:///D:/Hermes%20Desktop/KeyPilot/docs/RESEARCH-community.md)（社区 22 用例与竞品机制）、[DESIGN.md](file:///D:/Hermes%20Desktop/KeyPilot/docs/DESIGN.md)（v1 架构与 JSON 协议）、[TESTPLAN.md](file:///D:/Hermes%20Desktop/KeyPilot/docs/TESTPLAN.md)（v1 测试基线）  
> **核心用户诉求**：  
> 1. **上下文分流**：「在其他 App 中是打开相机，进入相机 App 则是快门」——同一物理按键手势按当前前台应用自动分流到不同动作。  
> 2. **直达应用内功能**：「直接打开某应用的某个功能」——跳过应用首页，一键触发扫一扫、付款码、App 快捷方式、快门、精准媒体控制或界面特定按钮。  
> **设计原则**：零破坏兼容 v1 配置与安全红线；核心路径（P1）坚持 `system_server` 零无障碍闭环；无障碍（`AccessBridge`）严格作为 P2 可选插件按需启用。

---

## 1. 功能定义与核心模型

### 1.1 `ActionProfile` 上下文分流模型
KeyPilot v2 在 v1「全局 4×3 矩阵 (`keys`)」之上叠加一层**有序场景配置表 (`profiles[]`)**：

$$\text{EffectiveAction}(key, gesture, ctx) = \text{FirstMatch}(profiles, key, gesture, ctx) \;\Vert\; \text{GlobalKeys}(key, gesture)$$

每条 `ActionProfile` 由三部分组成：
1. **元信息**：`id`（唯一标识）、`name`（显示名，如“相机快门模式”）、`enabled`（开关）、`priority`（整数优先级，数值越大越优先，默认 `100`）。
2. **上下文匹配器 (`match: ContextMatcher`)**：
   - `packages`（`String[]`，可选）：前台包名匹配列表，支持精确包名（`com.nothing.camera`）与后缀通配符（`com.google.android.GoogleCamera*`）；留空或 `["*"]` 表示任意前台 App。
   - `excludePackages`（`String[]`，可选）：排除包名列表（如在“游戏防误触”或“除相机外全部 App”场景使用）。
   - `activityRegex`（`String`，可选）：精细匹配当前 Activity 类名（例如区分相机拍照页 vs 相机设置页，或仅在视频播放全屏页生效）。
   - `keyguard`（`String`，默认 `"any"`）：`"any"` / `"unlocked_only"` / `"locked_only"`（支持锁屏安全相机等场景）。
   - `screen`（`String`，默认 `"any"`）：`"any"` / `"on_only"` / `"off_only"`（支持熄屏盲按仅手电筒/切歌）。
3. **局部覆写表 (`bindings`)**：
   - 稀疏映射 `KeyId -> GestureType -> ActionSpec`，**只需声明该场景下需要覆盖的格子**；未声明的格子（或声明为 `"inherit"` 的格子）自动**回退到更低优先级 Profile → 最终回退到 v1 全局 `keys` 绑定**；若显式设为 `"system"`，则强制放行系统原生（实现 Button Mapper 式的「在该 App 中临时还原原生」防误触黑名单！）。

**匹配与回退算法（确定性、O(N) < 0.05ms）**：
1. 校验安全红线（`masterEnabled == false` 或 `POWER.LONG` 未显式授权 → 立即返回 `SYSTEM`）。
2. 按 `priority` 降序遍历所有 `enabled == true` 的 `profiles`：
   - 若 `match.matches(ContextSnapshot)` 为 `true`，检查该 Profile 的 `bindings[key][gesture]` 是否存在且非 `inherit`：
     - 存在 → 立即命中并返回该 `ActionSpec`（日志记录命中 Profile ID）。
     - 不存在 → 继续检查下一优先级 Profile。
3. 若无 Profile 覆写该 `(key, gesture)` → 回退读取 v1 全局 `keys[key].gestures[gesture]`。

### 1.2 v2 新增动作目录（在 v1 20 项基础上增量扩展，合流 A1–A10）

| `type` | 显示名 | 所属分期 | 核心参数 | 执行机制（`system_server` / `AccessBridge`） |
|---|---|---|---|---|
| `inherit` | 继承全局（仅 Profile 内可用） | P1 | — | 显式声明不覆盖该格子，回退全局 |
| `camera_shutter` | 相机快门（A1） | P1（Tier 1）<br>P2（Tier 2/3） | `shutterTier`: `auto` \| `system_key` \| `a11y_node` \| `a11y_coord`；`shutterKey`: `auto` \| `volume_down` \| `volume_up` \| `keycode_camera`；`pulseMs` (默认 `35`) | 三档策略链：①系统键注入（Open Camera/Aperture 优先 `KEYCODE_CAMERA(27)`，Nothing/GCam 经 **Bypass 通道**注入 35ms `VOLUME_DOWN` 脉冲）→ ②P2 无障碍按包名节点点按 → ③P2 归一化坐标点按；支持按机型记忆可用档位 |
| `camera`（v1 增强） | 打开指定相机（A2） | P1 | `package` (可选，留空走系统默认，可指定 GCam 口 / Nothing Camera) | 优先启动用户指定的相机包名，留空走 v1 `STILL_IMAGE_CAMERA(_SECURE)` 逻辑 |
| `partial_screenshot` | 区域截图（A4 扩展） | P1 | — | 调用 Nothing/SystemUI 局部截屏入口（`ScreenshotHelper` 区域模式 / `com.nothing.ntscreenshot`），失败优雅回落全屏截屏；全屏截屏兼容息屏 |
| `ringer_cycle`（v1 增强） | 响铃/震动/静音切换 + 侧边 Toast（A8） | P1 | `edgeToast`: boolean（默认 `true`） | v1 铃声循环基础上增加贴近物理侧键位置的轻量化 Toast + 差异化轻振动反馈（类 iPhone Action Button 体验） |
| `expand_notifications` / `expand_qs` | 下拉通知栏 / 快速设置（A10） | P1 | — | 在 `system_server` 内反射调用 `StatusBarManager.expandNotificationsPanel()` / `expandSettingsPanel()`，零无障碍依赖 |
| `key_inject` | 注入指定按键码 | P1 | `keyCode` (int), `pulseMs` (int, 默认 35), `repeat` (int, 默认 1), `passthroughDefault` (可选) | `InputManagerGlobal.injectInputEvent`（带 `sBypassTokens` 防自钩重入），适配阅读器翻页（`PAGE_UP=92`/`PAGE_DOWN=93`）、确认（`ENTER=66`）等 |
| `media_transport` | 精准媒体控制（A5 增强） | P1 | `command`: `play` \| `pause` \| `toggle` \| `next` \| `prev` \| `seek_forward` \| `seek_back`；`seekMs` (默认 `15000`)；`targetPackage` (可选)；`simulateMediaButton` (boolean) | 在 `system_server` 内直接调 `MediaSessionManager.getActiveSessions(null)` + `TransportControls`，免通知权限；对 Spotify 等不响应标准键的播放器支持 `simulateMediaButton`（强制走 `dispatchMediaKeyEvent` + `injectInputEvent` 双路径） |
| `deep_link` | URI Deep Link 直达 | P1 | `uri` (String), `package` (可选限定), `label` | 构造 `Intent(ACTION_VIEW, Uri.parse(uri))` + `FLAG_ACTIVITY_NEW_TASK`，先 `resolveActivity` 校验，再 `startActivityAsCurrentUser` |
| `in_app_preset` | 中文高频应用内功能内置包（A6） | P1 | `presetId`: `alipay_scan` \| `alipay_pay` \| `alipay_collect` \| `wechat_scan` \| `wechat_pay` \| `camera_shutter` \| `nt_recorder_start` | 开箱即用内置封装：支付宝扫一扫（`saId=10000007`）/付款码（`appId=20000056`）/收款码（`appId=20000123`）、微信扫一扫（`weixin://scanqrcode` / P2 Shortcut 兜底）/付款码等，用户零手工填 URI/Activity |
| `context_var_broadcast` | 前台应用变量广播（B8） | P1 | `action` (String), `extras` (支持模板变量 `${fg_pkg}` / `${fg_app_name}` / `${fg_activity}`) | 触发时将当前 `ContextSnapshot` 注入 Intent Extras 下发，无缝对接 Tasker / MacroDroid 等自动化生态 |
| `app_shortcut` | 启动应用快捷方式（A7） | P2 | `package`, `shortcutId`, `label` | `system_server` 内调用 `LocalServices.getService(ShortcutServiceInternal.class)` 获取 Intent 并以系统权限直启（**免默认桌面权限，支持未导出组件**），找不到时回落至指定 Activity |
| `a11y_click` | 无障碍点击控件 | P2 | `viewIds` (`String[]` 候选), `texts` (`String[]`), `contentDescs` (`String[]`), `fallbackCoords` (`[rx, ry]` 可选) | 经 `AccessBridge` 可选无障碍服务按优先级查找匹配节点执行 `ACTION_CLICK`，未找到时可选降级到归一化相对坐标手势 |
| `a11y_gesture` | 无障碍手势（点按/长按/滑动/双指缩放） | P2 | `gestureKind`: `tap` \| `long_press` \| `swipe` \| `pinch`；归一化坐标 (`0.0–1.0`)；`durationMs` | 经 `AccessBridge` 结合当前屏幕实时宽高与旋转方向换算像素坐标并调 `dispatchGesture` |

### 1.3 场景模板库（Per-App 差异化语义，合流 B1–B8）

在 UI 的「预设市场 / 场景模板」中内置以下 8 套社区验证的高频 per-app 模板，用户可一键套用并按需微调：

| 模板编号 | 场景名称与差异化语义 | 默认手势映射示意 | 底层动作 | 分期 | 成熟度标注 |
|---|---|---|---|---|---|
| **B1** | **阅读器翻页**（微信读书 / KOReader / 静读天下 / 漫画等） | 音量+/音量− 或 EK 短按 → 上一页/下一页 | P1：`key_inject`（`PAGE_UP`/`PAGE_DOWN`）；P2：无障碍归一化纵向/横向 `a11y_gesture (swipe)` | P1 / P2 | 稳定（按键翻页 P1；滑动翻页 P2） |
| **B2** | **导航 / 地图缩放**（高德 / Google Maps 骑行车载场景） | 音量+/音量− → 地图放大/缩小 | P2：`a11y_click`（缩放按钮）或 `a11y_gesture (pinch)` | P2 | **`[实验级]`**（Google Maps Android 不响应键盘缩放，仅能走无障碍模拟手势/点击） |
| **B3** | **直播 / 录音 / 通话麦克风静音（PTT 按住说话）** | 长按 EK / 侧键 → 按住取消静音、松手恢复静音 | P1：`AudioManager.setMicrophoneMute`（需 `onLongPressUp` 透传还原）；P3：目标 App 内部 PTT 直调 | P1 / P3 | 稳定（系统级 Mic Mute P1）；App 内 PTT 为 P3 |
| **B4** | **相机内长按 = 连拍 / 录像启停** | 相机内短按 EK = 单拍快门；长按 EK = 持续按住快门 / 启停录像 | P1：`camera_shutter`（区分单拍 35ms `down+up` 与长按保持/录像 `repeatCount` 语义） | P1 | 稳定（需真机校准 Nothing Camera `down/up` 时序） |
| **B5** | **音乐 App 内切歌，非音乐 App 内调音量** | 音乐 App 前台（或息屏播放态）双击/长按音量± → 上/下一曲；其他场景回归原生音量 | P1：`media_transport (next/prev)` + 直接拦截 `KeyEvent`（**彻底根治 Tasker 音量到顶无变化失效的顽疾**） | P1 | 稳定 |
| **B6** | **游戏防误触（透传 / 屏蔽）** | 游戏前台时：音量± 还原 `system` 原生（不弹自定义动作）、EK 短按设为 `none` 防误触 | P1：`ActionProfile` 高优先级（`priority=300`）覆写为 `system` / `none` | P1 | 稳定 |
| **B7** | **微信 / IM 内长按 = 语音消息（按住说话）** | 微信聊天页长按 EK → 模拟按住“按住说话”按钮、松手发送 | P2：`a11y_gesture (long_press)`（将物理键 `DOWN..UP` 桥接为无障碍持续触摸） | P2 | **`[实验级]`**（微信无公开快捷键且为触摸按住语义，受聊天栏高度与版本影响） |
| **B8** | **前台应用变量导出（自动化桥接）** | 任意手势触发时广播 `${fg_pkg}` / `${fg_app_name}` / `${fg_activity}` | P1：`context_var_broadcast` | P1 | 稳定 |

### 1.4 防坑开关设计（升格为 P1 核心需求）

基于社区 22 用例与竞品踩坑实证，以下 6 项防坑能力升格为 **P1 必做基建**（在配置 JSON 顶层与 UI 设置页显式暴露）：

1. **全局 App 黑名单（`blacklistPackages`）**：
   - 命中黑名单包名（如银行/支付/高频游戏）时，**在 `ProfileResolver` 最前端（仅次于总开关）立即返回 `SYSTEM` 并 0ms 纯原生透传**，不跑任何手势状态机、不触碰无障碍。
   - UI 提供一键勾选常见银行/支付/游戏包的快捷入口。
2. **息屏 / 锁屏生效独立开关（`allowWhenScreenOff` / `allowWhenKeyguardLocked`）**：
   - 全局提供两个显式开关（默认 `allowWhenScreenOff=true`、`allowWhenKeyguardLocked=true`，充分利用 Xposed 息屏稳收键的天然优势），同时允许单个 `ActionProfile` 用 `screen` / `keyguard` 细粒度覆写（例如「锁屏仅允许手电筒与相机快门，禁止启动第三方支付 App」）。
3. **保留默认动作不吞键（`passthroughDefault`）**：
   - 每个 `ActionSpec` 增加可选布尔字段 `passthroughDefault`（默认 `false`）：开启后，KeyPilot 在后台执行自定义动作的同时，经 Bypass 通道放行原始按键事件，满足 Key Mapper *Do not override default action* 等“叠加而不吞键”场景。
4. **按键服务冲突检测（`ConflictDetector`）**：
   - 在 KeyPilot App 启动及「诊断」卡片中自动扫描系统已启用的 `AccessibilityService` 列表（读 `Settings.Secure.enabled_accessibility_services`）：
     - 若检测到 **Google Select to Speak**（2026-01 官方实锤事故：会导致音量键改调无障碍音量并使相机内音量键无法拍照）或其他第三方按键重映射无障碍服务（Key Mapper / Button Mapper / AutoInput），展示醒目黄色警示卡片并附一键跳转无障碍设置页。
     - 若用户开启了 P2 `AccessBridge` 且本机装有 GCam，在 UI 提示「启用任意无障碍服务可能导致 GCam 缩放按钮切换为滑杆」。
5. **快门注入三档回退（`shutterTier`）**：
   - `camera_shutter` 内置三档回退链：**Tier 1 系统键注入（P1，首选，零无障碍）→ Tier 2 无障碍按包名节点点按（P2）→ Tier 3 无障碍归一化坐标点按（P2）**，并按 `(机型, 相机包名)` 缓存用户实测可用档位；同时过滤外设蓝牙快门（检查 `InputDevice.isExternal()`，避免与 HID-over-GATT 蓝牙自拍杆抢音量键）。
6. **与 Nothing OS 5.1 官方 Essential Key 重映射的共存 / 让位策略（`officialRemapPolicy`，重点）**：
   - **背景**：Nothing 已官宣 OS 5.1 将开放官方 Essential Key 重映射，但大概率仅覆盖“单一全局动作”，无法提供 per-app 上下文分流（相机内=快门）与细粒度三手势；且社区已实证 Nothing 系统策略会直接消费单击事件。
   - **三档共存策略（配置字段 `officialRemapPolicy`）**：
     - `"takeover"`（默认）：KeyPilot 在 `NtEssentialKeyImpl.onRecord` 完整接管所有已绑定手势，屏蔽官方动作，确保零双触发。
     - **`"yield_single_click"`（官方单击让位模式，推荐 OS 5.1 共存档）**：
       - 在**未命中任何 per-app Profile**（即处于常规桌面/普通 App）时，若用户将官方重映射用于单击，KeyPilot 对 Essential Key **短按（单击）自动让位放行给原方法**，仅接管**长按 / 双按**以及**命中特定 per-app Profile（如相机内短按=快门）时的单击**！
       - 这样用户既能享受官方 OS 5.1 原生单击动作（低延迟、零延迟等待双按窗口时可配合仅绑定长按），又能叠加 KeyPilot 的“相机内短按变快门”和双/长按扩展。
     - `"yield_all"`：检测到官方重映射启用时，Essential Key 全手势让位原生（仅保留电源/音量± 自定义）。
   - **运行时自动探测**：`EssentialKeyHook` 在初始化时探测 `Settings.Secure` / `NtEssentialKeyImpl` 是否出现 OS 5.1 新增的官方重映射配置键；一旦检测到官方配置非默认且当前策略为 `"takeover"`，通过 `StatusProvider` 在 App 顶部展示温和提示卡片，引导用户一键切换为 `"yield_single_click"` 或继续由 KeyPilot 独占接管。

---

## 2. 配置 JSON 协议扩展（v2，100% 向后兼容 v1）

### 2.1 兼容性约定
- `version` 升级为 `2`；`KeyPilotConfig.fromJson()` 同时兼容 `version: 1`（无 `profiles` 字段时自动初始化为空列表 `[]`，全部行为与 v1 严格一致）。
- 传输路径与持久化文件保持不变：App 存 `files/keypilot_config.json` → 广播 `io.github.rtzhang.keypilot.CONFIG_UPDATE`（`config_json` extra）→ `system_server` `ConfigBridge` 原子落盘 `/data/system/keypilot.json`。
- **安全红线不变**：无论在全局 `keys` 还是在任何 `profiles[]` 中，只要 `powerLongPressExplicitlyBound == false`，`power.long` 永远强行返回 `SYSTEM`；硬件组合键护盾 `CHORD SHIELD` 在 Profile 解析之前即拦截放行。

### 2.2 完整 v2 JSON 协议示例（覆盖用户全部典型场景）

```json
{
  "version": 2,
  "masterEnabled": true,
  "showToast": true,
  "hapticFeedback": true,
  "preserveImeVoice": false,
  "longPressMs": 500,
  "doublePressWindowMs": 300,
  "powerLongPressExplicitlyBound": false,
  "allowWhenScreenOff": true,
  "allowWhenKeyguardLocked": true,
  "officialRemapPolicy": "takeover",
  "blacklistPackages": [
    "com.icbc",
    "com.chinamworld.main",
    "com.unionpay"
  ],
  "updatedAt": 1795500000000,
  "keys": {
    "essential": {
      "enabled": true,
      "gestures": {
        "short": { "type": "camera" },
        "long": { "type": "flashlight" },
        "double": {
          "type": "deep_link",
          "uri": "alipays://platformapi/startapp?saId=10000007",
          "package": "com.eg.android.AlipayGphone",
          "label": "支付宝扫一扫"
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
        "double": {
          "type": "media_transport",
          "command": "toggle"
        }
      }
    }
  },
  "profiles": [
    {
      "id": "prof_camera_shutter",
      "name": "相机内：Essential 变快门",
      "enabled": true,
      "priority": 200,
      "match": {
        "packages": [
          "com.nothing.camera",
          "com.google.android.GoogleCamera*",
          "net.sourceforge.opencamera"
        ],
        "excludePackages": [],
        "activityRegex": "",
        "keyguard": "any",
        "screen": "on_only"
      },
      "bindings": {
        "essential": {
          "short": {
            "type": "camera_shutter",
            "shutterTier": "auto",
            "shutterKey": "auto",
            "pulseMs": 35,
            "passthroughDefault": false
          },
          "double": {
            "type": "a11y_click",
            "viewIds": [
              "com.nothing.camera:id/switch_camera",
              "com.google.android.GoogleCamera:id/camera_switch_button"
            ],
            "contentDescs": ["切换相机", "Switch camera"],
            "label": "切换前后镜头"
          }
        }
      }
    },
    {
      "id": "prof_reader_page_turn",
      "name": "阅读器内：音量双击翻页",
      "enabled": true,
      "priority": 150,
      "match": {
        "packages": ["com.tencent.weread", "org.koreader.launcher"],
        "keyguard": "unlocked_only",
        "screen": "on_only"
      },
      "bindings": {
        "essential": {
          "short": {
            "type": "key_inject",
            "keyCode": 93,
            "pulseMs": 35,
            "label": "下一页 (PAGE_DOWN)"
          }
        }
      }
    },
    {
      "id": "prof_wechat_shortcut_demo",
      "name": "桌面：长按音量− 直达微信扫一扫",
      "enabled": true,
      "priority": 100,
      "match": {
        "packages": ["com.nothing.launcher"],
        "keyguard": "unlocked_only",
        "screen": "on_only"
      },
      "bindings": {
        "volume_down": {
          "long": {
            "type": "app_shortcut",
            "package": "com.tencent.mm",
            "shortcutId": "scan",
            "label": "微信扫一扫 (Shortcut)"
          }
        }
      }
    },
    {
      "id": "prof_game_bypass",
      "name": "游戏防误触：还原系统原生",
      "enabled": true,
      "priority": 300,
      "match": {
        "packages": ["com.miHoYo.Yuanshen", "com.tencent.tmgp.sgame"],
        "keyguard": "unlocked_only",
        "screen": "on_only"
      },
      "bindings": {
        "volume_up": { "double": { "type": "system" } },
        "volume_down": { "double": { "type": "system" } },
        "essential": { "short": { "type": "none" } }
      }
    }
  ]
}
```

> **语义走读**：在桌面/微信/浏览器中短按 EK → 未命中 `prof_camera_shutter` → 回退全局 `keys.essential.short = camera`（打开相机）；进入 Nothing Camera 后短按 EK → 命中 `prof_camera_shutter`（`priority=200`）→ 执行 `camera_shutter`（拍照）；同时在相机中长按 EK 未在 Profile 中覆写 → 自动回退全局 `flashlight`！

---

## 3. 系统架构与增量组件设计

### 3.1 v2 总体架构图（文字版）

```text
+======================================================================================+
|                        system_server 进程 (uid=1000, scope="system")                  |
|                                                                                      |
|  [ForegroundTracker]  <-- 钩 ATMS/TaskStackListener (onTaskFocusChanged/MovedToFront)|
|    • 维护无锁 volatile ContextSnapshot (topPkg, topActivity, focusedWinPkg,           |
|      isKeyguardLocked, isInteractive, isInMultiWindow, updatedAtMs)                  |
|                                                                                      |
|  [EssentialKeyHook / StandardKeysHook]                                               |
|    └─► GestureStateMachine (500ms / 300ms)                                           |
|          └─► [ProfileResolver]                                                       |
|                输入: (KeyId, GestureType, ContextSnapshot, KeyPilotConfig)           |
|                输出: ResolvedAction(ActionSpec, matchedProfileId | "global")         |
|                       │                                                              |
|                       ▼                                                              |
|               [ActionExecutor v2 (KeyPilot-Exec 后台线程)]                           |
|                 ├─ P1: camera_shutter / key_inject (Bypass InputManagerGlobal)       |
|                 ├─ P1: media_transport (MediaSessionManager.TransportControls)       |
|                 ├─ P1: deep_link / in_app_preset / launch_app / v1 全部 20 项        |
|                 ├─ P2: [ShortcutBridge] -> LocalServices(ShortcutServiceInternal)    |
|                 │        • 响应 UI 枚举请求: 全机 App Shortcuts 列表 -> StatusProvider|
|                 │        • 执行 app_shortcut: createShortcutIntents + startActivity  |
|                 └─ P2: [AccessBridge Dispatcher] -> 定向发指令至可选无障碍服务        |
+==========================================+===========================================+
                                           |
          +--------------------------------+-----------------------------------+
          | Config/Shortcut/Log IPC                                            | Optional A11y IPC (P2)
          v                                                                    v
+---------------------------------------------------+    +---------------------------------------+
|          KeyPilot UI App (MainActivity)           |    | [可选组件 P2] KeyPilotAccessibilitySvc|
|  • Tab 1: 全局默认 (v1 4x3 矩阵)                   |    |  (AccessBridge, 零常驻轮询开销)        |
|  • Tab 2: 应用场景 Profiles (列表 + 编辑器)        |    |  • 仅在收到定向指令时抓取一次          |
|  • 动作选择器 v2: 预设模板 / Shortcut 浏览器 /     |    |    getRootInActiveWindow()            |
|    Deep Link 编辑 / A11y 节点拾取器                |    |  • 执行 ACTION_CLICK / dispatchGesture|
+---------------------------------------------------+    +---------------------------------------+
```

### 3.2 前台检测模块：`ForegroundTracker`（钩子侧）
- **双通道零阻塞实现**：
  1. **主通道（事件驱动缓存）**：在 `ActivityTaskManagerService` 就绪后（`systemReady`），通过反射向 `ActivityTaskManagerService.registerTaskStackListener(ITaskStackListener)` 注册轻量监听器（或在 `ActivityTaskManagerService` / `RootWindowContainer` 钩 `onTaskFocusChanged` & `setResumedActivityUncheckLocked`），每次焦点切换立即原子替换 `volatile ContextSnapshot`。
  2. **辅助校验通道**：当收到按键时，同时读取 `KeyguardManager.isKeyguardLocked()` 与 `PowerManager.isInteractive()`（均为轻量快速调用）；若 `ContextSnapshot` 尚未初始化或疑似过期，才在后台线程懒刷新一次 `ActivityTaskManager.getTasks(1)`。
- **多窗口 / 悬浮窗 / 锁屏规则**：
  - **分屏**：`onTaskFocusChanged(taskId, true)` 锁定用户最近点击的活跃分屏窗格。
  - **悬浮窗 / 输入法**：监听任务栈而非普通 Window，天然免疫 IME（软键盘）、Toast、聊天悬浮气泡误切换前台包名。
  - **锁屏安全相机**：当 `isKeyguardLocked == true` 且顶层 Activity 为 `com.nothing.camera`（带 `SHOW_WHEN_LOCKED`），`ContextSnapshot.topPackage` 仍准确为 `com.nothing.camera`，`keyguard="any"` 的相机快门 Profile 照常工作！
- **拦截判定优化（关键细节！）**：
  - v1 中 `config.shouldInterceptKey(key)` 只看全局 `keys[key]` 是否全为 `system`。
  - v2 中改为 **`config.shouldInterceptKey(key, snapshot)`**：先查当前上下文 `snapshot` 下该键 3 个手势是否全为 `system`——若在某游戏 Profile 中将 `volume_up.double` 覆写为 `system` 且全局其余均为 `system`，则在该游戏内实现 **0ms 零拦截纯原生透传**！

### 3.3 是否需要独立无障碍服务组件（`AccessBridge`）？
**架构决策：需要，但必须严格「P2 可选插件化、与核心完全解耦」**：
- **为什么核心（P1）绝不依赖无障碍**：
  - 前台检测、快门按键注入、Deep Link、精准媒体控制、`ShortcutServiceInternal` 快捷方式启动全部可在 `system_server` 内零权限闭环完成，延迟 <15ms，且不受 Android 13–17 侧载受限设置（Restricted Settings）困扰。
- **为什么 P2 仍提供可选 `AccessBridge` (`KeyPilotAccessibilityService`)**：
  - 对于不响应快捷键、未暴露 Deep Link 且没有 App Shortcut 的长尾界面按钮（如特定相机的“切换微距/镜头”按钮、某 App 内“签到/收藏”图标），`AccessibilityService` 是不触碰目标 App 反 Xposed 风控前提下唯一可靠的 UI 自动化手段。
- **安全与轻量设计**：
  - 声明 `accessibilityEventTypes=""`（不监听任何高频 UI 事件流，仅 `canRetrieveWindowContent="true"` + `canPerformGestures="true"`），平时 CPU 占用 **0%**。
  - 通信协议：`system_server` 向 `io.github.rtzhang.keypilot` 发送带随机一次性 `sessionToken`（存于 `ConfigBridge` 与 App 同步）的显式定向广播/Service Intent，防止恶意第三方 App 伪造触发点击。
  - 未启用 `AccessBridge` 时，触发 `a11y_click` / `a11y_gesture` 优雅提示 Toast「请开启 KeyPilot 无障碍辅助服务」，不崩溃、不影响其他手势。

### 3.4 UI 增量设计（纯代码 Nothing 深色美学，文字线框草图）

#### A. 主页顶部增加分段切换器（全局默认 / 场景分流）
```text
┌───────────────────────────────────────────────┐
│ ● KEYPILOT                         [v2.0]     │
│ 模块状态：已连接 system_server · 4 个场景生效    │
│                                               │
│  [ 全局默认 (4×3) ]   [ 场景分流 Profiles (4) ] │
├───────────────────────────────────────────────┤
│  + 新建应用场景 Profile             [预设市场] │
│                                               │
│ ┌───────────────────────────────────────────┐ │
│ │ 相机内：Essential 变快门      [P200] [●━━] │ │
│ │ 目标: Nothing Camera, GCam*, Open Camera  │ │
│ │ 覆写: EK 短按→快门 · EK 双按→切换镜头       │ │
│ └───────────────────────────────────────────┘ │
│ ┌───────────────────────────────────────────┐ │
│ │ 游戏防误触：还原系统原生      [P300] [●━━] │ │
│ │ 目标: 原神, 王者荣耀                        │ │
│ │ 覆写: 音量±双按→系统原生 · EK短按→无动作     │ │
│ └───────────────────────────────────────────┘ │
└───────────────────────────────────────────────┘
```

#### B. Per-App Profile 编辑页
```text
┌───────────────────────────────────────────────┐
│ ← 编辑场景 Profile                     [保存] │
├───────────────────────────────────────────────┤
│ 场景名称： [ 相机内：Essential 变快门       ]  │
│ 优先级：   [ 200 ] (数值越大越优先)            │
│                                               │
│ ── 生效条件 (Context Matcher) ──              │
│ 目标应用：[+ 选择应用]                        │
│   [Nothing Camera ×] [Open Camera ×]          │
│ 高级匹配：[包名通配/排除/Activity正则 ▾]       │
│ 锁屏条件：(●) 不限  ( ) 仅解锁  ( ) 仅锁屏     │
│ 屏幕条件：( ) 不限  (●) 仅亮屏  ( ) 仅息屏     │
│                                               │
│ ── 覆写按键手势 (未设项自动继承全局) ──         │
│ ● Essential Key                               │
│   短按：相机快门 (auto · 35ms)               › │
│   长按：继承全局 [手电筒开关]                › │
│   双按：无障碍点击 [切换镜头]                › │
│ 电源键 / 音量+ / 音量−  [展开更多按键 ▾]       │
│                                               │
│ [ 删除此场景 ]                                 │
└───────────────────────────────────────────────┘
```

#### C. 动作选择器 v2（新增「应用内功能」分组）
- **分组 1：高频预设模板**（一键选：相机快门 / 支付宝扫一扫 / 支付宝付款码 / 微信扫一扫 / 音频快进15s / 音频后退15s）
- **分组 2：应用快捷方式浏览器（P2）**（打开时向 `system_server` `ShortcutBridge` 拉取全机 App 长按菜单快捷方式，按应用分组搜索选择，零手工填 ID）
- **分组 3：高级自动化**（注入按键码 / 精准媒体控制 / Deep Link URI / 无障碍点击节点 [含悬浮准星拾取] / 无障碍坐标手势）
- **分组 4：v1 基础动作全保留**（继承全局 / 跟随系统原生 / 无动作 / 启动应用 / 手电筒 / 截屏 / 相机 / …）

---

## 4. 三阶段分期切片（Delivery Roadmap，v1.1 更新）

### Phase 1（P1）：上下文分流引擎 + 6 项防坑开关 + 免无障碍动作闭环（核心价值 ~85%）
- **目标**：零无障碍权限实现「应用上下文分流 + 防坑护盾 + 相机快门 Tier 1 + 微信/支付宝深链内置包 + 精准媒体控制 + 按键翻页/前台变量广播」。
- **代码交付**：
  1. `config/`：`ContextMatcher`、`ActionProfile`、`KeyPilotConfig` v2 编解码（100% 兼容 v1 JSON，新增 `blacklistPackages`、`allowWhenScreenOff`、`allowWhenKeyguardLocked`、`officialRemapPolicy`、`passthroughDefault`）。
  2. `hook/ForegroundTracker.java`：`system_server` 侧 `TaskStackListener` + `KeyguardManager` + 外设输入源 (`isExternal()`) 实时上下文追踪。
  3. `hook/ProfileResolver.java`：黑名单最优先旁路 + OS 5.1 `officialRemapPolicy` 判定 + 优先级匹配 + 全局回退 + 电源长按/组合键红线守卫。
  4. `hook/ActionExecutor.java` 扩展：`camera_shutter`（Tier 1 系统键 + B4 长按连拍/录像）、`camera`（A2 指定相机包）、`partial_screenshot`（A4）、`ringer_cycle` 侧边 Toast（A8）、`expand_notifications/qs`（A10）、`key_inject`（B1 按键翻页）、`media_transport`（A5/B5 含 `simulateMediaButton`）、`deep_link`、`in_app_preset`（A6 微信/支付宝内置包）、`context_var_broadcast`（B8）、B3 系统级 Mic Mute。
  5. `ui/`：Profile 列表与编辑器、场景模板一键套用（B1/B3/B4/B5/B6/B8）、黑名单管理、OS 5.1 共存策略开关、`ConflictDetector`（Select to Speak / 第三方无障碍冲突检测警示卡）。
- **退出标准**：全部 P1 真机用例（含黑名单、OS 5.1 让位、阅读翻页/音乐切歌模板）通过，v1 所有用例零回归。

### Phase 2（P2）：全机快捷方式引擎（`ShortcutBridge`）+ 可选无障碍插件（`AccessBridge`）
- **目标**：一键直达任意 App 的长按菜单功能（无需默认桌面），并覆盖无公开入口的界面按钮与实验级手势模板。
- **代码交付**：
  1. `hook/ShortcutBridge.java`：`LocalServices.getService(ShortcutServiceInternal.class)` 反射适配器（A7：枚举全机静态/动态快捷方式、按 `(pkg, shortcutId)` 系统级直启，回落 Activity），通过 `StatusProvider` 回传给 UI。
  2. `a11y/KeyPilotAccessibilityService.java`（`AccessBridge`）：按需懒查询节点树（`a11y_click` + 快门 Tier 2）与归一化手势（`a11y_gesture` + 快门 Tier 3 + B1 归一化 swipe 翻页 + **B2 导航缩放 `[实验级]`** + **B7 微信长按说话 `[实验级]`**），带 `sessionToken` 鉴权与 Android 13–17 Restricted Settings 解锁引导页。
  3. `ui/`：App Shortcuts 可视化浏览器 + 悬浮准星节点/坐标拾取器 + GCam 无障碍 UI 变化提示。
- **退出标准**：无障碍关闭时 P1 零影响；无障碍开启后可精准点击相机切换镜头/第三方 App 内按钮；至少 2 个主流 App 长按快捷方式直启成功。

### Phase 3（P3）：进阶宿主直调与规则生态（可选远期）
- **目标**：针对极少数无 Shortcut、无 Deep Link、无快捷键且需要零 UI 闪烁触发的系统/开源 App，提供可选 `InAppHookBridge`（通过运行时签名探针 + 跨进程安全通道直调目标内部方法，如 B3 特定语音 App 内 PTT 挂钩），并支持 JSON Profile / 场景模板包导入、导出与社区分享。
- **安全边界**：明确标注“高风险/反 Xposed 检测应用（如微信/支付宝/银行类）禁止纳入宿主持钩白名单”，优先通过 P1/P2 系统层机制解决。

---

## 5. v2 测试矩阵草案（待真机联网后执行）

### 5.1 v2 新增核心用例（V2-TC-01 ~ V2-TC-10）

| 编号 | 场景与前置状态 | 触发操作 | 预期结果与量化判据 | 所属分期 |
|---|---|---|---|---|
| **V2-TC-01** | **桌面/计算器前台**（非相机 App） | 短按 EK（80ms） | 未命中相机 Profile → 回退全局绑定 → 打开 `com.nothing.camera`；日志含 `resolved=global -> camera` | P1 |
| **V2-TC-02** | **Nothing Camera 前台（拍照模式）** | **同一手势**：短按 EK（80ms） | 命中 `prof_camera_shutter` → 触发快门；`/sdcard/DCIM/Camera/` 照片数精确 `+1`；日志含 `resolved=prof_camera_shutter -> camera_shutter`；零音量条弹出、零重复触发 | P1 |
| **V2-TC-03** | **Nothing Camera 前台** | 长按 EK（700ms，Profile 未覆写） | 未在 Profile 声明 → 自动回退全局 → 切换手电筒（或相机占用时安全报错不崩 `system_server`） | P1 |
| **V2-TC-04** | **锁屏态打开安全相机**（`KeyguardLocked=true`） | 短按 EK（80ms） | `ForegroundTracker` 仍识别 `com.nothing.camera` 且 `keyguard="any"` 命中 → 快门成功拍照（照片 `+1`） | P1 |
| **V2-TC-05** | **任意前台直达应用内功能（Deep Link）** | 双按 EK | 启动 `alipays://platformapi/startapp?saId=10000007`（或本机已装测试 App Deep Link）直达目标内页而非首页 | P1 |
| **V2-TC-06** | **精准媒体控制（`media_transport`）** | 音量− 双按（后台音乐播放中） | `MediaSessionManager` 直接将活跃 `PlaybackState` 从 `PLAYING ↔ PAUSED` 切换，或快进 15s（`position` 增量 ~15000ms） | P1 |
| **V2-TC-07** | **多 Profile 优先级与防误触覆写** | 在“游戏防误触”目标 App 前台双按音量+ | 高优先级 Profile（`priority=300`）覆写为 `system` → 不触发截屏，表现纯原生音量响应 | P1 |
| **V2-TC-08** | **分屏多窗口上下文切换** | 分屏同时开 A（相机/阅读器）与 B（设置），先后点击 A 与 B 再按 EK | `ForegroundTracker` 精确跟随当前获焦窗格，点击 A 执行 A 的动作、点击 B 执行 B/全局动作 | P1 |
| **V2-TC-09** | **App Shortcut 枚举与直启** | UI 浏览快捷方式并绑定后触发 | `ShortcutBridge` 无需默认桌面即可列出全机快捷方式并成功拉起目标内部功能页 | P2 |
| **V2-TC-10** | **可选 `AccessBridge` 节点点击与未开启降级** | ①开启无障碍后在相机双按 EK；②关闭无障碍后再按 | ①成功切换前后摄像头；②无障碍关闭时优雅弹 Toast 提示，零异常、P1 功能全部不受影响 | P2 |
| **V2-TC-11** | **场景模板抽测：阅读翻页（B1）与音乐切歌（B5）** | ①阅读器前台短按 EK（`PAGE_DOWN`）；②将媒体音量调至最大（到顶）后，在音乐 App 前台双击音量+切歌 | ①阅读器成功向下翻页且不弹音量条；②音量已到顶时仍可靠触发 `media_transport (next)` 切至下一曲（验证根治 Tasker 音量到顶失效坑） | P1 |
| **V2-TC-12** | **Nothing OS 5.1 共存 / 让位策略验证** | 将 `officialRemapPolicy` 设为 `yield_single_click`：①桌面短按 EK；②桌面长按 EK；③进入相机后短按 EK | ①桌面短按放行原生/官方链（日志 `yielded to official`）；②桌面长按仍执行 KeyPilot 手电筒；③相机内短按仍命中 `prof_camera_shutter` 触发快门 | P1 |
| **V2-TC-13** | **保留默认动作不吞键 & 冲突检测** | ①配置 `passthroughDefault=true` 触发测试；②模拟开启 Select to Speak 后打开 KeyPilot 诊断页 | ①自定义动作与系统原生事件均触发且无递归死循环；②诊断页准确检出并弹出 Select to Speak 冲突警示 | P1 |

### 5.2 v2 必测回归项（V2-REG-01 ~ V2-REG-06）
- **V2-REG-01（v1 JSON 向后兼容）**：直接加载 v1 格式 `/data/system/keypilot.json`（无 `profiles` 字段）冷启动，全部 6 项 v1 绑定与热更新正常。
- **V2-REG-02（安全红线①）**：在 Profile 中尝试绑定 `power.long`（但 `powerLongPressExplicitlyBound=false`），长按电源键仍必出原生 6 项电源菜单。
- **V2-REG-03（安全红线②）**：在命中相机/游戏 Profile 的前台下，同时按下 `POWER+VOL_DOWN` 与 `POWER+VOL_UP`，组合键护盾仍 100% 放行原生截屏与电源菜单。
- **V2-REG-04（NothingTweaks 覆盖回归）**：任意 Profile 下电源双按仍优先执行 KeyPilot 绑定，零唤醒 Google Wallet。
- **V2-REG-05（高频切换压测）**：连续切前台 App + 触发按键 30 次，`system_server` PID 不变、无 `FATAL EXCEPTION` / `Watchdog` / `KeyPilot: [ERR]`。
- **V2-REG-06（全局 App 黑名单回归）**：将测试 App（如计算器/银行包名）加入 `blacklistPackages`，在该 App 前台按所有已绑定手势（EK/音量±/电源双按），验证 0ms 纯原生透传且 KeyPilot 零拦截日志。

---

## 6. 风险与未决问题清单

| 编号 | 风险 / 未决问题 | 影响范围 | 缓解方案 / 真机验证动作 |
|---|---|---|---|
| **Q1** | **Nothing Camera (`com.nothing.camera`) 对 `KEYCODE_CAMERA (27)` vs `KEYCODE_VOLUME_DOWN (25)` 的实际响应差异** | P1 `camera_shutter` 默认策略 | 下一轮真机实测 `input keyevent 27` 与 `25` 在 Nothing Camera 拍照/录像/锁屏相机下的表现；若 27 原生支持则优先用 27（彻底规避用户把音量键改为缩放的冲突！）。 |
| **Q2** | **Android 17 (`API 37`) 中 `ActivityTaskManagerService` / `ITaskStackListener` 精确注册签名** | P1 `ForegroundTracker` | 离线拉取本机 `services.jar` 反汇编确认 `registerTaskStackListener` 及 `TaskChangeNotificationController` 方法签名，辅以运行时反射探针与 `getTasks(1)` 兜底。 |
| **Q3** | **Android 17 `ShortcutServiceInternal` 方法签名演进**（`getShortcuts` / `createShortcutIntents` 参数个数在 Android 14–17 曾调整 `userId`/`callingUserId`） | P2 `ShortcutBridge` | 使用参数类型反射探针（按方法名 + 参数类型列表匹配），并保留 `LauncherApps` 系统反射备选路径。 |
| **Q4** | **相机占用闪光灯时在相机内长按 EK 触发手电筒** | 边界体验 | `CameraManager.setTorchMode` 在相机前台占用摄像头时会抛 `CameraAccessException(CAMERA_IN_USE)`；`ActionExecutor` 已有 `try/catch` 兜底，v2 应捕获该异常并友好提示「相机占用中，无法开启手电筒」。 |
| **Q5** | **微信 / 支付宝等超级 App 对 Deep Link 与 App Shortcut 的版本风控变化** | P1/P2 `in_app_preset` | 优先通过 `ShortcutBridge` 走官方声明的长按快捷方式（最稳定）；Deep Link 执行前做 `resolveActivity` 检测并允许用户自定义编辑 URI。 |
| **Q6** | **Android 15–17 侧载 APK 的无障碍“受限设置”阻断** | P2 `AccessBridge` | 保持 P1 100% 免无障碍；在 P2 引导页提供一键跳转 App Info 与 ADB/Root 解锁命令（`appops set io.github.rtzhang.keypilot ACCESS_RESTRICTED_SETTINGS allow`）说明。 |
| **Q7** | **Google Maps Android 不响应键盘缩放（社区实证）** | P2 模板 B2 | 将 B2 导航缩放明确标记为 **`[实验级]`**，仅通过 P2 `AccessBridge` 模拟双指缩放/按钮点击实现，不承诺键盘注入可用。 |
| **Q8** | **微信无公开快捷键且“按住说话”为持续触摸语义 + 反 Xposed 风控** | P2 模板 B7 / P3 | 将 B7 标记为 **`[实验级]`**，仅走 P2 无障碍持续按压兜底；严禁将微信/银行/支付类 App 纳入 P3 Xposed 宿主持钩作用域。 |
| **Q9** | **音量已到顶/见底时切歌失效（Tasker `%VOLM` 社区顽疾） & Spotify 忽略标准媒体键** | P1 模板 B5 / `media_transport` | KeyPilot 在 `PhoneWindowManager` 层直接拦截物理 `KeyEvent`（不依赖音量值变化），根治音量到顶失效；对 Spotify 等播放器提供 `simulateMediaButton` 兼容开关。 |
| **Q10** | **Select to Speak 抢占音量键快门（2026-01 Google 官方确认）& GCam 无障碍 UI 变化** | P1 `ConflictDetector` / P2 `AccessBridge` | P1 诊断页自动扫描并警示 Select to Speak 及第三方无障碍按键服务；启用 P2 `AccessBridge` 前提示对 GCam 缩放按钮 UI 的潜在影响。 |
| **Q11** | **蓝牙自拍杆 / 遥控器（HID-over-GATT 模拟音量键）与相机内音量重映射冲突** | P1 `StandardKeysHook` / `camera_shutter` | `ForegroundTracker` / `StandardKeysHook` 按 `InputDevice.isExternal()` 区分机身物理键与外设蓝牙输入，默认对外设蓝牙快门直通放行。 |
| **Q12** | **Nothing OS 5.1 官方 Essential Key 重映射上线后的抢键与接口变更风险** | P1 `EssentialKeyHook` / `officialRemapPolicy` | 预置 `takeover` / `yield_single_click` / `yield_all` 三档共存策略及运行时设置键探针，OS 5.1 推送后只需补充探针键名即可平滑衔接。 |
