# 系统更新页丢失「更新日志 / 固件信息」—— 根因与修复（NothingUpdateFix 模块）

> 设备：Nothing Phone (3)（A024，代号 Metroid）| 系统：Nothing OS 5.0（Android 17，SDK 37）
> Build：`C5.0-260924-2115` | Magisk v31.1 + JingMatrix/Vector v2.2（LSPosed）| 记录日期：2026-11-10
> 状态：✅ **已定稿** —— PC 侧构建/静态校验 + 真机验证（2026-11-10，phase4）全部完成，T1–T7 勾销
> 开源仓库：<https://github.com/rtzhang1202/nothing-update-fix> | 证据目录：`NothingPhone3-Root/analysis/phase2–phase4/`

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
   实证）；链路 B 恢复的是数据面。
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

**关联**：开源仓库 <https://github.com/rtzhang1202/nothing-update-fix>（模块源码、`docs/ROOT_CAUSE.md`、`screenshots/`、`report.md`）；
本地源码 `NothingPhone3-Root/NothingUpdateFix/`；真机验证证据 `NothingPhone3-Root/analysis/phase2–phase4/`
（phase2 数据流取证 / phase3 注入与 PIF 实验 / phase4 模块安装与链路验证，含 12 张截图与 `lspd_module_log.txt`）。
