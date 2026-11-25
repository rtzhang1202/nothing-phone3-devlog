# KeyPilot Magisk Companion Module（可选附加件）

> **重要声明**：KeyPilot 主体功能以 **LSPosed / Vector Xposed 模块**（`release/KeyPilot.apk`）运行，**完全不依赖本 Magisk 模块**。本目录为可选扩展件，提供：
> 1. `sepolicy.rule`：补充 `/dev/uinput`、`/dev/input/event*` 与 `/data/system/keypilot.json` 访问策略。
> 2. `keypilotd`（路线 F 原生备胎守护进程）：在无 Xposed 环境下通过 `EVIOCGRAB` 独占 `/dev/input/event0`，将 `KEY_VOLUMEUP (115)` 原样透传至 `/dev/uinput`，并在用户态截获 `Essential Key (250)` 执行 500ms/300ms 三手势识别。

## 启用方式（仅当停用 Xposed 时）

默认情况下，`service.sh` 检测到未创建 opt-in 标记文件时**不会启动 `keypilotd`**，以免与主力 Xposed 钩子冲突。如需启用纯 Native 备胎模式：

```sh
su -c "touch /data/system/keypilot_use_daemon"
# 重启设备，或手动测试：
su -c "/data/adb/modules/keypilot_companion/system/bin/keypilotd /dev/input/event0"
```

退出备胎模式（释放 `EVIOCGRAB` 立即还原）：

```sh
su -c "rm -f /data/system/keypilot_use_daemon && pkill -TERM keypilotd"
```

## arm64 交叉编译命令（Android NDK）

```bash
NDK="$ANDROID_SDK_ROOT/ndk/28.2.13676358"
"$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android35-clang" \
  -O2 -Wall -Wextra -fPIE -pie \
  -o magisk/system/bin/keypilotd \
  magisk/src/keypilotd.c
```

## 打包为 Magisk ZIP（可选）

```bash
cd magisk && zip -r ../release/KeyPilot-Magisk-Companion.zip \
  module.prop service.sh sepolicy.rule system/
```
