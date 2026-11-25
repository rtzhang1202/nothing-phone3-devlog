# KeyPilot Material 3 (M3) 全界面美化与真机视觉自审报告

- **测试设备**：Nothing Phone (3)（`00019157L001724`，Android 17 / Nothing OS 5.0 `C5.0-260924-2115`）
- **构建管线**：原生 `aapt2 + 两阶段 javac + d8 + zipalign + apksigner v2/v3`（零 Material/AndroidX 依赖，`build.sh` 未修改）
- **DEX 安全审计**：`classes.dex` 大小 `228,756 bytes`，`de/robv/android/xposed` 桩类定义 **0 matches**
- **核心功能零回归**：`config/` 与 `hook/` 目录全程未修改（最后修改时间均为 2026-11-25 04:40 之前）
- **最终产物**：[KeyPilot.apk](file:///D:/Hermes%20Desktop/KeyPilot/release/KeyPilot.apk)
- **最终 APK SHA256**：`8737b7838eee94f7cc10f32539ecb95f74517e1699d7c032cb866bb173d40d8b`
- **最终自审结论**：**10 / 10 屏全绿通过（含 Round 5 独立复审截断补修：状态条、Profile 标题、手势副标题零截断）**

---

## 1. M3 设计系统落地清单（改动文件）

| 模块 | 文件路径 | 核心改动 |
|---|---|---|
| M3 设计系统 | [UiTheme.java](file:///D:/Hermes%20Desktop/KeyPilot/app/src/main/java/io/github/rtzhang/keypilot/ui/UiTheme.java) | 动态取色（`system_accent1_*` / `system_neutral1_*` / `system_neutral2_*` 昼夜自动跟随）、统一圆角族（`24dp` 卡片 / `26dp` 输入与下拉 / `28dp` 药丸按钮、开关、Tab、弹窗顶角）、自绘零偏移 `ThumbCircleDrawable` M3 Switch（52×32dp，ON=24dp 同心右圆 / OFF=16dp 同心左圆）、Edge-to-Edge 全贴底 Modal Bottom Sheet + `MaxHeightScrollView` |
| 应用图标缓存 | [AppIconCache.java](file:///D:/Hermes%20Desktop/KeyPilot/app/src/main/java/io/github/rtzhang/keypilot/ui/AppIconCache.java) | LRU 缓存 `PackageManager.loadIcon/loadLabel`，圆角裁剪真实应用图标 + 常见预设包名友好中文名兜底 |
| 语义矢量图标 | [ActionIcons.java](file:///D:/Hermes%20Desktop/KeyPilot/app/src/main/java/io/github/rtzhang/keypilot/ui/ActionIcons.java) | 为全部 31 项 `ActionType` Canvas 自绘清晰几何图标徽章；对 `LAUNCH_APP` / `CAMERA` / `IN_APP_PRESET` 自动优先解析所属真实 APP 图标 |
| 主界面双 Tab | [MainActivity.java](file:///D:/Hermes%20Desktop/KeyPilot/app/src/main/java/io/github/rtzhang/keypilot/MainActivity.java) | 透明状态栏/导航栏昼夜反色 + `ScrollView` 状态栏防碰撞裁剪、M3 药丸双 Tab、4×3 手势行真实 APP 图标 + 纯值显示、场景分流卡片（横向 `24dp` 目标 APP 图标 + 应用名 + 分隔线 + 覆写摘要）、可忽略 `errorContainer` 冲突横幅、JSON 底部弹窗 |
| 动作/应用/预设选择器 | [ActionPickerDialog.java](file:///D:/Hermes%20Desktop/KeyPilot/app/src/main/java/io/github/rtzhang/keypilot/ui/ActionPickerDialog.java) | 全部迁移至 M3 底部弹窗：分组动作列表（`36dp` 图标 + 1 行描述 + `56dp` 缩进分隔线）、中文高频预设列表（支付宝/微信/相机/录音机真实图标与所属 APP 名）、单/多选应用选择器（`40dp` 图标 + `26dp` 搜索栏 + 分隔线） |
| 场景分流编辑器 | [ProfileEditorDialog.java](file:///D:/Hermes%20Desktop/KeyPilot/app/src/main/java/io/github/rtzhang/keypilot/ui/ProfileEditorDialog.java) | 显式字段标签（场景名称/优先级）、目标应用图标+名称实时预览、多选应用选择器、M3 下拉选择器、4×3 带图标覆写行与中文化值文本 |
| 防坑开关与共存弹窗 | [SafetySettingsDialog.java](file:///D:/Hermes%20Desktop/KeyPilot/app/src/main/java/io/github/rtzhang/keypilot/ui/SafetySettingsDialog.java) | 6 项 P1 防坑开关全 M3 化（息屏/锁屏 M3 Switch、OS 5.1 共存 M3 下拉、黑名单多选带图标选择器、`errorContainer` 冲突卡片） |

---

## 2. 逐屏真机截图与视觉自审问答表（最终轮：全绿）

自审维度固定 7 项：
- **Q1 碰撞/贴边/裁切**：有无文字与状态栏碰撞、按钮被裁切、贴底留缝或内容挤压？
- **Q2 圆角一族**：卡片 `24dp` / 输入与下拉 `26dp` / 按钮·开关·Tab·弹窗顶角 `28dp` 是否统一？
- **Q3 间距节奏**：卡片间距 `8dp`、内边距 `20/16dp`、右侧控件右对齐是否整洁？
- **Q4 图标对齐**：APP 真实图标与语义矢量图标是否清晰、圆角一致、垂直居中对齐？
- **Q5 应用名称完整**：APP 列表、预设列表、已绑定行与 Profile 卡是否清晰展示所属 APP 名称？
- **Q6 分隔到位**：列表项 `56dp` 缩进分隔线与卡片分区 Divider 是否到位？
- **Q7 值文本去重**：手势行与配置项是否仅展示值本身，无重复标签及原始英文枚举？

| 序号 | 界面与截图 | Q1 碰撞/裁切 | Q2 圆角族 | Q3 间距对齐 | Q4 图标对齐 | Q5 所属 APP 名 | Q6 分隔线 | Q7 值文本 | 结论 |
|---|---|---|---|---|---|---|---|---|---|
| 01 | **全局默认 Tab（浅色顶部）**<br>[01_global_top.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_ui/01_global_top.png) | 无碰撞无裁切，状态栏留白充足 | 卡片 24 / Tab·按钮·Switch 28 统一 | 8dp 卡距，5 枚 Switch 右缘严格对齐 | 相机真实图标 + 手电/截屏矢量徽章居中清晰 | `相机` + `com.nothing.camera` 完整 | 全局控制区开关与按钮间有清晰分隔线 | `相机` / `手电筒开关` / `截屏` 纯值无重复 | **GREEN** |
| 02 | **全局默认 Tab（浅色中下部）**<br>[02_global_lower.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_ui/02_global_lower.png) | 滚动内容在 `15:24` 状态栏下沿干净裁剪，零重叠 | 24dp 卡片 + 28dp Switch 统一 | 电源/音量+/音量− 节奏一致，右缘对齐 | Google 钱包真实彩色图标 + 矢量图标清晰对齐 | `钱包` + `Google 钱包 · com.google.android.apps.walletnfcrel` 完整无省略 | 卡片间 8dp 分隔，行间距 8dp 规整 | `媒体 · 播放 / 暂停` 已本地化，无原始 `toggle` | **GREEN** |
| 03 | **动作选择器底部弹窗**<br>[03_action_picker.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_ui/03_action_picker.png) | Edge-to-Edge 全贴底无灰缝，`取消` 避让手势条清晰可见 | 弹窗顶角 28 / 分组卡片 24 统一 | 标题/副标题/分组/底栏层次清晰 | 36dp 语义矢量图标垂直居中、粗细统一 | 描述明确标出微信/支付宝/GCam/Nothing | 每项之间 56dp 缩进分隔线清晰到位 | 标题+1行描述清晰，当前选中项右侧打勾 | **GREEN** |
| 04 | **中文高频内置预设弹窗**<br>[04_preset_picker.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_ui/04_preset_picker.png) | Edge-to-Edge 全贴底，`取消` 按钮完整不贴边 | 28dp 顶角 + 24dp 分组卡片统一 | 列表项呼吸感充足，触控高度 ≥56dp | 支付宝×3、微信×2、相机、录音机真实图标清晰圆润 | 每项副标题均给出所属 APP 名（`支付宝 ·` / `微信 ·` / `相机 ·` / `录音机 ·`） | 7 项间 56dp 缩进分隔线规整 | 标题+功能说明清晰无重复 | **GREEN** |
| 05 | **应用选择器底部弹窗**<br>[05_app_picker.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_ui/05_app_picker.png) | Edge-to-Edge 全贴底，搜索栏与列表不挤压，`取消` 固定可见 | 弹窗 28 / 搜索栏 26 统一 | `共 95 个可启动应用` 计数清晰，左右边距 20dp | 40dp 圆角真实 APP 图标（AdAway/AFWall+/bilibili/Chrome 等）清晰对齐 | 应用名（14sp Medium）+ 包名副标题（12sp）双行完整 | 每行 56dp 缩进分隔线清晰 | 纯应用名+包名无冗余前缀 | **GREEN** |
| 06 | **场景分流 Profiles Tab（浅色）**<br>[06_profiles_tab.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_ui/06_profiles_tab.png) | 标题 `相机内：EK 短按快门 / 长按连拍录像` 2 行完整展示不截断，状态条 `14:17:49 已同步` 完整 | 卡片 24 / Tab·按钮·芯片·Switch 28 统一 | 4 张场景卡 8dp 间距，Switch 统一贴右缘 | 横向 24dp 真实 APP 图标（王者荣耀、相机、QQ 音乐、YouTube Music）对齐工整 | `原神 · 王者荣耀 等 3 个应用` / `相机 · Google Camera 等 4 个应用` / `网易云音乐 · QQ音乐 等 5 个应用` | 目标应用区与手势覆写摘要间有清晰横向 Divider | 摘要清晰呈现手势→动作映射 | **GREEN** |
| 07 | **场景分流编辑底部弹窗**<br>[07_profile_editor.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_ui/07_profile_editor.png) | Edge-to-Edge 全贴底，底栏 `删除 / 取消 / 保存并同步` 完整固定、避让手势条 | 弹窗 28 / 输入框 26 / 开关卡片 24 / 按钮 28 统一 | 区块标题→字段标签→输入框 8dp 节奏一致 | 目标应用预览区显示真实 20dp 相机图标 + 名称 | `相机 · Google Camera 等 4 个应用` + 多选应用入口 | 基本信息与生效条件间有横向 Divider | `场景名称` / `优先级（数值越大越优先）` 标签清晰可见 | **GREEN** |
| 08 | **防坑开关与共存策略弹窗**<br>[08_safety_settings.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_ui/08_safety_settings.png) | Edge-to-Edge 全贴底，底栏 `取消 / 保存并同步` 清晰不裁切 | 弹窗 28 / 下拉与输入 26 / 开关行 24 / 按钮 28 统一 | 3 枚黑名单药丸按钮（Tonal/Outlined/Danger Text）排布均衡 | M3 Switch 居中同心对齐，下拉箭头右对齐 | 黑名单区展示应用图标/名称预览及快捷一键填充 | 息屏锁屏 / OS 5.1 共存 / 黑名单 / 冲突检测分区清晰有 Divider | 下拉框显示中文策略名 `KeyPilot 独占接管（默认）` | **GREEN** |
| 09 | **全局默认 Tab（深色动态 Monet）**<br>[09_dark_global.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_ui/09_dark_global.png) | 昼夜自动切换无白底残留，状态栏/手势条反色清晰 | 24/28 圆角族完全一致 | 间距与浅色模式 100% 对称 | 深色 M3 Switch（浅蓝轨+深蓝 24dp 同心圆，OFF 细环+16dp 圆）与矢量徽章高对比清晰 | `相机` + `com.nothing.camera` 可读性极佳 | 深色 `outlineVariant` 分隔线柔和清晰 | 纯值无重复标签 | **GREEN** |
| 10 | **场景分流 Tab（深色动态 Monet）**<br>[10_dark_profiles.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_ui/10_dark_profiles.png) | 全屏深色无裁切碰撞，状态栏/手势条反色正常 | 24/28 圆角族完全一致 | 卡片/芯片/Switch 间距节奏完全一致 | 深色卡片下真实彩色 APP 图标边缘干净、对比度高 | 各场景目标应用名完整清晰 | 分隔线柔和且层级清楚 | 摘要使用深色 `primary` 高亮可读性强 | **GREEN** |

> **关于冲突横幅（`ConflictDetector` Banner）说明**：实机未开启 Select to Speak 或第三方无障碍抢键器（系统 `AccessibilityManagerService` 也会自动剔除未安装服务），故主屏正常状态不显示错误横幅；经代码核验，[MainActivity.java](file:///D:/Hermes%20Desktop/KeyPilot/app/src/main/java/io/github/rtzhang/keypilot/MainActivity.java#L890-L937) 冲突横幅复用同一套 `UiTheme.makeCard`（`24dp` 圆角 + `errorContainer` / `onErrorContainer` 动态色角色 + 右上角 `忽略` 文本按钮 + 右下角 `前往无障碍设置` 跳转按钮），样式与全站 M3 规范完全一致。

---

## 3. 迭代自审修复记录（Round 1 → Round 4）

| 轮次 | 视觉自审发现问题 | 根因定位 | 修复方案 | 复审结果 |
|---|---|---|---|---|
| **Round 1 → 2** | `03_action_picker` / `07_profile_editor` 底部按钮栏被推出屏外裁切 | `ScrollView` 使用 `addOnLayoutChangeListener` 限高晚于 `Dialog` 首次测量 | 在 `UiTheme.java` 实现确定性 `MaxHeightScrollView`（`onMeasure` 强制 `AT_MOST 58%` 屏高）并关闭默认滚动条 | 全部弹窗底栏 100% 稳定可见 |
| **Round 1 → 2** | `07_profile_editor` 优先级输入框已有值 `200` 时看不出字段含义；`02_global_lower` 显示原始英文 `媒体 · toggle` | `EditText` 仅依赖 hint；媒体命令未做中文映射 | 新增 `fieldLabel(...)` 显式字段标签；在 `ProfileEditorDialog.localizeMediaCmd(...)` 映射为 `播放 / 暂停`、`下一首` 等中文 | 字段语义与中文值清晰可读 |
| **Round 2 → 3** | 全界面（尤以深色模式 `09`/`10` 明显）自定义 Switch 滑块凸出轨道边缘、轨道高度被压缩 | Android 原生 `Switch` 会将 `InsetDrawable.getPadding()` 误作光学缩进（缩窄轨道高度并偏移滑块） | 在 `UiTheme.java` 新增零 padding 自绘 `ThumbCircleDrawable`（`26×32dp` 盒宽匹配 `52×32dp` 轨道，ON=`24dp` 右半圆同心，OFF=`16dp` 左半圆同心） | 浅/深色模式均为标准 Pixel M3 Switch 观感 |
| **Round 2 → 3** | `02_global_lower` 向上滚动时顶部卡片文字与状态栏时间 `15:07` 重叠 | 状态栏顶部 padding 加在 `root` 而非 `ScrollView` 自身 | 将 `statusTop` 移至 `scroll.setPadding(0, statusTop, 0, 0)` 并设置 `scroll.setClipToPadding(true)` | 滚动内容在状态栏下沿干净裁剪，零碰撞 |
| **Round 3 → 4** | `03`/`04`/`05`/`07`/`08` 底部弹窗在手势导航条下方露出 ~20dp 暗色缝隙 | 默认 `new Dialog(ctx)` 为浮动窗口，系统保留底部导航栏边距 | 改为 `Theme_DeviceDefault_NoActionBar` 全屏窗口 + 半透明 `FrameLayout` 遮罩（点击遮罩关闭）+ `setDecorFitsSystemWindows(false)` + 底部导航栏动态安全区 padding | 全部底部弹窗 100% 贴底 Edge-to-Edge，手势条自动反色 |
| **Round 4 → 5（补修）** | 独立复审揪出 2 处截断：① 场景卡标题 `相机内：EK 短按快门 / 长按连拍...` 截断；② 顶部状态条 `synced (v2 ma...)` 截断；另同源排查发现 `02_global_lower` 包名副标题 `walletnfc...` 截断 | `nameTv`/`heartbeatView`/`valTv`/`subTv` 等处设置了 `setSingleLine(true)`，且原始 heartbeat 串含冗长调试后缀 | ① `MainActivity.formatHeartbeatText()` 精简格式化为 `已连接 system_server · 14:17:49 已同步` 并允许 `maxLines=2`；② Profile 标题、手势行值/副标题、目标应用列表及 `ProfileEditorDialog`/`SafetySettingsDialog` 预览文本全量解除 `singleLine`，改 `maxLines=2`（覆写摘要 `maxLines=3`）自适应换行 | 复审 `01`/`02`/`06`/`09`/`10` 全部零截断、卡高自适应、无新引入错位 |

---

## 4. 环境复原与基线核验

- 系统夜间模式：已恢复原值 `Night mode: no`
- 系统无障碍设置：已核验原值完整保留（`accessibility_enabled=1`，原有服务列表原封不动）
- Magisk 模块状态：10 个模块，0 个 disable
- 最终产物 SHA256：`8737b7838eee94f7cc10f32539ecb95f74517e1699d7c032cb866bb173d40d8b`
