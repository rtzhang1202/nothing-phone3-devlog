# KeyPilot v2 Phase 1（P1）真机部署与 V2 矩阵实测报告

> **实测时间**：2026-11-25 04:34 – 04:44 (CST)  
> **目标设备**：Nothing Phone (3)（`A024 / Metroid`，Serial `00019157L001724`）  
> **系统基线**：Android 17 (API 37) / Nothing OS 5.0 (`C5.0-260924-2115`)，Magisk + Vector/LSPosed (`zygisk_vector` v2.2)  
> **最终产物**：[KeyPilot.apk](file:///D:/Hermes%20Desktop/KeyPilot/release/KeyPilot.apk)（SHA256：`f960b6124588ccc103c2733d88eeee483911c2644297d9405c2d9ba1e417f17e`）  
> **总评**：**V2-TC 6/6 全通过 · V2-REG 5/5 全通过 · 迭代修复 1 轮 · `system_server` 零崩溃**

---

## 1. 部署与双 Tab UI / 钩子就绪核验

| 核验项 | 实测结果 | 存证 |
|---|---|---|
| 覆盖安装与重启激活 | `adb -s 00019157L001724 install -r release/KeyPilot.apk` 成功；重启后 `system_server`（PID `2757`）加载 `v2-P1`，并兼容冷启动读取旧配置及 v2 `profiles=4/4` | [v2_tc_06_iter1_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_06_iter1_logcat.txt) |
| `ForegroundTracker` 三通道就绪 | `DisplayContent#setFocusedApp`、`TaskChangeNotificationController#notifyTaskMovedToFront`、`ITaskStackListener` 动态代理全部挂载成功（`3 channels active`） | 同上 |
| 双 Tab UI 渲染核验 | 「全局默认 (4×3)」与「场景分流 Profiles (4)」双 Tab、优先级卡片（P300/P200/P160/P150）、防坑开关入口与实时绿色心跳渲染清晰 | [01_ui_tab_global.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/01_ui_tab_global.png)、[02_ui_tab_profiles.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/02_ui_tab_profiles.png)、[03_ui_tab_profiles_populated.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/03_ui_tab_profiles_populated.png) |

---

## 2. V2 核心用例测试矩阵（V2-TC-01 ~ V2-TC-06：**6 / 6 PASS**）

| 编号 | 测试场景与判据 | 实测现象与核心日志 | 结论 | 证据文件 |
|---|---|---|---|---|
| **V2-TC-01** | **非相机 App（桌面）短按 EK → 打开相机**（Profile A：`packages=["*"]`, `exclude=["com.nothing.camera", ...]`） | 前台由 `SearchLauncher` 切至 `com.nothing.camera/.activity.CameraActivity`，弹 Toast `KeyPilot: 打开相机`；日志命中 `resolved=profile:prof_non_camera_open` | **PASS** | [v2_tc_01_before.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_01_before.png) / [v2_tc_01_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_01_after.png) / [v2_tc_01_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_01_logcat.txt) |
| **V2-TC-02** | **相机内（Nothing Camera）同一手势短按 EK → 快门拍照**（Profile B）；记录生效快门档位 | 首轮实证 `VOLUME_DOWN (25)` 与 `KEYCODE_CAMERA (27)` 均在 Nothing Camera 触发快门（3s 夜景倒计时拍照，`35→36→37`）；Iter 1 将 `com.nothing.camera` 升级为 `auto` 首选 **`KEYCODE_CAMERA (27)`** 后复验，首次即成片 **`38 → 39 (+1)`**，且零音量键冲突 | **PASS**（Tier 1 `KEYCODE_CAMERA=27`） | [v2_tc_02_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_02_after.png) / [v2_tc_02_iter1_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_02_iter1_after.png) / [v2_tc_02_iter1_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_02_iter1_logcat.txt) |
| **V2-TC-03** | **直达应用内功能**：双按 EK 触发内置包 `alipay_scan`（`alipays://platformapi/startapp?saId=10000007`） | 日志命中 `EXEC [essential.double] -> 内置功能：支付宝：扫一扫`，成功经 Deep Link 拉起 `com.eg.android.AlipayGphone`（因测试机未登录支付宝，支付宝自动路由至登录引导页） | **PASS** | [v2_tc_03_before.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_03_before.png) / [v2_tc_03_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_03_after.png) / [v2_tc_03_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_03_logcat.txt) |
| **V2-TC-04** | **场景模板 B5（音乐 App 内切歌 + 音量到顶不失效）**：QQ 音乐前台将媒体音量拉满至 15 后双击音量+ | 命中 `resolved=profile:prof_music_track_skip -> 精准媒体：next [模拟键]`，注入 `KEYCODE_MEDIA_NEXT (87)`，屏幕弹 Toast `KeyPilot: 媒体：下一曲`（证明彻底根治 Tasker 音量到顶失效坑） | **PASS** | [v2_tc_04_before.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_04_before.png) / [v2_tc_04_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_04_after.png) / [v2_tc_04_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_04_logcat.txt) |
| **V2-TC-05** | **Nothing OS 5.1 共存策略（`yield_single_click` 不重启热更新）**：桌面单击让位官方/原生，相机内单击仍走快门 | 热更新 `officialRemapPolicy=yield_single_click`：①桌面短按日志 `official_yield_single` 并拉起原生 `FocusActivity`；②进入 Nothing Camera 短按仍命中 `prof_camera_shutter` 成功拍照 **`39 → 40 (+1)`** | **PASS** | [v2_tc_05_launcher_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_05_launcher_after.png) / [v2_tc_05_camera_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_05_camera_after.png) / [v2_tc_05_iter1_camera_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_05_iter1_camera_after.png) / [v2_tc_05_iter1_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_05_iter1_logcat.txt) |
| **V2-TC-06** | **分层继承 + 相机占用闪光灯优雅兜底**：相机内长按 EK（未在 Profile 覆写）回退全局手电筒 | 首轮捕获反射包装异常 → **Iter 1 修复解包 `getCause()` 后复验**：命中 `resolved=global -> 手电筒开关`，日志平稳记录 `flashlight: camera busy (MAX_CAMERAS_IN_USE), skipping torch toggle`（**零 `[ERR]`**），界面弹出友好 Toast `KeyPilot: 相机占用中，无法切换手电筒` | **PASS**（Iter 1 修复通过） | [v2_tc_06_iter1_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_06_iter1_after.png) / [v2_tc_06_iter1_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_tc_06_iter1_logcat.txt) |

---

## 3. V2 必测回归与安全红线矩阵（V2-REG-01 ~ V2-REG-05：**5 / 5 PASS**）

| 回归项 | 验证目标 | 实测现象与核心日志 | 结论 | 证据文件 |
|---|---|---|---|---|
| **V2-REG-01** | **黑名单 0ms 旁路**：`com.android.settings` 加入 `blacklistPackages`，在设置前台短按 EK | 0ms 旁路零 `KeyPilot: EXEC`，直接唤起原生 `com.nothing.ntessentialrecorder/.FocusActivity` 截屏框 | **PASS** | [v2_reg_01_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_reg_01_after.png) / [v2_reg_01_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_reg_01_logcat.txt) |
| **V2-REG-02** | **保留默认动作不吞键（`passthroughDefault=true`）**：`volume_down.short = none + 不吞键` | 日志同时命中 `EXEC [volume_down.short] -> 无动作 (屏蔽) [不吞键]` 与 `passthroughDefault=true -> also replaying native`，并正常弹出左侧原生 `VolumeDialog`，零递归 | **PASS** | [v2_reg_02_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_reg_02_after.png) / [v2_reg_02_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_reg_02_logcat.txt) |
| **V2-REG-03** | **关闭 Profiles 回退 v1 全局矩阵 & 热更新生效** | 广播关闭全部 Profiles（不重启），桌面短按 EK 立即回退全局 `resolved=global`，启动 `com.google.android.calculator` 并弹 Toast `KeyPilot: 启动 计算器` | **PASS** | [v2_reg_03_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_reg_03_after.png) / [v2_reg_03_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_reg_03_logcat.txt) |
| **V2-REG-04** | **安全红线①：电源键长按必出原生电源菜单** | 满 500ms 弹出 6 项原生 `GlobalActionsDialogLite` | **PASS** | [v2_reg_04_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_reg_04_after.png) / [v2_reg_04_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_reg_04_logcat.txt) |
| **V2-REG-05** | **安全红线②：组合键 `POWER+VOL_DOWN`（截屏）与 `POWER+VOL_UP`（电源菜单）** | 双组合键均命中 `CHORD SHIELD`，截图数 `21 → 22 (+1)`，`GlobalActionsDialogLite` 正常弹出 | **PASS** | [v2_reg_05_screenshot_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_reg_05_screenshot_after.png) / [v2_reg_05_powermenu_after.png](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_reg_05_powermenu_after.png) / [v2_reg_05_ss_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_reg_05_ss_logcat.txt) / [v2_reg_05_pm_logcat.txt](file:///D:/Hermes%20Desktop/KeyPilot/verify_v2/v2_reg_05_pm_logcat.txt) |

---

## 4. 迭代修复记录（共 1 轮）

1. **Iteration 1 — `ActionExecutor.java` 两处根因级优化与修复**：
   - **修复 `MAX_CAMERAS_IN_USE` 反射异常未解包**：在相机前台尝试开手电筒时，`Method.invoke()` 将 `CameraAccessException(MAX_CAMERAS_IN_USE)` 包装在 `InvocationTargetException` 中，导致首轮走入 `[ERR]` 异常日志。修复为解包 `t.getCause()` 识别 `IN_USE`/`insufficient resources`，平稳输出信息日志并弹友好 Toast「相机占用中，无法切换手电筒」。
   - **优化 Nothing Camera 默认快门策略（实证定案 Q1）**：真机实证 `com.nothing.camera` 原生支持 `KEYCODE_CAMERA (27)` 拍照且完全不与音量键缩放设置冲突；已将 `auto` 策略对 `com.nothing.camera` 升级为首选 `KEYCODE_CAMERA (27)`（`GoogleCamera*` 仍保持 Bypass `VOLUME_DOWN`）。

---

## 5. 终检设备状态核验

| 核验项 | 最终实测值 | 状态 |
|---|---|---|
| `/data/system/keypilot.json` 目标态 | `version=2`，`4 profiles`（V2 相机快门预设） | ✅ |
| Clash / `tun0` | `26: tun0: <POINTOPOINT,UP,LOWER_UP> ... inet 172.19.0.1/30` | ✅ |
| Magisk 模块状态（root 核验） | 10 个模块全部启用，**`disable` 标记数 = `0`** | ✅ |
| `system_server` 稳定性 | PID `2757` 全程无重启、无 `FATAL EXCEPTION` / `Watchdog` | ✅ |
| 终态前台 | `com.nothing.launcher/com.android.searchlauncher.SearchLauncher` | ✅ |
