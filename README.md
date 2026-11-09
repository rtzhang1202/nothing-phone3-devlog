# Nothing Phone (3) 开发日志 📱🔧

Nothing Phone (3)（A024 / Metroid，骁龙 8s Gen 4）的探索开发全过程记录。
从系统底层到上层应用，一步一步把这台"纯透明"的手机变成个人智能体实验平台。

## 目录

| 章节 | 内容 | 状态 |
|---|---|---|
| [**ROOT 篇**](root/README.md) | 解锁 bootloader + Magisk root 全流程：原理、7 步实操、6 大踩坑实录、救砖预案 | ✅ 已完成 |
| [**工具箱篇**](tools/README.md) | ROOT 后工具箱：终端/去广告/备份/自动化/抓包全家桶 + Play Protect 拦截链等 6 大踩坑 | ✅ 已完成 |
| [**系统更新页修复**](system-update-fix/README.md) | 更新日志/固件信息丢失根因（GMS gOTA 双链路断供）+ NothingUpdateFix 模块修复，真机验证完成 | ✅ 已完成 |
| 模块篇 | Zygisk / LSPosed / DisableFlagSecure 模块链 | 📝 计划中 |
| GUI Harness 篇 | 手机端智能体调度中心部署 | 📝 计划中 |
| PC 助力篇 | WSL 后端 + Tailscale 组网 + 任务规划接管 | 📝 计划中 |

## 设备档案

| 项 | 值 |
|---|---|
| 机型 | Nothing Phone (3)，型号 A024，代号 Metroid |
| SoC | 骁龙 8s Gen 4（SM8735），台积电 4nm |
| 系统 | Nothing OS 5.0（Android 17，SDK 37） |
| 内核 | 6.6.127-android15-8（GKI） |
| 分区 | Android 13+ 标准：ramdisk 独立于 `init_boot` |
| Bootloader | ✅ 已解锁（2026-11-07） |
| Root | ✅ Magisk v31.1（31100），systemless |

## 免责声明

刷机有风险：解锁 bootloader 会清空数据、Widevine 降为 L3、影响保修。
本仓库内容为个人学习探索记录，照做前请确认理解每一步的含义与后果。
