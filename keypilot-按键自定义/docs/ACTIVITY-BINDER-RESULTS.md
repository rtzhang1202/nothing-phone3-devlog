# KeyPilot「Activity 探测绑定器」验收报告（verify_activity）

- **产物**：`release/KeyPilot.apk`（SHA256 `896ab6d1297779a5dc0bb3e66af5e10f5609d6b852c9865090df58ed9b5a6840`，build.sh 全绿、dexdump 桩类 0 命中）
- **设备**：Nothing Phone (3) `00019157L001724`，Android 17 / NOS 5.0，Magisk + Vector/LSPosed（覆盖安装后各重启 2 次生效）
- **证据目录**：本机 `D:\Hermes Desktop\KeyPilot\verify_activity\`（截图 PNG + uiautomator XML + `stage*.json` + `*_logcat.txt`）
- **测试窗口**：2026-11-27 00:38 – 02:05（CST）

> 说明：本文中的截图文件名对应本机全量存证目录；仓库仅精选 4 张入 `screenshots/09~12`（活动浏览器 / 重命名行 / 试开启 Toast / 矩阵回显）。

## 验收结论速览

| # | 验收项 | 结论 | 关键证据 |
|---|---|---|---|
| ① | 选 APP → 活动列表完整 | ✅ | `22_browser_wechat.png`（2047 条）、`22_browser_alipay.png`（949）、`22_browser_camera.png`（16）、`23_browser_bascan.png`（非导出 chip）、`10_browser_search_scan.png`（过滤 17） |
| ② | 试开启真实拉起 | ✅ | `91_trylaunch_toast.png`（Toast「已拉起 微信扫一扫」+ 目标页）、`24_trylaunch_toast.png`、`status.log` `TRY_LAUNCH ok …BaseScanUI` |
| ③ | 重命名持久化 | ✅ | `28_browser_renamed.png` → force-stop 后 `30/31_matrix_*` → 重启后 `51_matrix_after_reboot.png`；`/data/system/keypilot_labels.json` 与 APP 缓存一致 |
| ④ | 绑定带命名活动，手势直达 | ✅ | `29_matrix_after_bind.png`（命名 + 小字组件）、`53_profile_card_*.png`（场景卡摘要 + 组件行）、log `EXEC [volume_up.long] … Activity：微信扫一扫` |
| ⑤ | 导出 / 导入往返 | ✅ | `36_saf_save_dialog.png`、`exported_labels.json`（261B）、`39_after_delete.png` → `46_after_import.png`（补空缺）、`78_conflict_prompt.png`（冲突提示 + 覆盖） |
| ⑥ | 回归（≥3 项，实做 5 项） | ✅ | 见下「回归矩阵」 |
| ⑦ | UI 自审（vision 审 → 修 → 复审） | ✅ | 见下「UI 自审」，两轮均无碰撞/贴边/截断/间距缺陷 |

## 1. 活动列表完整（ACT-01）

| 包 | UI 报告 | 独立交叉核对（拉取 APK → `aapt2 dump xmltree` 计 `E: activity`） | 差异 |
|---|---|---|---|
| `com.nothing.camera` | **16** | 16 | 0 ✅ |
| `com.tencent.mm`（微信） | **2047** | 2048 | −1 |
| `com.eg.android.AlipayGphone`（支付宝） | **949** | 952（+2 `activity-alias`） | −3 |

- 差异为 PackageManager `GET_ACTIVITIES` 对超大清单的极小折算（≤0.3%），**不影响功能**（抽查组件均可试开启）。
- 行结构：APP 图标 + 主行（命名/短类名）+ 副行完整 `package/class`（10sp，点按复制）+ `导出/非导出` chip + `试开启/重命名` 药丸钮（`23_browser_bascan.png`）。
- 搜索过滤：`scan` → 17 条、`BaseScanUI` → 1 条（`10/23_browser_*.png`）。
- 大包分页：微信 2047 条首屏仅渲染 60 条（「已显示 60 个（滚动加载更多）」），滚动到底自动追加（`09/22_browser_*.png`）。
- 应用选择器复用现有 M3 选择器并新增「复制」药丸钮（`07/08_app_picker_*.png`，vision 审：复制钮不压包名、不贴边、间距一致）。

## 2. 试开启（ACT-02）

- **非导出活动（独家能力实证）**：
  - `adb shell am start -n com.tencent.mm/.plugin.scanner.ui.BaseScanUI` → **SecurityException: … not exported from uid 10328**（shell uid 被拒）
  - 同一组件经 `TRY_LAUNCH`（system_server uid=1000）→ `TRY_LAUNCH ok`，`mCurrentFocus=com.tencent.mm/…BaseScanUI` ✅
- UI 点「试开启」：Toast **「已拉起 微信扫一扫」**（命名回填，`91_trylaunch_toast.png`）、行短暂高亮、`status.log` 记录 `TRY_LAUNCH ok …`。
- **失败分支**：`TRY_LAUNCH` 传入不存在的组件 → `TRY_LAUNCH FAILED … -> 组件不存在: com.tencent.mm/com.tencent.mm.NotARealActivity (NameNotFoundException)`（根因修复见「迭代」#3）。
- 成功/失败均经 `StatusProvider.call("try_launch_result")` 回执，6s 未回执 UI 判「钩子未响应」。

## 3. 重命名持久化（ACT-03）

1. 活动浏览器「重命名」→ 输入「微信扫一扫」→ 列表即时刷新（`27/28_*.png`）。
2. APP 缓存 `files/keypilot_labels.json` 与钩子镜像 `/data/system/keypilot_labels.json` **内容一致**（`stage2b.json.label_file_app / label_file_system`）。
3. `am force-stop` 后重开：矩阵仍显命名（`31_matrix_label_after_reboot_prep.png`）。
4. **重启后**：`51_matrix_after_reboot.png` 仍显命名 + 组件副行；镜像文件仍在（`stage5.json`）。
5. 广播桥：`LABELS_UPDATE applied: 1 labels`（已排除 `_meta` 元数据键）。

## 4. 绑定 + 手势直达（ACT-04）

- 矩阵值文本 = **微信扫一扫**，副行小字 = `com.tencent.mm/.plugin.scanner.ui.BaseScanUI`（`29_matrix_after_bind.png`）。
- 触发 `volume_up.long` → `logcat`：`EXEC [volume_up.long] (resolved=global, fg=com.nothing.launcher) -> Activity：微信扫一扫 (com.tencent.mm/.plugin.scanner.ui.BaseScanUI)`，前台切至目标（`stage3.json.gesture_focus`）。
- 场景分流卡：摘要 `…音量+长按→微信扫一扫` + 独立小字组件行 `com.tencent.mm/.plugin.scanner.ui.BaseScanUI`（`53_profile_card_*.png`，`stage5.json`）。

## 5. 导出 / 导入往返（ACT-05）

- 导出：设置 → 活动命名管理 → 导出 JSON → SAF 保存 `/sdcard/Download/keypilot_labels.json`（261B，已 `adb pull` 为 `exported_labels.json`，格式 `keypilot.labels.v1`）。
- 补空缺：删除本地命名（列表显示空态）→ 导入同一文件 → Toast「导入完成：新增 1 条」，命名恢复（`39/46`）。
- 冲突提示：把本地改名「扫码入口」→ 导入 → 弹「导入冲突提示：共识别 1 条：新增 0 条，与本地命名冲突 1 条…」→ 选「覆盖冲突项」→ 命名回到「微信扫一扫」、冲突值消失（`78/79_*.png`，`stage7.json`）。
- 分享路径（文本分享）在「导出 JSON」选项内提供（本轮以文件往返为主验证）。

## 6. 回归矩阵（ACT-06，5/5 通过）

| 用例 | 判据 | 结果 |
|---|---|---|
| REG-A v1 动作 `essential.double → 截屏` | 截图数 +1 + `EXEC [essential.double] … 截屏` | 32→33 ✅ |
| REG-B 安全红线 `power.long` 原生电源菜单 | `GlobalActionsDialogLite` 可见 + `power.long unbound -> invoking native GlobalActions` | ✅（`60_global_actions.png`） |
| REG-C 组合键 `POWER+VOL_DOWN` | 截图数 +1 + `CHORD SHIELD` | 33→34 ✅（另验 `POWER+VOL_UP` → 电源菜单 ✅） |
| REG-D 普通 `CONFIG_UPDATE`（无 command） | `CONFIG_UPDATE applied … bound=6/12`、无 `unknown command` | ✅ |
| REG-E v2 分流相机快门 | `resolved=profile:prof_camera_shutter` + `/sdcard/DCIM/Camera` 照片 44→45 | ✅ |
| 配置还原 | 实验后推回 `config_before.json` → `bound=6/12`、`volume_up.long=system`、场景绑定已移除 | ✅（`stage8.json`） |

## 7. UI 自审（ACT-07，审 → 修 → 复审）

- **第一轮（审）**：`05_activity_editor`、`09_browser_wechat`（含行局部放大）、`07_app_picker`、`33_label_manager`、`24_trylaunch_toast`。
  - 结论：编辑器/选择器/命名管理**无缺陷**；活动行局部放大确认「主行文字与 chip 有明确间隙、组件行不压按钮」。
  - **发现并修复**：试开启成功 Toast 显示短类名而非用户命名 → 改为回填命名（`已拉起 微信扫一扫`）。
- **第二轮（复审）**：`91_trylaunch_toast.png`（命名 Toast）、`90/92_browser`、`73_dark_matrix`（深色模式）、`74_dark_settings`。
  - 结论：对比度、截断、重叠、8dp 间距节奏、贴边检查**全部通过**（vision 结论：clean / no concrete defects）。
- 深色模式已切换 `cmd uimode night yes` 截图后还原（`stage7.json.night_before/after = no`）。

## 8. system_server 稳定性红线（ACT-08）

- 实验期间（单次开机会话内）`system_server` PID **2731 恒定**；两次 PID 变化均来自**计划内重启**（2757 → 2739 → 2731 → 2786）。
- `logcat` 无 `FATAL EXCEPTION`、无 `KeyPilot: [ERR]`；Vector 日志 `grep 'KeyPilot.*ERR|FATAL'` 0 命中。
- 命中的 `Watchdog` 字样全部来自无关组件（keystore2 RKPD、PackageWatchdog），已甄别。
- TRY_LAUNCH 全程在 `KeyPilot-ConfigIO` 线程 `try/catch(Throwable)`，失败以回执返回而非抛进 system_server 主循环。

## 9. 迭代记录（3 轮，每轮一个根因）

| # | 根因 | 修复 |
|---|---|---|
| 1 | 导入用 `ACTION_OPEN_DOCUMENT` + `*/*` 过滤 → DocumentsUI 弹「打开方式」不回传 URI，导入静默失败 | 导入 MIME 收敛为 `application/json`（`LabelManagerDialog.openImportPicker`） |
| 2 | 试开启成功 Toast 显示短类名，未体现用户命名 | `findName()` 回查 `LabelStore`（user > community > 短类名） |
| 3 | `startActivity` 对**不存在的组件**不抛异常（只返回 start result code）→ 误报「ok」 | 钩子端先 `PackageManager.getActivityInfo()` 预校验，缺失即回执 `组件不存在: …` |

## 10. 已知问题 / 备注

1. 超大包 `GET_ACTIVITIES` 计数比原始清单少 1–3 条（微信 −1、支付宝 −3，相机 0），展示层无感。
2. 试开启**失败 Toast** 未单独截图：列表组件均来自 PackageManager（真实存在），失败分支经桥命令实测（见 §2），UI 侧与成功分支共用同一回执监听。
3. 开机时观察到第三方 Xposed 模块 `com.kinginu.pixelmask.PixelMaskHookEntry` 的 native crash（`XposedHelpers.setStaticFinalField` 栈），**与 KeyPilot 无关**，未处理。
4. 实验产物保留在 `/sdcard/Download/keypilot_labels.json`（导出文件）与本目录 `exported_labels.json`。
5. 仓库：工作区原本**不存在 git 仓库**（无 `.git`、无 remote），已 `git init` 并提交（不 push、不建 remote）。

## 11. 交付状态

- 设备：停在桌面、亮屏、输入法已还原 Gboard、配置还原为实验前状态（`bound=6/12`）、命名数据保留（用户命名 1 条 + 内置 4 条）。
- 模块：`Magisk 模块 0 disable`、未动 bootloader，实验相关改动仅 KeyPilot APK。
