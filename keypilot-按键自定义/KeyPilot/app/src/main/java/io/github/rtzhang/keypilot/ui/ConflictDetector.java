package io.github.rtzhang.keypilot.ui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Safety Switch #4: Detects conflicting Accessibility services (such as Google Select to Speak,
 * confirmed 2026-01 bug breaking volume keys & camera shutter, and third-party button remappers)
 * plus GCam accessibility UI side effects.
 */
public final class ConflictDetector {

    public static final class Warning {
        public final String level; // "HIGH", "MEDIUM", "INFO"
        public final String title;
        public final String detail;

        public Warning(String level, String title, String detail) {
            this.level = level;
            this.title = title;
            this.detail = detail;
        }
    }

    private static final String[] THIRD_PARTY_REMAPPERS = {
            "io.github.sds100.keymapper",
            "flar2.homebutton",
            "uk.appsby.mapper",
            "com.joaomgcd.autoinput",
            "com.arlosoft.macrodroid",
            "com.threestarsapps.keystar",
            "keysnap",
            "essentialkeytools"
    };

    private ConflictDetector() {
    }

    public static List<Warning> scan(Context ctx) {
        List<Warning> out = new ArrayList<>();
        if (ctx == null) {
            return out;
        }
        try {
            String enabled = Settings.Secure.getString(
                    ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.trim().isEmpty()) {
                return out;
            }
            String lower = enabled.toLowerCase(Locale.US);

            // 1. Google Select to Speak (2026-01 confirmed volume/camera shutter bug)
            if (lower.contains("selecttospeak") || lower.contains("select_to_speak")) {
                out.add(new Warning(
                        "HIGH",
                        "检测到「随选朗读 (Select to Speak)」已开启",
                        "Google 已确认该无障碍服务会导致音量键误调无障碍音量并使相机内音量键快门失效，"
                                + "建议在系统无障碍设置中关闭。"));
            }

            // 2. Third-party accessibility key remappers
            for (String token : THIRD_PARTY_REMAPPERS) {
                if (lower.contains(token)) {
                    out.add(new Warning(
                            "MEDIUM",
                            "检测到其它无障碍按键重映射服务 (" + token + ")",
                            "与 KeyPilot 同时启用可能导致重复触发或抢键冲突，建议仅保留 KeyPilot 或在对应服务内排除重叠按键。"));
                }
            }

            // 3. GCam installed while any accessibility service is active
            if (isPackageInstalled(ctx, "com.google.android.GoogleCamera")) {
                out.add(new Warning(
                        "INFO",
                        "检测到 Google Camera + 已开启无障碍服务",
                        "社区实证：开启任意无障碍服务可能导致 GCam 隐藏快捷缩放按钮并切换为滑杆；"
                                + "KeyPilot P1 核心功能无需任何无障碍权限。"));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static boolean isPackageInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
