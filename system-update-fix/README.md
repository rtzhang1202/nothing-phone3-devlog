# 系统更新页丢失「更新日志 / 固件信息」—— 根因与修复（NothingUpdateFix 模块）

> 设备：Nothing Phone (3)（A024，代号 Metroid）| 系统：Nothing OS 5.0（Android 17，SDK 37）
> Build：`C5.0-260924-2115` | Magisk v31.1 + JingMatrix/Vector v2.2（LSPosed）| 记录日期：2026-11-10
> 状态：✅ **已定稿** —— PC 侧构建/静态校验 + 真机验证（2026-11-10，phase4）全部完成，T1–T7 勾销；
> **v1.0.1**（phase6）追加视图注入，GMS 更新页的 What's New / 固件信息复活（见 **§九**）
> 开源仓库：<https://github.com/rtzhang1202/nothing-update-fix> | 证据目录：`NothingPhone3-Root/analysis/phase2–phase6/`

---

## 一、症状

ROOT 之后，两处 Nothing 定制内容整体消失：

1. **系统更新页**（`设置 → 系统 → 系统更新`，实为 GMS gOTA 客户端
   `com.google.android.gms/.update.SystemUpdateActivity`）：退化成 GLIF 裸壳——只有
   「系统已是最新版本」、Android 版本/安全更新两行、「上次成功检查更新的时间」和
   「检查更新」按钮，**What's New 更新日志块与固件信息行整块不见**。
2. **软件信息页**（`设置 → 关于手机 → 软件信息`，`DeviceSoftwareInfoFragment`）：
   只剩静态卡 + 一大块黑区，**「更新日志」「固件信息」动态卡片消失**。

## 二、根因（一句话）

**这两处内容从不存本地、全部服务端下发，而两条下发通道各自独立断了**：更新页 changelog
的真身是 GMS Gservices 标志 `update_title/update_description/update_size`（组装进
`UpdateDescription`，`b` 字段为空就 `setVisibility(8)` 整块隐藏），排查期清 GMS 数据把已
拉取的 OTA 元数据缓存一并清掉 → 三键 ABSENT；软件信息页卡片来自 Nothing 配置服务
`iota.nothing.tech`，该端点当前 503（AWS ALB 目标组不健康），而 APK 本地兜底
`settings_recommendation.json` 恰好没有该页的卡片 → 黑区。

## 三、证据链

### 1. 更新页 changelog = Gservices 三键，空则整块隐藏（GMS 字节码实锤）

- `com.google.android.gms.update.UpdateDescription`：4 个 `public final String` 字段
  **a/b/c/d**，构造 `(String,String,String,String)`，Parcelable。
- 唯一填充点 `Lgstx;.b()` 组装 `SystemUpdateStatus.x`，四参数全部取自 Gservices 标志
  （log tag `GservicesUtils`，异常串 "Failed to parse value from Gservices for flag."）：
  `a`←`update_title`、`b`（HTML changelog）←`update_description`、`c`←`update_size`、
  `d`←`update_end_of_life_url`。
- 渲染端 `gtak.a()`：`b` 经 `Html.fromHtml` 进 changelog TextView；**`b` 为 null/空 →
  `K().setVisibility(8)` 整块隐藏**（classes13_dis:928799-928818）。
- 文案证据：「更新日志」等字面在 NothingSettings/GMS/NothingOfflineOtaUpdate 的任何
  dex/资源里 **0 命中**——内容只能远端下发。

### 2. Gservices 三键 ABSENT + 清 GMS 数据的锅（华为 FAQ 同病例）

- `/data/user/0/com.google.android.gms/databases/gservices.db`：28 行 `update_%` 全是配置类
  键，**`update_title/update_description/update_size` 全部 ABSENT**。
- 排查期清过 GMS 数据 → 已拉取的 OTA 元数据缓存被清。华为官方 FAQ 同机制病例：
  「清空软件更新缓存后，当前版本的更新日志不显示内容」，等新版本推送才恢复。

### 3. 管线本身是健康的（假说 B 证伪）

点「检查更新」触发强制 checkin，logcat 端到端全绿：

```
I/Checkin: Checkin Succeeded: https://android.googleapis.com/checkin
I/Checkin: Checkin Operation finished with result: SUCCESS
I/SystemUpdate: [Common,RequestConfigUpdateLoader] Config update succeeded.
```

「上次检查时间」当场写回（11月9日→00:36）；`UPDATE_INFO_PREFS` 空 map、
`current_update_url` 空、`metadata_url` 全局 grep 零残留 → 干净的「无待更新」状态，
不是「拉取坏了」。截图 01/02（`screenshots/`）：check 前后除时间戳外零差异。

### 4. 注入实验：状态门 + checkin 清库（决定钩子路线）

- **实验 A（PIF 无罪）**：禁用 PlayIntegrityFix + 重启 + 重查 → 三键仍 ABSENT。
- **实验 B（本地注入探针）**：备份 gservices.db 后 INSERT 三键测试值（插后复查确实在库）
  → 强停 GMS → 重开更新页 → **What's New 依旧不渲染**（无 pending update 时 UI 层状态门
  控制，gservices 值本身不驱动 UI）；且 GMS checkin 后 **注入行全被清掉**（写库不持久）。
  → 结论：**修链路 B 必须钩查询时点，不能写库**；回滚后 update_% 与基线 diff=0。
- 截图 03–06：两组实验前后对照。

### 5. 软件信息页：iota 503 + 兜底配置缺卡

- `https://iota.nothing.tech/api/v2/…` 全路径 503（`Server: awselb/2.0`，AWS ALB 默认错误页）；
  **check-host.net 10 国 3 大洲探针（CH/FR/IR×2/MD/NL/RU/US×3，3 个 ALB 后端 IP）全部 503**
  → 服务端真故障，非地区/WAF/路由。CNAME 指向 eu-central-1 ALB，国内外解析一致。
- 卡片链（dex 反汇编还原）：`ConfigGrabber(settings_configs)` → `NTConfigManager` →
  `settings_recommendation` 卡片数组 → 按 `fragmentTag`（= Fragment 类名）注入
  `RecommendationPreference`。**本地兜底 `res/raw/settings_recommendation.json`
  （version=100000002，18 卡）没有 `DeviceSoftwareInfoFragment` 的卡** → 线上 503 时
  该页必黑区。

### 6. 旧结论推翻 / 修正记录（写回文档）

排查过程中被真机证据推翻或部分修正的旧结论，留档防止回潮：

- **`layoutType` 语义修正**：早期结论认为 `layoutType`（1/2/5/6）是服务端下发、控制更新页布局的字段 → **推翻**。字节码实证它是 GMS **客户端** fragment 常量，服务端不下发该字段（phase2 反汇编）；
- **「服务端降级」部分修正为「清数据 + 状态门」**：早期把两处内容消失一概归因「服务端降级/不下发」→ **部分修正**。更新页 changelog 丢失的主因是**排查期清 GMS 数据清掉 OTA 元数据缓存**（`update_*` 三键 ABSENT，华为 FAQ 同病例）+ **无 pending update 时的状态门**（原厂 UI 本来就不渲染，注入实验实证）；服务端故障（iota 503）只解释软件信息页黑区，与更新页无关——两条链路必须分开定性；
- **PIF 无罪**（phase3 二分实验）：禁用 PlayIntegrityFix + 重启 + 重查，三键仍 ABSENT → 早期「PlayIntegrityFix 导致 gservices 读不到」假设证伪；
- **gservices 直写不可行**（phase3 注入实验）：本地 INSERT 三键测试值 → 不驱动 UI（状态门），且 GMS checkin 后注入行全被清（写库不持久）→ 修链路 B 必须钩查询时点；
- 其余红鲱鱼：`last_metadata_url` 属动态运营商 ID 列表服务、`UpdateConfigService` 是实验特性配置刷新、暗码 `*#*#8377466#*#*` 的 `SoftVersionDisplayActivity` 全本地完好（正是本模块复用的固件信息页）。

## 四、修复：NothingUpdateFix 模块（双链路钩子）

备选方案 hosts 劫持 iota / 重打包 NothingSettings.apk / 伪造完整 pending-update 状态
分别因 TLS 脆弱、OTA 即失效、复杂度与触发真实 OTA 流风险被否。落地两条低侵入钩子链：

```
MainHook (Xposed 经典 API 82, 作用域 settings + gms)
├─ 链路 A（com.android.settings）：hook onCreatePreferences(Bundle,String)
│   类名守卫 DeviceSoftwareInfoFragment + 具体方法直钩双路由，注入：
│   「更新日志」→ 模块 ChangelogActivity（内置 changelog.json：当前版+7 历史版）
│   「固件信息」→ Nothing 自带 SoftVersionDisplayActivity（全本地）
│   findPreference 去重，幂等
├─ 链路 B（com.google.android.gms）：hook ContentResolver/Client.query 全部重载
│   authority=com.google.android.gsf.gservices 时 MatrixCursor 按原列名重建：
│   覆盖/追加 update_title / update_description / update_size
│   （update_description HTML 运行时拼装：changelog 分节+固件信息表，
│     基带/内核/build 号实时读属性）→ 查询时拦截，checkin 清库打不掉
└─ 加分钩：UpdateDescription 构造器 setIfEmpty 补空，绝不覆盖真实 OTA 描述
```

工程细节（详见仓库 README / report.md）：纯反射构造 Preference 不依赖宿主 androidx 版本；
`XposedBridgeApi-82.jar` 仅编译期、dexdump 硬校验保证不入 dex；build.sh 全流程
badging/dexdump/apksigner 校验；keystore 不入 git。

## 五、构建与安装（速查）

```bash
bash build.sh          # 产物 release/NothingUpdateFix.apk（v2/v3 签名）+ .sha256
```

安装 APK → Vector 管理器启用「Nothing update fix」，作用域勾 `com.android.settings` +
`com.google.android.gms` → 重启 → APP「状态」区看钩子命中日志。
框架选型：原版 LSPosed 官方 2023-10 起停更且不支持 Android 17，用 JingMatrix/Vector
（v2.1+ 支持 Android 8.1–17，本机 v2.2/3080，与设备 zygisk_vector 同款），安装脚本
`tools/install_lsposed.sh`。

## 六、验证

### 已完成（PC 侧，2026-11-10）

- ✅ `aapt2 dump badging`：label/minSdk 26/targetSdk 37/自适应图标/launchable-activity；
- ✅ `dexdump`：6 个模块类齐全，**零** Xposed API 类定义入 dex；
- ✅ `apksigner verify` v2/v3 通过，SHA256 `eed41806…c0b59`（含反射加固重建，见仓库 git log）；
- ✅ `ChangelogData.buildUpdateDescriptionHtml()` 桌面 JVM 实跑，HTML 结构逐项吻合；
- ✅ 根因链全部有证据锚定（见仓库 `docs/ROOT_CAUSE.md` E1–E9 + `screenshots/`）。

### 真机验证结果（TODO T1–T7 全部勾销，2026-11-10，证据 `analysis/phase4/`）

- [x] **T1 链路 A**：✅ 软件信息页出现「更新日志」「固件信息」两条入口，原生 `android:id/title` 行样式、不重不漏、不重入（截图 05–07，三轮迭代对照）；
- [x] **T2**：✅ 「更新日志」→ ChangelogActivity 渲染 `Nothing OS 5.0 (C5.0-260924-2115)` + New Features / Visual Enhancements / Fresh Widgets 全分节 + nothingarchive 链接（截图 09）；
- [x] **T3**：✅ 「固件信息」→ SoftVersionDisplayActivity「软件版本」页正常：基带 `MPSS.DE.7.0-02698-…`、内核 `6.6`、版本号 `C5.0-260924-2115`、PSN `00019157L001724`、Android 17/型号 A024 全部渲染（截图 08）；
- [x] **T4 链路 B**：⚠️→ ✅ 定性完成——加分钩 `UpdateDescription.<init>` **命中 5 次**（boot 2 + 开页 3，`setIfEmpty` 对字段 a/b/c/d 无 NoSuchField 错，GMS 26.43.30 字段名成立）；但无 pending update 时 What's New 块被**状态门**拦住不渲染（原厂行为，截图 10/11，`lspd_module_log.txt`：ctor patched ×5、gservices intercepted ×0）→ 按约定定性**已知限制**，最终判据等下一次真实 OTA；gservices query 拦截钩在 Android 17 无 3 参 `query` 重载已跳过（无害），4/5/6 参及 ContentProviderClient 各重载注册成功；
- [x] **T5**：✅ 模块经 lspd config 启用（`modules enable` + `scope set` 双作用域），Vector 管理器 UI 复核开关 ON（截图 02–04）；重启后钩子仍命中（boot 期 ctor patched ×2，持久性成立）；强停重进页面不重不漏；
- [x] **T6**：✅ 回归通过——设置各页不崩、「检查更新」流程正常、APP「状态」区命中日志正常（截图 12）；终检 Magisk 9 模块 0 disable、网络正常；
- [x] **T7 截图**：✅ 按 `screenshots/README.md` 预留命名补齐 `analysis/phase4/01–12`。

**链路 A 三轮迭代过程**（详见 `analysis/phase4/report.md`）：

1. **轮 1**：route1 因 Settings 无 `PreferenceFragmentCompat` 回落 route2（直钩 `DashboardFragment#onCreatePreferences`，命中），但注入报 `NoSuchMethodError: PreferenceScreen#findPreference(String)`——Vector legacy bridge 的 callMethod 不接受 String→CharSequence；
2. **轮 2**：改精确签名反射仍失败（`setKey`/`getPreferenceCount` NoSuchMethodException）——离线 dexdump（`analysis/phase4/nt1_dexdump.txt`）实证 **NothingSettings 的 androidx.preference 被 R8 混淆**（仅 `getKey/setVisible/compareTo/toString` 保留原名）；
3. **轮 3**：✅ 运行时签名+行为探针解析混淆 API，不依赖任何混淆方法名——`titleField=F`（探针标记+StringBuilder 输出顺序）、`intentField=K`（唯一 Intent 字段）、`addPreference=J1(Preference)Z`（自建探针 PreferenceScreen 逐一试 4 候选，List 0→1 者命中）、WeakHashMap 注册表替代 findPreference 去重。

## 七、已知限制与风险

1. **pending-update 状态门**：无待装 OTA 时 GMS 可能在 UI 层拦住 What's New（注入实验
   实证）；链路 B 恢复的是数据面。**—— v1.0.1 起该限制已被视图注入绕过，见 §九**。
2. **GMS checkin 清 `update_%` 命名空间**：手工改库不持久（钩子不受影响）。
3. **GMS 升级兼容性**：加分钩依赖 GMS 26.43.30 字段名 a/b/c/d，升级改名则静默失效
   （全 try/catch，主钩不受影响）。
4. 钩子常开无开关（XSharedPreferences 不可靠）；`update_size` 显示 build 号而非包体大小。
5. **风险免责**：root/Xposed 有卡米、数据丢失、完整性检测失效、OTA 后失效等真实风险，
   模块只做展示层补内容、不动系统分区，但一切风险自担（详见仓库 README §10）。

## 八、附：诊断方法论

1. 字节码还原数据流（谁 new UpdateDescription、参数从哪个标志来、空值在哪隐藏）→
   找到可钩的确切位置；
2. 假说区分探针（A「没东西渲」 vs B「拉取坏了」）：强制 checkin + 状态缓存取证 + UI 闭环；
3. 本地注入实验先验证渲染路径与写库行为，**再**决定钩子形态（查询拦截 vs 写库）；
4. 多国出口矩阵把服务端 503 与地区/WAF/路由区分开；
5. 红鲱鱼逐个排除（layoutType / last_metadata_url / PIF / UpdateConfigService），
   修正过的结论要写回文档。

## 九、更新页内容复活（v1.0.1，视图注入）

> 追加记录（2026-11-10，phase5 + phase6）。本章**取代 §七-1 的「pending-update 状态门 = 已知限制」定性**——
> 真因不是"无待更新时原厂不渲染"，而是**我们钩的那条渲染路径在当前状态下根本不执行**。
> 证据：`NothingPhone3-Root/analysis/phase5/`（`route_recon.md` 桌面侧 / `device_route_evidence.md` 真机侧）、
> `analysis/phase6/`（`gms_hook_design.md` 决策链逆向 / `gms_render_log.md` 渲染期日志 / `verify/` 三轮验证产物）。

### 1. 问题：重启后依旧 AOSP 样式

phase4 收官时链路 B 挂着「`UpdateDescription.<init>` 命中 5 次但不渲染」，判据留给下一次真实 OTA。
用户侧实测不认这个判据：**重启后重新打开更新页，仍是同一副裸壳** —— 深色 GLIF 模板，只有
「系统已是最新版本」标题、「Android 版本 17 / 安全更新 / 上次成功检查更新的时间」摘要和「检查更新」按钮，
What's New 块与固件信息一个像素都没有。用户把它描述成"还是 AOSP 那种样子"（实测比对：资源 ID 全是
`com.google.android.gms:id/glif_layout|suc_layout_title|sud_layout_header`、薄荷绿强调色 —— 是 GMS 的
GLIF 深色模板，不是老 AOSP 蓝顶白底，也不是 Nothing 黑底点阵）。于是三个问题必须查清：

1. 是不是 Nothing 另有一个"黑底点阵"的更新页，我们从一开始就瞄错了目标？
2. 为什么构造钩命中了 5 次，界面上却毫无反应？
3. 「已最新」这个状态下，GMS 到底走的是哪条渲染代码？

### 2. phase5 定案：全机不存在"黑底点阵更新 Activity"，样式是内容级定制

双侦察并行：桌面侧纯离线反汇编，真机侧纯只读实启，结论互证。

| 侦察 | 手段 | 关键结论 |
|---|---|---|
| 桌面侧（`route_recon.md`） | dexdump 全量反汇编 + manifest 解析 + `dumpsys package` resolver 表交叉比对 | `SYSTEM_UPDATE_SETTINGS` 唯一 handler 就是 GMS `SystemUpdateActivity`（priority=1）；NothingSettings 476 个 activity 里 ota/update/upgrade 命名 **0**；`ci_action_on_sys_update_*` 两条消费链都是 `sendBroadcast` 不是 `startActivity`，且 action 无默认常量 |
| 真机侧（`device_route_evidence.md`） | 15 个候选 component 逐个 `am start` + `screencap` + `uiautomator dump` | 只有 1 个是真"系统更新页"；其余是离线刷包工程页 / Play 系统更新 / 版本信息页；`CarrierConfig` 四键 `bool=false` + `intent_string` 空 → `startOtaPage` 在 `TextUtils.isEmpty(action)` 处直接 `return void`，改道链在本机是断的 |

全机否定清单（"Nothing 更新页"不存在的最小完备证据）：

- `com.android.settings` manifest **476** 个 activity：名称含 ota/update/upgrade **0**；`NtSettings$*` 29 个里无 SystemUpdate 类；
- **73** 个 `com.nothing.*` 包逐包 resolver 扫描：除 `OfflineOTAUpgradeApp`（手动离线刷 OTA 包）与 `userguide` 的 `OTAUpdateReceiver`（广播）外，无任何 OTA 组件；
- 全机 **964** 条 MAIN activity：类名含 ota/update 的只有 `OfflineOTAUpgradeApp/.ui.MainActivity` 与本模块自己的 `MainActivity`；
- `com.nothing.smartcenter` 里全部 `*.ota.*` 都是**耳机 TWS 固件页**（`com.nothing.ear*.ota.os.*`），与手机 OS 更新无关；
- `pm list packages -d` 仅 4 个禁用包，无一相关；`disabledComponents` 中 settings/gms/OfflineOTAUpgradeApp/smartcenter 均无 update/ota 条目；
- 15 个候选实启里，`OfflineOTAUpgradeApp/.ui.MainActivity` 是深色工程调试页（Roboto、无点阵、无更新日志），`vending/SystemUpdateActivityAlias` 是 **Play 系统更新**（Mainline 组件），`SoftVersionDisplayActivity`/`FirmwareVersionActivity` 是版本信息页 —— 全部不是目标页。

→ **定案：用户口中的"Nothing 样式"不是独立页面，而是对 GMS 更新页的内容级定制**（What's New 更新日志块 + 固件信息行）。要把内容画回来，只能在这个 GMS 页面内部做，绕不开它。

### 3. phase6 破案：类级渲染门 + 无 getter + layoutType=6 实测

#### 3.1 渲染门是"类级分派"，不是方法里的 null 判断

```
SystemUpdateChimeraActivity.onCreate  → layoutType=a()(1/5/6/其它) → 选 OtaStatusFragment 实现类
   └─ 状态事件 status=0x000（已最新）
        └─ 工厂 gszy.a(status, layoutType) → OtaUiController 27 个子类之一
             ├─ gtah  ← "无更新/检查中"族：i() 只写标题+版本摘要，类块内 UpdateDescription 引用 0 次
             └─ 其余 14 个 ← "有更新"族：全部调用 gtak.a()，desc.b 只在这里被读
```

- `gtah` 类块（`classes13_dis.txt:925862`–`927889`）内 **`UpdateDescription` 出现次数 = 0**；
- `UpdateDescription.b` 的唯一消费者是 `gtak.a()`（:928751），空值判定 `TextUtils.isEmpty(desc.b) → K().setVisibility(8)` 在 :928817；而 `gtak.a()` 的 **14 个调用点全在"有更新"族控制器里**，`gtah` 不在其中；
- → 往 `UpdateDescription` 里塞字段，等于往一条"当前状态永不执行"的分支里塞数据。**phase4 那 5 次命中是真的，但那些实例没有消费者。**

#### 3.2 没有 getter，字段读无法 hook

`UpdateDescription`（`classes3_list.txt:2606859`）只有 4 个 `public final String a/b/c/d` + 一个
`(String,String,String,String)` 构造器，**零访问器** —— 所有读取都是 `iget-object` 内联直读。
Xposed 只能钩方法：钩不到字段读，"钩 getter 返回本地 changelog"这条任务原方案在字节码层面不可实现。

#### 3.3 layoutType=6 实测（旧假设再次推翻）

| 口径 | 触发方式 | status | layoutType |
|---|---|---|---|
| Run 1 | warm resume（`am start`） | `0x000` | **6** |
| Run 2 | `force-stop` 后冷启动 | `0x000` | **6** |
| Run 3 | 点「检查更新」→ checkin 21021 + `Config update succeeded` | — | **无 `Status change`** |
| Run 4 | config 成功后返回再重开 | `0x000` | **6** |

- 3/3 一致 = 6 → **`layoutType=6` 不等于"富布局/待装"**，它是本机 GMS + `SudThemeGlifExpressive` 下的**已最新默认变体号**（"6=富布局"旧假设推翻，接续 §六-1 的 `layoutType` 修正：它既不是服务端下发，也不代表有内容）；
- 视图树 26 个节点：`sud_layout_header`（图标+标题）→ `sud_layout_content` → `descriptionLayout` → `description`（版本摘要）→ `suc_footer_button_bar`；**changelog / 固件信息节点数 = 0**，连隐藏占位节点都没有；
- 该布局 4 个 TextView 的 `android:visibility` 默认全是 `2`(gone)，靠代码 `setVisibility(0)` 打开 → "块看不见"= 没人打开，不是没膨胀；
- 点「检查更新」只出 checkin + config 日志，**不产生新的 `Status change`** → 页面不重绘（这既说明注入的卡片不会被重绘冲掉，也说明 L1 多拍注入有必要）。

一句话根因：**GMS 更新页是「状态机 + 每状态一个控制器类」，更新日志块只存在于"有更新"族的控制器里；"已最新"族根本没有这块 UI 的代码路径。**

### 4. 解法：`GmsPageInjector` 视图注入（两张 Nothing 深色卡）

方案 A（改数据让原厂渲染）三条路全死：**A1** 钩 getter —— 无 getter，不可实现；**A1'** 强显 `id/description` —— 没有内容，只是把版本摘要重显示一遍，还要钩 GMS 进程高热路径的全局 `setVisibility`；**A2** 反射替换 `SystemUpdateStatus.x` 再调 `gtak.a()` —— 会覆盖页面标题、打开 `suw_description`/size 行、且强依赖 `gtak/gtcj` 混淆名。
落地 **方案 B：视图注入**，与状态机彻底解耦：

```
GmsPageInjector（GMS 进程内）
├─ L1 主锚点：com.google.android.gms.update.phone.SystemUpdateChimeraActivity.onCreate/onResume（未混淆）
│    decorView.post + postDelayed(250/800/2000ms) 多拍注入
├─ L2 复注入：gtah.i(gszx,gtcj)、gtak.a(Activity,gtcj,SystemUpdateStatus,Z)
│    形状探针校验（static + void + 第 2 参简单名 gtcj），校验失败只丢 L2
└─ L3 兜底：android.app.Activity.onResume 全局钩 + 类名 contains(".update.")，仅 L1 找不到类时挂
```

- **不写死资源 id**：`getIdentifier("description","id","com.google.android.gms")` → 取 `descriptionLayout` 的父线性容器 → 插在版本摘要之后；`description` 缺失时逐级退 `descriptionLayout → sud_layout_content → sud_layout_template_content → glif_layout`（名称解析，GMS 版本升级 id 数值变了也不受影响）；
- **卡 1 · What's New**：标题「更新内容」+ 副标题 `Nothing OS 5.0 · C5.0-260924-2115` + 分节要点（默认渲染前 3 节 × 每节前 4 条，全文折叠）+ 底部「查看全部更新日志 ›」→ 模块 `ChangelogActivity`（跨包启动带 `FLAG_ACTIVITY_NEW_TASK`）；数据读模块 `assets/changelog.json`，失败回退编译内置数组；
- **卡 2 · 固件信息**：9 行 —— 型号 A024 / 设备代号 Metroid / Android 17 / 系统版本 / Build 号 / 基带 `MPSS.DE.7.0-…` / 内核 6.6 / 安全补丁 2026-09-01 / 序列号（无权限位落 `—`），全部运行时读 `Build` + `SystemProperties`；
- **样式**（Nothing 深色美学）：卡底 `#141414` 圆角 24dp、padding 20dp；卡标题 18sp bold `#F5F5F5` + 左侧 3dp 红条 `#D71921`；副标题 12sp `#8A8A8E`；分节标题 12sp bold 全大写 `#D71921`；条目 14sp `#CFCFCF`；分隔 1px `#262626`；页脚链接 13sp `#FF5A5F`；浅色模式按 `uiMode` 反向兜底（白卡/深字）；
- **幂等**：容器 `setTag("nuf_cards")`，`findViewWithTag` 命中即返回，L1/L2/L3 多拍不叠加；页面重建后新容器无 tag → 自愈；
- **全部 try/catch，只 `addView`，零宿主写入**：任何一环失败只丢内容，不动 GMS 原有渲染。

### 5. 最大的坑：`SystemUpdateChimeraActivity` 不是 `Activity`

首装轮 1 注入**完全没发生**，日志只有一行：

```
java.lang.ClassCastException: com.google.android.gms.update.phone.SystemUpdateChimeraActivity
    cannot be cast to android.app.Activity
```

`SystemUpdateChimeraActivity` 是 **Chimera 模块委托类**，继承链
`… < agdp < agdw < agco < agdo < com.google.android.chimera.android.Activity < afxg < ContextThemeWrapper`，
压根不在 `android.app.Activity` 体系里；真正的窗口由容器 Activity
`com.google.android.gms.update.SystemUpdateActivity` 持有。

修法 `resolveActivity()` 三级解析：① 在委托对象上反射找 0 参 getter / `Activity` 字段 →
② `ActivityThread.getActivities()` 扫描全部存活 Activity，**优先选能 `findViewById` 到锚点 id 的那个** →
③ 仍失败则 L3 全局 `onResume` 兜底。轮 2 重建后只 `force-stop com.google.android.gms / com.google.android.gms.ui`
热重载，`added 2 cards` 立刻出现，两卡上屏。

顺带记两条**集成前既有**的无害日志（别误判为本次引入）：`chainB hook skipped …query/3: NoSuchMethodError`
（Android 17 删了 3 参 `query` 重载，注册前已跳过）与 `UpdateDescription ClassNotFoundException`
（部分 GMS 进程 classloader 里没有该类）。

### 6. 真机验证（3 轮，判据全 PASS，证据 `analysis/phase6/verify/`）

| # | 判据 | 结果 | 证据 |
|---|---|---|---|
| 1 | 两张卡渲染 | ✅ | `r3_page.png`（更新内容卡）、`r3_bottom.png`（固件信息卡） |
| 2 | What's New 正文 | ✅ 标题 + `Nothing OS 5.0 · C5.0-260924-2115` + 7 个分节 | `r3_ui.xml` |
| 3 | 「查看全部更新日志 ›」 | ✅ 点击跳转，`topResumedActivity = .ChangelogActivity` | `r3_link_bounds.xml`（clickable）→ `r3_changelog.png` |
| 4 | 固件信息 9 行 | ✅ 基带/内核非 `—`，序列号 `—`（无权限位） | `r3_bottom.xml` |
| 5 | 幂等 | ✅ 点「检查更新」后、返回后各 1 处，不重复 | `r3_after_check.xml`、`r2_after_back.xml` |
| 6 | 链路 A 回归 | ✅ 软件信息页仍有「更新日志」「固件信息」两入口 | `r3_chainA.png` / `r3_chainA_ui.xml` |
| 7 | 锚点命中 L1 | ✅ `L1 hooked …#onCreate/#onResume` → `container Activity resolved: …SystemUpdateActivity` → `added 2 cards (via onCreate, anchor=description, host=LinearLayout)` | `r3_lsposed_log.txt` |
| 8 | 环境终检 | ✅ 9 模块 / 0 disable，`tun0` UP | — |

三轮迭代：**轮 1** 首装零注入（Chimera 强转，见 §5）→ **轮 2** 修 `resolveActivity()` 后两卡上屏，幂等复验 →
**轮 3** 给 `gtah.i` after-hook 补 fired 日志、`liveActivities()` 复用兜底，卡片与幂等再复验通过。

**遗留（不影响验收）**：L2 `gtah.i` 钩子已安装但真机未观测到 fired（形状探针可能匹配到非渲染路径的同名方法），
`gtak.a` 形状探针直接 miss（该渲染器只在"有更新"状态族可达，本机 `status=0x000` 永不进入）→ 两者都只影响
"状态重绘后的复注入"，卡片在被移除前不会消失；且实测「检查更新」本就不重建 UI，L1 的 `onResume` + 多拍已覆盖。

### 7. 结论修正（写回，防回潮）

- **§七-1 状态门定性废弃**：不是"无待更新时原厂拦着不渲染"这类无解限制，而是我们喂数据的 `UpdateDescription` 在"已最新"状态族里**根本没有消费者** —— 设计错位，不是数据缺失。链路 B 保留作数据面兜底（真实 OTA 到来时"有更新"族会读它），页面呈现改由链路 C（视图注入）负责；
- **`layoutType` 语义再修正**：`6 ≠ 富布局`。实测本机"已最新"状态也是 6，它是主题化默认变体号；接续 §六-1 的"客户端 fragment 常量、服务端不下发"；
- **方法论沉淀**：遇到"注入命中了但界面没变"，先问"**这个状态的渲染代码路径里，到底有没有我的数据的消费者**"，而不是先怀疑注入方式。GMS 这类状态机 UI 的分派单位是**类**（27 个 `OtaUiController` 子类），不是方法内的 `if (x != null)`。

### 8. v1.0.1 开源

模块版本 **v1.0.1**：<https://github.com/rtzhang1202/nothing-update-fix>
（源码 `NothingPhone3-Root/NothingUpdateFix/`，本版新增 `app/src/main/java/io/github/rtzhang/nothingupdatefix/GmsPageInjector.java`）。
验证用 APK：`release/NothingUpdateFix.apk`，SHA256 `c3f49ca469f6ae091a2913bbbe57fce4af22f9b4ad9ccd6f6ab57e199dc17605`。

> **发布状态（已核实）**：本版两个提交 `a83ecfe feat(gms)`（视图注入）与 `fca2622 fix(app)`（About 链接）**已推送**，
> 远端 `main` = `8962f0b2e9de0e9b7a2fe3c42db7cfe92fa00a76`；Release **v1.0.1** 已上线
> <https://github.com/rtzhang1202/nothing-update-fix/releases/tag/v1.0.1>，资产 `NothingUpdateFix.apk`（50263 B）
> 匿名下载回读 SHA256 = `c3f49ca469f6ae091a2913bbbe57fce4af22f9b4ad9ccd6f6ab57e199dc17605`，与发布说明一致；
> 真机截图 `screenshots/device_v101_*.png` 已随提交入库。

**关联**：开源仓库 <https://github.com/rtzhang1202/nothing-update-fix>（模块源码、`docs/ROOT_CAUSE.md`、`screenshots/`、`report.md`）；
本地源码 `NothingPhone3-Root/NothingUpdateFix/`；真机验证证据 `NothingPhone3-Root/analysis/phase2–phase6/`
（phase2 数据流取证 / phase3 注入与 PIF 实验 / phase4 模块安装与链路验证，含 12 张截图与 `lspd_module_log.txt` /
phase5 全机路由否定 / phase6 渲染门逆向与视图注入三轮回验，含 `verify/`）。
