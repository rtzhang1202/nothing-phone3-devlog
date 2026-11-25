package io.github.rtzhang.keypilot.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime read-only probe for Nothing OS 5.1 official Essential Key remapping settings
 * (Safety Switch #6). Never writes settings or mutates system state.
 */
public final class OfficialRemapDetector {

    private static final String[] CANDIDATE_SECURE_KEYS = {
            "nt_essential_key_action",
            "nt_essential_key_remap",
            "nt_essential_key_single_click",
            "nt_essential_key_custom_action",
            "essential_key_single_click_action",
            "nt_essential_key_activate_single_tap"
    };

    private static final AtomicBoolean sProbed = new AtomicBoolean(false);
    private static volatile String sDetectedKey;
    private static volatile String sDetectedValue;

    private OfficialRemapDetector() {
    }

    public static void probeOnce(Context ctx) {
        if (ctx == null || !sProbed.compareAndSet(false, true)) {
            return;
        }
        try {
            ContentResolver cr = ctx.getContentResolver();
            if (cr == null) return;
            for (String key : CANDIDATE_SECURE_KEYS) {
                try {
                    String val = Settings.Secure.getString(cr, key);
                    if (val != null && !val.trim().isEmpty() && !"0".equals(val.trim())
                            && !"default".equalsIgnoreCase(val.trim())) {
                        sDetectedKey = key;
                        sDetectedValue = val.trim();
                        HookLog.report(ctx, "OS 5.1 OfficialRemapDetector hit: secure."
                                + key + "=" + sDetectedValue
                                + " (consider officialRemapPolicy=yield_single_click if conflict)");
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
            HookLog.i("OS 5.1 OfficialRemapDetector: no active official remap setting detected");
        } catch (Throwable t) {
            HookLog.e("OfficialRemapDetector probe error", t);
        }
    }

    public static boolean isOfficialRemapDetected() {
        return sDetectedKey != null;
    }

    public static String getDetectedSummary() {
        return sDetectedKey != null ? (sDetectedKey + "=" + sDetectedValue) : null;
    }
}
