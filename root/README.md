# Nothing Phone (3) ROOT 全记录 —— 流程、要点与踩坑

> 设备：Nothing Phone (3)（A024，代号 Metroid）| 系统：Nothing OS 5.0（Android 17，SDK 37）
> Build：`C5.0-260924-2115` | SoC：骁龙 8s Gen 4（SM8735）| 执行日期：2026-11-07
> 最终状态：**Magisk v31.1 root 成功**，`uid=0(root) context=u:r:magisk:s0`，重启后保持

---

## 一、背景与结论

Nothing Phone (3) 是目前除 Pixel 外最容易 root 的机型之一：bootloader 官方解锁、无等待期、社区固件归档完善。本次在 **Nothing OS 5.0 / Android 17** 这一最新版本上完成了从解锁到 root 持久化的全流程验证——**Android 17 没有改变启动分区布局，root 路径与 Android 15/16 完全一致**，唯一硬性要求是使用 **Magisk v31.1+**（Zygisk 的 Android 17 支持在 v31.0 预览版加入、v31.1 正式稳定）。

## 二、Root 原理（三分钟版）

现代 Android root 的本质是**抢占启动最早期**：

```
Bootloader（校验分区签名，AVB/Verified Boot）
  → 内核 + ramdisk（init_boot 分区）
    → /init ← Magisk 就在这里做手脚！
```

- Magisk 只给 ramdisk 所在分区打补丁（Phone (3) 是 **`init_boot.img`**，不是 `boot.img`——Android 13+ 起 ramdisk 从 boot 拆出），把 `init` 替换为 `magiskinit`；
- `/system` 一个字节不动（**systemless**），所有修改开机时在内存里动态叠加（bind mount），刷回原厂镜像即完全还原；
- `magiskinit` 在启动最早期给 SELinux 策略"给自己放行"（`u:r:magisk:s0` 特权上下文），随后交还控制权给原 init；
- `magiskd` 守护进程负责 su 授权、模块挂载（magic mount）、Zygisk 注入。

Bootloader 解锁为什么是前提：不解锁 = bootloader 只肯启动厂商签名镜像，打过补丁的镜像根本刷不进去。

## 三、完整流程（7 步）

### S1 检测基线
```bash
adb shell getprop ro.build.display.id   # C5.0-260924-2115，记录！
```
确认机型（Metroid/A024）、Android 版本、当前槽位（`ro.boot.slot_suffix`）、电量 ≥60%。

### S2 获取并校验固件镜像
- 来源：[Nothing Archive](https://nothingarchive.tech)（社区索引 Nothing 官方 OTA 服务器），下载与 build 号**逐字一致**的 `*-image-boot.img.7z`；
- 解包得 `init_boot.img`（8MB）/ `boot.img` / `vendor_boot.img` / `recovery.img` / `vbmeta*` 全套救砖件；
- 用官方 `hash.sha256` 逐一比对——**全部 OK 才继续**。

### S3 解锁 bootloader（⚠️ 清空数据）
```bash
adb reboot bootloader
fastboot devices              # 必须看到序列号
fastboot flashing unlock      # 手机音量键选 Unlock → 电源键确认
```
- 前置：开发者选项打开 **OEM unlocking** + USB 调试（恢复出厂后会被重置，要重开）；
- 解锁状态验证：`getprop ro.boot.flash.locked` = 0，`ro.boot.verifiedbootstate` = orange（开机橙色警告 = 正常）；
- 权威查询（进 bootloader 后）：`fastboot flashing get_unlock_ability` = 1 表示 OEM 解锁开关已开。

### S4 安装 Magisk + 推送镜像
```bash
adb install -r Magisk-v31.1.apk      # 只从 GitHub 官方 Release 下载
adb push init_boot.img /sdcard/Download/
```

### S5 打补丁（Magisk App 内）
Magisk → 安装 → **Patch firmware / image** → Select from storage → 选 `init_boot.img` → 确定
→ 显示"安装 - 完成！"，产物 `magisk_patched-31100_*.img` 在 Download 目录。

### S6 刷入
```bash
adb reboot bootloader
fastboot flash init_boot magisk_patched-31100_*.img
fastboot reboot
```
**铁律**：绝不加 `--disable-verity/--disable-verification`、绝不动 `vbmeta`（社区案例里所有 bootloop 都与此有关）。

### S7 验证与持久化
```bash
adb shell su -c id
# uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0  ✅
```
- **Magisk v31.1 授权模型变了**：取消了传统的 su 弹窗！首次需在 Magisk → 超级用户 → 悬浮按钮"授予超级用户权限" → 勾选 **Shell (com.android.shell)** 预授权；
- Magisk → 安装 → **直接安装（推荐）** → 重启：完成原厂镜像备份（`/data/adb`），root 持久化。

## 四、要点清单

1. **补丁 `init_boot` 不是 `boot`**——Phone (3) 的 boot.img 只含内核，ramdisk 在 init_boot（Magisk App 的 Ramdisk 显示"是"即确认）；
2. **镜像 build 号必须逐字匹配**——跨 build 刷入必 bootloop；救砖就靠留底的原版 `init_boot.img`（一条 `fastboot flash init_boot init_boot.img` 恢复）；
3. **vbmeta 不要碰**——保持 `KEEPVERITY=true` 反而稳；
4. **Magisk 必须 v31.1+**——Android 17 的 Zygisk 支持是 v31.1 才稳定；旧版遇 Android 16 QPR2+ 的新 SELinux 策略格式会直接 bootloop 回 fastboot；
5. **KernelSU 路线用 Next（LKM 模式）**——Phone (3) 无验证过的定制内核，KSU Next 对 `init_boot` 打补丁即可（管理器显示 "Working [LKM]"）；
6. **OTA 后要重打**——还原原厂镜像 → OTA → 用新 build 的 init_boot 重走 S5-S6；或尝试 Magisk"安装到未使用的槽位"（A/B 机型）。

## 五、踩坑全记录（都是实战换来的）

### 坑 1：fastboot 模式下电脑"看不见"手机（本次最大拦路虎）
**现象**：`adb reboot bootloader` 后手机进了 bootloader，但 `fastboot devices` 一直为空。USB 枚举里出现 `VID_18D1&PID_D00D` 状态 Error 的未知设备——**Windows 没有 fastboot 模式的驱动**（ADB 模式靠系统自带 WinUSB 自动绑定，fastboot 模式不享受这个待遇）。

**解法三部曲**：
1. **Zadig 2.9** 给 `18D1:D00D` 绑 WinUSB → 驱动装好（Status: OK），但 fastboot **还是**看不见！
2. 定位根因：Zadig 在注册表写入的设备接口 GUID 是它自己生成的 `{17488D48-…}`，而 fastboot 只认 Google 的 **`{F72FE0D4-CBCB-407d-8814-9ED673D0DD6B}`**（GUID_DEVINTERFACE_ADB）；
3. 提权补注册表 + 重启设备：
   ```bat
   reg add "HKLM\SYSTEM\CurrentControlSet\Enum\USB\VID_18D1&PID_D00D\00019157L001724\Device Parameters" /v DeviceInterfaceGUIDs /t REG_MULTI_SZ /d "{F72FE0D4-CBCB-407d-8814-9ED673D0DD6B}\0{17488D48-7413-4D46-92C2-9F31B26DD9D8}" /f
   pnputil /restart-device "USB\VID_18D1&PID_D00D\00019157L001724"
   ```
   → `fastboot devices` 立刻识别 ✅（脚本见 [fix_fastboot_guid.bat](fix_fastboot_guid.bat)）

**备选方案**（如果不想折腾驱动）：usbipd-win 把设备透传给 WSL2 用 Linux 版 fastboot（Linux 免驱动）。

### 坑 2：Google USB 驱动下载（国内网络）
`dl.google.com` 直连和走代理都极慢/超时。两个可用通道：
- **腾讯镜像**：`https://mirrors.cloud.tencent.com/AndroidSDK/usb_driver_r13-windows.zip`（秒下）；
- **GitHub release 资产**绕行：api.github.com 可达但 `releases/download` 被卡 → 用 **API 资产端点**下载：
  ```bash
  curl -H "Accept: application/octet-stream" \
    https://api.github.com/repos/<owner>/<repo>/releases/assets/<asset_id>
  ```

### 坑 3：改 inf 加 PID 会破坏签名
想给 Google 驱动的 inf 补 `PID_D00D` 条目？改完 inf 的哈希就和 `.cat` 签名目录对不上，`pnputil` 直接拒装（重签需要 WDK 的 inf2cat）。所以走 Zadig 而不是改 inf。

### 坑 4：Magisk App 的 UI 自动化（无 root 的 PC 端 adb 驱动）
用 adb 驱动 Magisk 打补丁时的连环坑：
- **uiautomator dump 抓不到抽屉/弹层**（侧边栏抽屉是独立窗口层）——必须 `screencap` 截图 + 视觉定位坐标；
- **`adb push` 保留文件原始时间戳**——老时间戳的 `init_boot.img` 不会出现在文件选择器"最近"列表顶部（被当成旧文件排到深处）；`touch` 一下改时间戳即出现在最前；
- **`input text` 的下划线会变成破折号**（`init_boot` 打成 `init——`）——文件名/路径避免下划线，或改用导航点击；
- **屏幕息屏**打断操作链——`settings put global stay_on_while_plugged_in 7`（充电时常亮）专治；
- **界面动画后坐标漂移**——点击必须用"新鲜 dump"的坐标，隔了几秒的坐标会点偏。

### 坑 5：Magisk v31.1 的授权模型变化
v31.1 移除了传统的 su 弹窗（为适配 Headless System User 模式），改为**超级用户页面预授权**。`adb shell su -c id` 首次返回 Permission denied 不是 root 失败，去 Magisk → 超级用户 → "授予超级用户权限" FAB → 勾选 Shell 即可。

### 坑 6：别被 `getprop sys.oem_unlock_allowed` 误导
Nothing 机型上该属性为空（未暴露），**不代表 OEM 解锁没开**。权威判断是 bootloader 里的 `fastboot flashing get_unlock_ability`（=1 即可解锁）。

## 六、工具箱（一次配置长期受益）

| 工具 | 用途 | 备注 |
|---|---|---|
| platform-tools 37.x | adb / fastboot | Google 官方 |
| Magisk v31.1 (31100) | root + 模块框架 | GitHub Release 官方下载 |
| Zadig 2.9 | fastboot 驱动绑定 | libwdi 项目 |
| fix_fastboot_guid.bat | GUID 补丁 | 本仓库提供 |
| py7zr | 解 7z 固件包 | 或 7-Zip |
| payload-dumper-go | 从 OTA payload.bin 提取镜像 | 备用 |

## 七、救砖预案

```bash
# 软砖/bootloop：刷回原版即复活
fastboot flash init_boot init_boot.img      # 原版留底！

# 彻底回厂（骁龙机型全分区）
fastboot flash boot        boot.img
fastboot flash init_boot   init_boot.img
fastboot flash vendor_boot vendor_boot.img
fastboot flash recovery    recovery.img
fastboot flash vbmeta      vbmeta.img
fastboot reboot
```

**铁律**：原版 `init_boot.img` 永远留底；`fastboot flashing lock` 前必须全机原厂（带补丁上锁 = 硬砖）；镜像与 build 号逐字核对。

## 八、社区成功案例（时间线佐证）

- XDA《[GUIDE] Root Nothing Phone 3 with Magisk (Android 14/15)》——完整教程 + 作者实测 "working like a charm"；
- XDA《Root + Ungoogle + microG on any Stock ROM》——KernelSU Next (LKM) 实测 "Working [LKM]"，免配置过 Base Integrity；
- Reddit r/androidroot（2026.1）——Magisk "without a hitch"，银行 App 正常；
- Qiita 日本开发者完整记录（2025.8）——含指定槽位刷入细节（`fastboot flash init_boot_b`）；
- Magisk 仓库 Issue #9186——早期踩坑原始案例（后证实为补错 boot.img / 动 vbmeta 所致）。

## 九、下一步

1. 模块链：Zygisk → LSPosed → DisableFlagSecure（解除 FLAG_SECURE 截屏封锁）；
2. GUI Harness 部署（智能体手机计划 P1）。

---

*本文档为 Nothing Phone (3) 开发日志的 ROOT 篇。所有命令均经真机验证。*
