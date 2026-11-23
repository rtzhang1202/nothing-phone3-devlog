# 全屏 AOD 篇：全屏锁屏式息屏显示 LSPosed 模块 🔋✨

> Nothing Phone (3) 的「全屏 AOD」——息屏时瞬间点亮**整张锁屏壁纸（息屏亮度）**，时钟与小组件随后优雅浮现，告别原生 AOD 的小表盘。Xposed/LSPosed 模块实现，含亮度映射、定时转普通（带 GUI 定时规则）、无闪屏 doze 等一整套工程化打磨。

## ✨ 功能特性

| 功能 | 说明 |
|---|---|
| 全屏锁屏式 AOD | 息屏瞬间以息屏亮度展示整张锁屏壁纸，时钟/小组件/通知按原生动画淡入 |
| 纯净入场 | 单一入场策略（无模式探测）：壁纸秒亮 + 元件释放，已消除黑闪/白闪（亮帧 4→0-1） |
| 亮度映射 | 硬件亮度映射可调（压暗比例/上下界 1%–200%），解决 LTPS 低亮度 PWM 余光闪烁 |
| 定时转普通 AOD | 全屏 AOD 可按时长（10s–30min，秒/分钟输入）自动转回原生 AOD 省电 |
| **定时规则 GUI** | 时段规则可视化编辑：点击时间块弹**安卓原生时间选择器**，每条规则独立时长/可「不转」，支持跨午夜（22:00→次日 9:00） |
| 无闪屏 doze（根治） | Xposed 翻译 doze 屏幕状态请求（DOZE→ON），面板保持正常驱动 + 仅降亮度，根治 LTPS 低频驱动闪烁 |
| 组件唤醒复原 | 从普通 AOD 唤醒时小组件/时钟可见性完整复原，壁纸压暗自动归零（修复桌面/锁屏壁纸消失） |
| M3 设计系统 | 设置界面 Material 3 动态取色（壁纸取色）、全 APP 统一控件语言 |

## 📱 环境要求

- Nothing Phone (3)（A024 / Metroid，Nothing OS 5 / **Android 17**）
- Bootloader 已解锁 + Magisk root（见 [ROOT 篇](../root/README.md)）
- **LSPosed**（Zygisk 版）
- 模块为 Xposed 实现，理论上任何 Android 12+ 设备可移植（显示钩子路径按 AOSP 结构实现）

## 🚀 安装步骤

1. **下载 APK**：Releases 中的 `FullscreenAOD.apk`（或自行构建，见下文）
2. **安装**：`adb install FullscreenAOD.apk` 或文件管理器安装
3. **LSPosed 启用**：LSPosed 管理器 → 模块 → 「Fullscreen AOD」→ 启用，**作用域勾选**：
   - `android`（系统框架，负责亮度映射与 doze 屏幕状态翻译）
   - `com.android.systemui`（负责 AOD 渲染与组件动画）
4. **完整重启**（重要！LSPosed 对更新后的模块要求完整重启，软重启 `stop; start` 可能不加载）
5. 锁屏即可看到全屏 AOD 效果

> ⚠️ **模块更新后必须完整重启**，否则新版本钩子不生效（表现为功能失灵、日志无 `FullAOD` 输出）。

## 🎛️ 设置界面使用教程

打开「全屏 AOD」APP（全部设置即时热生效，约 1.5 秒）：

| 区块 | 项目 | 用法 |
|---|---|---|
| 总开关 | 启用全屏 AOD | 模块总闸 |
| 预设 | 夜间 / 标准 / 明亮 | 一键套用整组参数，改动任一滑杆自动转为自定义 |
| 亮度 | 亮度比例 / 最低映射 / 最高映射 | 硬件亮度映射三件套；**低亮度 PWM 闪烁**时调高映射上下界（最低可到 100%、最高可超 100%，最高恒大于最低） |
| 压暗 | 前景压暗 / 壁纸压暗 | 元件与壁纸的压暗程度 |
| 元素 | 自适应环境调光 / 显示小组件 / 通知 | AOD 显示内容开关 |
| 全屏 AOD 时长 | 数值 + 秒/分钟切换 | 超时自动转普通 AOD；**0 = 一直全屏** |
| **定时规则** | ＋添加时段规则 | 每条规则三要素：**起止时间**（点击弹安卓原生时间选择器）、**超时时长**（数值+秒/分钟）、**不转普通 AOD** 开关 |
| 亮度上限保护 | — | 2160Hz PWM 兼容的映射钳制 |

### 定时规则语义

- 规则**优先于**默认时长；未被规则覆盖的时段走默认时长
- **跨午夜自动识别**：`22:00 → 09:00` 即当晚到次晨
- 起止相同 = 全天生效
- 示例：夜间省电配置 = 规则 `22:00→09:00` 超时 10 秒 + 默认时长 100 秒；或规则勾选「不转普通」让某时段一直全屏

## 🔧 从源码构建

```bash
cd fullscreen-aod
# 1) 准备签名密钥（一次性）
keytool -genkeypair -keystore keystore/release.jks -alias nothingfullaod \
        -keyalg RSA -keysize 2048 -validity 10000
# 2) 构建（需 JDK 17+ 与 Android SDK，见下）
python build_p1.py
# 产物：release/FullscreenAOD.apk
```

构建依赖：Android SDK（`build-tools/37.0.0`、`platforms/android-37`）、JDK（`javac`/`java` 在 PATH 或 Android Studio JBR）、Python 3。脚本自动调用 aapt2 → javac → d8 → zipalign → apksigner；路径可用环境变量覆盖（`FULLAOD_KEYSTORE` 等）。

## 🧠 工作原理（简）

- **SystemUI 钩子**（`SystemUiHook`）：监听 `KeyguardTransitionRepository` 入场信号做壁纸预热（消除亮闪）；DozeMachine 状态机驱动元件释放动画；超时驱动 KGRV/时钟面切换原生 AOD 呈现；唤醒复原小组件与壁纸压暗
- **框架钩子**（`FrameworkBrightnessHook`，system_server）：doze 亮度映射（比例+上下界钳制）；**doze 屏幕状态翻译**（`DisplayPowerController.requestPowerState` 中 `dozeScreenState: DOZE→ON`）——面板保持 60–120Hz 正常驱动，仅应用息屏亮度，从机理上根治 LTPS 低频驱动闪烁（详见 [docs/闪烁根治方案.md](docs/闪烁根治方案.md)）
- **配置**：全部走 `Settings.Global`（`fullaod_*` 键），1.5s TTL 热生效

## 🩺 故障排查

| 症状 | 处理 |
|---|---|
| 功能失灵 / 日志无 `FullAOD` | 模块更新后未完整重启 → 重启手机 |
| 转普通 AOD 后全黑只能指纹 | 检查版本（v1.0 已修复：可见性钉扎不带 alpha 钉扎） |
| 唤醒后小组件只剩空隙 | v1.0 已修复（`restoreWidgetsAtWake` 双保险复原） |
| 桌面/锁屏壁纸消失 | v1.0 已修复（超时全黑后唤醒压暗归零 + 超时旗自愈） |
| 息屏闪烁 | v1.0 已根治（doze 屏幕状态翻译）；残余请调高亮度映射上下界 |
| 抓日志 | `adb logcat -s FullAOD`（关键字：`Entrance:` `Full-AOD duration:` `timed out` `Doze screen request`） |

## 📂 目录结构

```
fullscreen-aod/
├── README.md              ← 本文（使用教程）
├── build_p1.py            ← 构建管线（aapt2/javac/d8/apksigner）
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/            ← xposed_init / xposed_scope
│   ├── java/io/github/rtzhang/nothingfullaod/
│   │   ├── MainHook.java                 ← Xposed 入口
│   │   ├── SystemUiHook.java             ← SystemUI 侧（AOD 渲染/动画/超时/复原）
│   │   ├── FrameworkBrightnessHook.java  ← system_server 侧（亮度映射/doze 状态翻译）
│   │   ├── FullAodConfig.java            ← Settings.Global 配置中枢
│   │   └── MainActivity.java             ← M3 设置界面（定时规则 GUI）
│   └── res/
├── vendor/XposedBridgeApi-82.jar
├── scripts/gen_icons.py
└── docs/闪烁根治方案.md   ← 闪烁机理研究与三档根治方案全记录
```

## ⚖️ 免责声明

模块修改系统显示电源行为，功耗较原生 AOD 偏高（定时转普通可缓解）。使用风险自负；请勿用于商业分发。

---

*Nothing Phone (3) 开发日志 · 全屏 AOD 篇 · v1.0（2026-11）*
