# 锁屏密码键盘缩小 1/3 —— 根因与修复

**症状**：硬重启后，锁屏数字密码键盘（键格、数字、删除键、间距）整体缩小到正常尺寸的 **1/3**（键格 240px→80px，数字 54×112→18×38，删除键 72→24）。标题「输入数字密码」、PIN 区、「紧急呼叫」完全正常。`su -c 'stop; start'`（软重启 Android 运行时）后痊愈；再次硬重启必复发。杀 SystemUI、拨 font_scale、拨 `wm density`、杀全部三方应用、禁用全部 Magisk 模块均无效。

## 根因（一句话）

**Nothing 定制版 `PinBouncerKt.PinPad` 用 `DisplayMetrics.DENSITY_DEVICE_STABLE / 160f` 构造自定义 `Density` 覆盖整个键盘子树的 `LocalDensity`；而 zygote 预加载 `DisplayMetrics` 类时 `ro.sf.lcd_density` 尚未由 `init.qcom.rc` 的 `on early-boot` 写入，静态字段 `DENSITY_DEVICE_STABLE` 冻结为默认值 160，导致该 Density=1.0（应为 3.0），dp 按 1:1 渲染 → 全键盘精确 1/3。**

## 证据链

### 1. 几何取证（uiautomator dump 双态 diff）

| 元素 | 病发 | 正常 | 比值 |
|---|---|---|---|
| 键格 | 80×80 px | 240×240 px | 3.00 |
| 横间距 | 45 px | 135 px | 3.00 |
| 竖间距 | 12 px | 36 px | 3.00 |
| 数字"5" | 18×38 | 54×112 | ≈3.0 |
| 删除键 | 24×24 | 72×72 | 3.00 |
| 键盘容器 | 330×356 | 990×1068 | 3.00 |

病发包围盒恰为正常包围盒**以顶边中点为原点缩放 1/3**（顶边 y=1137 两态相同、水平居中不变）；同层的标题/输入区/紧急呼叫尺寸**完全不变** → 只有键盘子树拿到 1/3 换算密度。dp 布局值本身两态一致（80dp 键格、45dp/12dp 间距、330dp 容器），纯 px 换算差 3 倍。

### 2. 排除法

- 全部 Magisk 模块禁用 + 真冷启动 → 依旧病发（模块无罪）
- 杀光全部三方应用 + SystemUI → 依旧病发（应用无罪）
- getprop / settings(system/secure/global) 双态零差异（无持久化毒值）
- 窗口转储 `mFullConfiguration` 双态均 480dpi / fontScale 1.0（WMS 配置无罪）
- FontManagerService / overlay 双态零差异
- `font_scale` 0.9→2.0 → 键盘尺寸纹丝不动（不走 sp 缩放）
- `wm density` 160/320/480 → 两态键盘尺寸均纹丝不动（**关键线索**：键盘不读实时显示密度）
- root 重放 `BOOT_COMPLETED` → 不复病（广播假设证伪）
- 痊愈态杀 SystemUI → 不复病；病发态杀 SystemUI → 不愈（**关键线索**：毒值不在 SystemUI 进程内，在 zygote 遗传的静态状态）
- 软重启（`stop; start`，重启 zygote+system_server）→ 必愈

### 3. 反编译定位（SystemUI.apk / classes5.dex）

`com.android.systemui.bouncer.ui.composable.PinBouncerKt.PinPad`（Nothing 定制版，LineageOS 同名文件无此逻辑）字节码：

```smali
sget v3, Landroid/util/DisplayMetrics;.DENSITY_DEVICE_STABLE:I   # 静态字段
int-to-float v5, v3
const/high16 v8, #float 160.0
div-float/2addr v5, v8                                          # v5 = STABLE/160
...
invoke-static {v5, v9}, Landroidx/compose/ui/unit/DensityKt;.Density:(FF)
# Density(density = DENSITY_DEVICE_STABLE/160f, fontScale = 当前fontScale)
invoke-static {}, ...getLocalDensity...
invoke-virtual {v6, v11}, ProvidableCompositionLocal;.provides   # 覆盖键盘子树
new-instance v13, Landroid/content/res/Configuration;            # 同时拷贝覆盖 LocalConfiguration
```

- 正常态：STABLE=480 → Density(3.0) → 80dp 键格渲染 240px ✓
- 病发态：STABLE=160 → Density(1.0) → 80dp 键格渲染 80px ✓（精确 1/3）
- 该值**不读实时密度**（`wm density` 无效）、**fontScale 走当前值**（`font_scale` 只影响位置不影响尺寸）——与全部实测吻合
- `DENSITY_DEVICE_STABLE` 是静态字段，在 **zygote 预加载 `android.util.DisplayMetrics` 时一次性定格**，此后所有 zygote 子进程（含被杀后重 fork 的 SystemUI）继承 → 解释"杀 SystemUI 不愈"

### 4. 启动竞态

`ro.sf.lcd_density` 不在 build.prop，由 **`/vendor/etc/init/hw/init.qcom.rc:110`** 写入：

```
on early-boot
    exec ... /vendor/bin/init.qcom.early_boot.sh    # 写 vendor.display.lcd_density=480
    setprop ro.sf.lcd_density ${vendor.display.lcd_density}
```

Android init 的 `late-init` 序列中 `trigger zygote-start` **先于** `trigger early-boot`。zygote 预加载 DisplayMetrics 类时属性未就位 → `SystemProperties.getInt("ro.sf.lcd_density", 160)` 冻结 160。软重启时属性早已就位 → 读到 480 痊愈。冷启动时序稳定 → 必发。

## 修复

Magisk 模块 **`density-fix`**（`/data/adb/modules/density-fix/`）：

```
post-fs-data.sh        # init post-fs-data 阶段，早于 zygote-start
#!/system/bin/sh
resetprop ro.sf.lcd_density 480
resetprop vendor.display.lcd_density 480
```

在 zygote 预加载前把属性喂到位，`DENSITY_DEVICE_STABLE` 定格 480。

**验证**：连续两次硬重启（修复前的必发场景），键盘均 54×112 正常尺寸。痊愈标准：数字"5"文本节点 `[603,1489][657,1601]`。

## 附：诊断方法论

1. uiautomator dump 双态几何 diff → 定位"仅子树、精确 1/3、dp 布局同值"
2. 操纵实验矩阵（密度/字体/进程/模块/广播）逐个证伪
3. `wm density` 无响应 → 放弃"实时密度"假设，转向稳定密度/静态缓存
4. dexdump 字节码 → 找到 `DENSITY_DEVICE_STABLE` 实参
5. init rc 时序 → 定位竞态窗口 → post-fs-data 抢跑修复

**测量脚本**：设备端 `/data/local/tmp/measure_keypad.sh`（uiautomator dump + grep 数字"5" bounds）。
