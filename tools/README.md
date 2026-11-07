# ROOT 后工具箱篇

> Nothing Phone (3) · Android 17 / NOS 5.0 · Magisk v31.1 · 2026-11-07
> ROOT 之后装什么、怎么装、踩了什么坑。

## 一、工具清单（全部装好并验证）

### 核心五件套

| 类别 | 工具 | 版本 | 包名 | 说明 |
|---|---|---|---|---|
| 终端 | Termux | v0.118.1 | `com.termux` | Linux 终端环境，`su` 可用 |
| 命令行工具链 | BusyBox NDK | v1.36.1 | Magisk 模块 | wget/awk/sed/tar 全家桶 |
| 广告拦截 | AdAway | v6.1.4 | `org.adaway` | hosts 层去广告，配合 Systemless hosts |
| 应用/后台管理 | Thanox | v9.6 | `github.tornaco.android.thanos` | 冻结、自启管控、情景模式 |
| 备份 | Neo Backup | 8.33.1 | `com.machiav3lli.backup` | 钛备份精神续作（F-Droid 渠道） |

### 可选扩展（也已装好）

| 类别 | 工具 | 版本 | 包名 | 说明 |
|---|---|---|---|---|
| 自动化 | GKD | v1.13.0 | `li.songe.gkd` | 自动跳开屏广告/弹窗（需导订阅规则） |
| 文件管理 | MT 管理器 | — | `bin.mt.plus` | root 文件管理 + APK 编辑（官网 pan.mt2.cn） |
| 性能工具箱 | Scene | v9.2.11 | `com.omarea.vtools` | 性能调度/帧率记录（官方 download.omarea.com） |
| 防火墙 | AFWall+ | v5.0.0 | `dev.ukanth.ufirewall` | iptables 应用级联网控制 |
| 抓包 | PCAPdroid | v2.0.2 | `com.emanuelef.remote_capture` | 流量监控，root 模式增强 |
| 通话录音 | BCR | v3.11 | `com.chiller3.bcr` | Magisk 模块形态，重启后生效 |
| 后台管理 | 黑阈 Brevent | 4.2.31.1 | `me.piebridge.brevent` | （意外收获）老牌后台冻结 |

## 二、踩坑记录（每条都是真金白银趟出来的）

### 1. Magisk v31.1 批量 su 授权的正确姿势

v31.1 起新装应用的 `su` 请求默认拒绝（不弹窗），必须预授权。UI 里一个个点太慢，
走 sqlite 批量写是正解：

```sh
# 正确 schema：没有 package_name 列！
magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification)
                 VALUES (<uid>,2,0,1,1)"
```

- ❌ 老教程的 `(uid,package_name,policy,until)` 写法会因列名不存在**静默失败**（不报错、不落库）
- uid 用 `pm list packages -U <pkg> | grep -oE "uid:[0-9]+"` 取（`dumpsys package` 在脚本里抓不稳）
- 验证：`magisk --sqlite "SELECT uid FROM policies"`（多列 SELECT 输出格式怪，拼串可能为空，count 最可靠）

### 2. Play Protect「低 targetSdk」拦截链（Android 14+ 装老应用）

症状极具欺骗性：`adb install` 永远卡在 `Performing Streamed Install`，无报错、
logcat 里没有 installd 动作——其实包管理器在**等屏幕上那个 Play Protect 弹窗的裁决**。

弹窗链（文案：「已屏蔽不安全的应用 / 此应用是针对旧版 Android 开发的」）：

1. 点「更多详情」展开 → 文本块**末行出现「仍要安装」可点 span，必须点在那几个字上**（点弹窗中心无效）
2. 终极确认「要安装不安全的应用吗？」要求**指纹或 PIN**——硬边界，只能用户亲手按
3. ⚠️ `--bypass-low-target-sdk-block` 参数**绕不过**（verifier 先于 SDK 检查拦截）
4. 弹窗会过期（放着不理就失效），过期后重跑 `adb install` 重新触发即可；
   建议后台跑安装会话（`terminal background`）保持会话不死，再从容点弹窗

本案触发者：MT 管理器（targetSdk 28）和 Scene（targetSdk 30）。

### 3. Magisk v31.1 的 Systemless hosts 不是开关

设置页「Systemless hosts」行是**跳转入口**（右侧箭头非开关），点一下即安装 hosts 模块，
验证看 `/data/adb/modules/hosts/` 是否存在（`checked=false` 属性没有意义）。
AdAway 的 root 模式依赖它。

### 4. 第三方商店装包被拦

酷安装包弹「禁止安装来自此来源的未知应用」——root 一行免跳设置：

```sh
su -c 'appops set com.coolapk.market REQUEST_INSTALL_PACKAGES allow'
```

### 5. 酷安搜索结果页的两个坑

- **话题卡混排**：搜索结果里「话题卡」（无安装按钮、点了进话题页）和「应用卡」混在一起，
  应用 tab 也不例外
- **卡片标题与按钮错行**：dump 里上一卡的标题配下一卡的按钮是常态，盲点必翻车。
  正解：拿包名走 `coolmarket://apk/<包名>` 直达详情页，或直接 `adb install` 官方 APK

### 6. 官方下载渠道备忘

- MT 管理器：官网 `mt2.cn/download/` → `pan.mt2.cn/apk/<id>`（主包 target28 兼容版另发）
- Scene：`download.omarea.com/scene9/scene_<版本>.apk`（SPA 官网抓不到链接）
- Neo Backup / AdAway：F-Droid `https://f-droid.org/repo/<pkg>_<versionCode>.apk`

## 三、待办

- [ ] GKD 导入订阅规则（推荐 `AIsouler/GKD-Subscription`，GKD 内订阅管理添加）
- [ ] AdAway 打开一次点「启用」加载默认拦截列表
- [ ] BCR 重启后确认通话录音可用
- [ ] Scene 首次启动选择工作模式（ADB/Root）
