package io.github.rtzhang.keypilot.hook;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.rtzhang.keypilot.config.ActionSpec;
import io.github.rtzhang.keypilot.config.ActionType;
import io.github.rtzhang.keypilot.config.GestureType;
import io.github.rtzhang.keypilot.config.KeyId;
import io.github.rtzhang.keypilot.config.KeyPilotConfig;
import io.github.rtzhang.keypilot.config.SceneTemplates;

/**
 * Executes mapped actions inside system_server (uid=1000) on a dedicated background
 * HandlerThread with cleared Binder calling identity. Every path is fail-safe.
 * Supports all v1 actions + v2 P1 context-aware actions.
 */
public final class ActionExecutor {

    private static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;

    private static volatile Handler sExecHandler;
    private static volatile Handler sMainHandler;

    private static final AtomicBoolean sTorchCallbackRegistered = new AtomicBoolean(false);
    private static volatile boolean sTorchEnabled = false;
    private static volatile String sTorchCameraId;
    private static volatile long sLastTorchSetUptimeMs = 0L;

    // Track PTT mic mute state for B3 press-and-hold restoration
    private static volatile boolean sPttPrevMuted = false;

    private ActionExecutor() {
    }

    private static Handler getExecHandler() {
        Handler h = sExecHandler;
        if (h == null) {
            synchronized (ActionExecutor.class) {
                h = sExecHandler;
                if (h == null) {
                    HandlerThread ht = new HandlerThread("KeyPilot-Exec");
                    ht.start();
                    h = new Handler(ht.getLooper());
                    sExecHandler = h;
                }
            }
        }
        return h;
    }

    private static Handler getMainHandler() {
        Handler h = sMainHandler;
        if (h == null) {
            synchronized (ActionExecutor.class) {
                h = sMainHandler;
                if (h == null) {
                    h = new Handler(Looper.getMainLooper());
                    sMainHandler = h;
                }
            }
        }
        return h;
    }

    /** v1 compatible overload. */
    public static void execute(final KeyId key, final GestureType gesture, final ActionSpec spec) {
        execute(key, gesture, spec, ContextSnapshot.EMPTY, "global");
    }

    /** v2 context-aware execution entry point. */
    public static void execute(final KeyId key,
                               final GestureType gesture,
                               final ActionSpec spec,
                               final ContextSnapshot snap,
                               final String source) {
        if (spec == null || spec.isSystem() || spec.isInherit()) {
            return;
        }
        final ContextSnapshot ctxSnap = snap != null ? snap : ContextSnapshot.EMPTY;
        final String src = source != null ? source : "global";

        getExecHandler().post(new Runnable() {
            @Override
            public void run() {
                Context ctx = ConfigBridge.getSystemContext();
                KeyPilotConfig cfg = ConfigBridge.getConfig();
                if (ctx == null) {
                    HookLog.i("ActionExecutor: no system Context, skipping " + spec.type.id);
                    return;
                }
                long token = Binder.clearCallingIdentity();
                try {
                    HookLog.report(ctx, "EXEC [" + key.id + "." + gesture.id + "] (resolved="
                            + src + ", fg=" + ctxSnap.topPackage + ") -> " + spec.summary());
                    if (cfg.hapticFeedback && spec.type != ActionType.NONE) {
                        vibrateLight(ctx);
                    }
                    String toastMsg = dispatchAction(ctx, key, gesture, spec, ctxSnap);
                    if (cfg.showToast && toastMsg != null && !toastMsg.isEmpty()) {
                        if (spec.type == ActionType.RINGER_CYCLE && spec.edgeToast) {
                            showEdgeToastSafe(ctx, "KeyPilot: " + toastMsg);
                        } else {
                            showToastSafe(ctx, "KeyPilot: " + toastMsg);
                        }
                    }
                } catch (Throwable t) {
                    HookLog.e("ActionExecutor failed [" + key.id + "." + gesture.id
                            + " -> " + spec.type.id + "]", t);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        });
    }

    /** Restores microphone mute state when a B3 PTT long-press is released. */
    public static void releasePttMicMute() {
        getExecHandler().post(new Runnable() {
            @Override
            public void run() {
                Context ctx = ConfigBridge.getSystemContext();
                if (ctx == null) return;
                long token = Binder.clearCallingIdentity();
                try {
                    AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        am.setMicrophoneMute(sPttPrevMuted);
                        HookLog.i("mic_mute_ptt: released -> restored muted=" + sPttPrevMuted);
                    }
                } catch (Throwable t) {
                    HookLog.e("releasePttMicMute failed", t);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        });
    }

    private static String dispatchAction(Context ctx,
                                         KeyId key,
                                         GestureType gesture,
                                         ActionSpec spec,
                                         ContextSnapshot snap) throws Throwable {
        switch (spec.type) {
            case NONE:
            case SYSTEM:
            case INHERIT:
                return null;
            case LAUNCH_APP:
                return execLaunchApp(ctx, spec);
            case LAUNCH_ACTIVITY:
                return execLaunchActivity(ctx, spec);
            case CUSTOM_INTENT:
                return execCustomIntent(ctx, spec);
            case FLASHLIGHT:
                return execFlashlight(ctx);
            case SCREENSHOT:
                return execScreenshot(ctx);
            case PARTIAL_SCREENSHOT:
                return execPartialScreenshot(ctx);
            case CAMERA:
                return execCamera(ctx, spec);
            case CAMERA_SHUTTER:
                return execCameraShutter(ctx, spec, snap);
            case VOICE_ASSISTANT:
                return execVoiceAssistant(ctx);
            case MEDIA_PLAY_PAUSE:
                return execMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "播放 / 暂停");
            case MEDIA_PREV:
                return execMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "上一曲");
            case MEDIA_NEXT:
                return execMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT, "下一曲");
            case MEDIA_TRANSPORT:
                return execMediaTransport(ctx, spec);
            case VOLUME_UP:
                return execVolumeAdjust(ctx, AudioManager.ADJUST_RAISE, "音量 +");
            case VOLUME_DOWN:
                return execVolumeAdjust(ctx, AudioManager.ADJUST_LOWER, "音量 −");
            case RINGER_CYCLE:
                return execRingerCycle(ctx);
            case NAV_BACK:
                injectKeyTap(ctx, KeyEvent.KEYCODE_BACK, 0, false);
                return null;
            case NAV_HOME:
                execHome(ctx);
                return null;
            case NAV_RECENTS:
                injectKeyTap(ctx, KeyEvent.KEYCODE_APP_SWITCH, 0, false);
                return null;
            case OPEN_SETTINGS:
                return execOpenSettings(ctx);
            case LOCK_SCREEN:
                return execLockScreen(ctx);
            case KEY_INJECT:
                return execKeyInject(ctx, spec);
            case DEEP_LINK:
                return execDeepLink(ctx, spec.dataUri, spec.packageName, spec.label);
            case IN_APP_PRESET:
                return execInAppPreset(ctx, spec, snap);
            case EXPAND_NOTIFICATIONS:
                return execStatusBarExpand(ctx, false);
            case EXPAND_QS:
                return execStatusBarExpand(ctx, true);
            case CONTEXT_VAR_BROADCAST:
                return execContextVarBroadcast(ctx, key, gesture, spec, snap);
            case MIC_MUTE_PTT:
                return execMicMutePtt(ctx, gesture);
            default:
                return null;
        }
    }

    // ------------------------------------------------------------ v2 P1 actions

    /**
     * Three-tier camera shutter strategy (Safety Switch #5 + GCam/Nothing/OpenCamera matrix):
     * - Tier 1 (system_key / auto): OpenCamera/Aperture -> KEYCODE_CAMERA(27);
     *   Google Camera -> Bypass KEYCODE_VOLUME_DOWN(25) (GCam ignores 27!);
     *   Nothing Camera / generic -> Bypass KEYCODE_VOLUME_DOWN(25) unless explicit key configured.
     * - Tier 2/3 (a11y_node / a11y_coord): reserved for P2 AccessBridge; degrades gracefully to Tier 1 in P1.
     */
    private static String execCameraShutter(Context ctx, ActionSpec spec, ContextSnapshot snap) {
        String tier = spec.shutterTier != null ? spec.shutterTier : ActionSpec.SHUTTER_TIER_AUTO;
        String prefKey = spec.shutterKey != null ? spec.shutterKey : ActionSpec.SHUTTER_KEY_AUTO;
        int pulseMs = spec.pulseMs;
        String fgPkg = snap != null ? snap.topPackage : "";

        if (ActionSpec.SHUTTER_TIER_A11Y_NODE.equals(tier)
                || ActionSpec.SHUTTER_TIER_A11Y_COORD.equals(tier)) {
            HookLog.i("camera_shutter: P2 A11y tier (" + tier
                    + ") not installed in P1 -> graceful fallback to Tier 1 system_key");
        }

        int keyCode;
        int scanCode;
        boolean needBypass;

        if (ActionSpec.SHUTTER_KEY_CAMERA.equals(prefKey)) {
            keyCode = KeyEvent.KEYCODE_CAMERA;
            scanCode = 0;
            needBypass = false;
        } else if (ActionSpec.SHUTTER_KEY_VOLUME_UP.equals(prefKey)) {
            keyCode = KeyEvent.KEYCODE_VOLUME_UP;
            scanCode = KeyId.VOLUME_UP.scanCode;
            needBypass = true;
        } else if (ActionSpec.SHUTTER_KEY_VOLUME_DOWN.equals(prefKey)) {
            keyCode = KeyEvent.KEYCODE_VOLUME_DOWN;
            scanCode = KeyId.VOLUME_DOWN.scanCode;
            needBypass = true;
        } else {
            // AUTO strategy grounded in V2-CONTEXT-RESEARCH.md §3 + Nothing Phone (3) real-device proof:
            // Nothing Camera, Open Camera, and Aperture natively support KEYCODE_CAMERA (27) without
            // conflicting with volume-key zoom settings; Google Camera ignores 27 and uses Bypass Vol-.
            if ("com.nothing.camera".equals(fgPkg)
                    || "net.sourceforge.opencamera".equals(fgPkg)
                    || "org.lineageos.aperture".equals(fgPkg)) {
                keyCode = KeyEvent.KEYCODE_CAMERA;
                scanCode = 0;
                needBypass = false;
            } else {
                // Google Camera (ignores KEYCODE_CAMERA=27), Samsung, generic cameras
                keyCode = KeyEvent.KEYCODE_VOLUME_DOWN;
                scanCode = KeyId.VOLUME_DOWN.scanCode;
                needBypass = true;
            }
        }

        injectKeyPulse(ctx, keyCode, scanCode, pulseMs, needBypass);
        HookLog.i("camera_shutter: fg=" + fgPkg + " tier=" + tier
                + " -> injected keyCode=" + keyCode + " pulse=" + pulseMs + "ms bypass=" + needBypass);
        return null; // Never pop a Toast over the camera viewfinder!
    }

    private static String execKeyInject(Context ctx, ActionSpec spec) {
        if (spec.keyCode <= 0) {
            return "未配置有效按键码";
        }
        int kc = spec.keyCode;
        int sc = 0;
        boolean bypass = false;
        if (kc == KeyEvent.KEYCODE_VOLUME_UP) {
            sc = KeyId.VOLUME_UP.scanCode;
            bypass = true;
        } else if (kc == KeyEvent.KEYCODE_VOLUME_DOWN) {
            sc = KeyId.VOLUME_DOWN.scanCode;
            bypass = true;
        } else if (kc == KeyEvent.KEYCODE_POWER) {
            sc = KeyId.POWER.scanCode;
            bypass = true;
        }
        for (int i = 0; i < spec.repeat; i++) {
            injectKeyPulse(ctx, kc, sc, spec.pulseMs, bypass);
            if (i + 1 < spec.repeat) {
                SystemClock.sleep(50L);
            }
        }
        HookLog.i("key_inject: keyCode=" + kc + " pulse=" + spec.pulseMs + "ms repeat=" + spec.repeat);
        return null;
    }

    private static String execMediaTransport(Context ctx, ActionSpec spec) {
        String cmd = spec.mediaCommand != null ? spec.mediaCommand : ActionSpec.MEDIA_CMD_TOGGLE;
        if (!spec.simulateMediaButton) {
            try {
                MediaSessionManager msm = (MediaSessionManager)
                        ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
                if (msm != null) {
                    List<MediaController> controllers = msm.getActiveSessions(null);
                    MediaController target = pickMediaController(controllers, spec.packageName);
                    if (target != null) {
                        MediaController.TransportControls tc = target.getTransportControls();
                        if (ActionSpec.MEDIA_CMD_PLAY.equals(cmd)) {
                            tc.play();
                            return "媒体：播放 (" + target.getPackageName() + ")";
                        } else if (ActionSpec.MEDIA_CMD_PAUSE.equals(cmd)) {
                            tc.pause();
                            return "媒体：暂停 (" + target.getPackageName() + ")";
                        } else if (ActionSpec.MEDIA_CMD_NEXT.equals(cmd)) {
                            tc.skipToNext();
                            return "媒体：下一曲";
                        } else if (ActionSpec.MEDIA_CMD_PREV.equals(cmd)) {
                            tc.skipToPrevious();
                            return "媒体：上一曲";
                        } else if (ActionSpec.MEDIA_CMD_SEEK_FORWARD.equals(cmd)
                                || ActionSpec.MEDIA_CMD_SEEK_BACK.equals(cmd)) {
                            PlaybackState ps = target.getPlaybackState();
                            long cur = ps != null ? ps.getPosition() : 0L;
                            long delta = ActionSpec.MEDIA_CMD_SEEK_FORWARD.equals(cmd)
                                    ? spec.seekMs : -spec.seekMs;
                            tc.seekTo(Math.max(0L, cur + delta));
                            return ActionSpec.MEDIA_CMD_SEEK_FORWARD.equals(cmd)
                                    ? ("快进 " + (spec.seekMs / 1000) + "s")
                                    : ("后退 " + (spec.seekMs / 1000) + "s");
                        } else {
                            PlaybackState ps = target.getPlaybackState();
                            boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
                            if (playing) {
                                tc.pause();
                                return "媒体：暂停";
                            } else {
                                tc.play();
                                return "媒体：播放";
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                HookLog.i("media_transport MediaSessionManager fallback to media key: " + t);
            }
        }

        // Fallback (or simulateMediaButton=true for Spotify compatibility)
        if (ActionSpec.MEDIA_CMD_NEXT.equals(cmd)) {
            return execMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT, "下一曲");
        } else if (ActionSpec.MEDIA_CMD_PREV.equals(cmd)) {
            return execMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "上一曲");
        } else if (ActionSpec.MEDIA_CMD_PLAY.equals(cmd)) {
            return execMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY, "播放");
        } else if (ActionSpec.MEDIA_CMD_PAUSE.equals(cmd)) {
            return execMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE, "暂停");
        } else if (ActionSpec.MEDIA_CMD_SEEK_FORWARD.equals(cmd)) {
            return execMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "快进");
        } else if (ActionSpec.MEDIA_CMD_SEEK_BACK.equals(cmd)) {
            return execMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_REWIND, "后退");
        }
        return execMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "播放 / 暂停");
    }

    private static MediaController pickMediaController(List<MediaController> list, String targetPkg) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        if (targetPkg != null && !targetPkg.isEmpty()) {
            for (MediaController mc : list) {
                if (targetPkg.equals(mc.getPackageName())) {
                    return mc;
                }
            }
        }
        // Prefer actively playing session first
        for (MediaController mc : list) {
            PlaybackState ps = mc.getPlaybackState();
            if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                return mc;
            }
        }
        return list.get(0);
    }

    private static String execDeepLink(Context ctx, String uri, String pkg, String label) {
        if (uri == null || uri.trim().isEmpty()) {
            return "未配置 Deep Link URI";
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri.trim()));
        if (pkg != null && !pkg.trim().isEmpty()) {
            intent.setPackage(pkg.trim());
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (ctx.getPackageManager().resolveActivity(intent, 0) == null) {
                // Retry without explicit package filter if scoped resolution failed
                if (pkg != null) {
                    intent.setPackage(null);
                    if (ctx.getPackageManager().resolveActivity(intent, 0) == null) {
                        HookLog.i("deep_link: unresolvable URI=" + uri);
                        return "未安装支持该链接的应用";
                    }
                } else {
                    HookLog.i("deep_link: unresolvable URI=" + uri);
                    return "未安装支持该链接的应用";
                }
            }
        } catch (Throwable ignored) {
        }
        startActivityAsCurrentUser(ctx, intent);
        return label != null ? label : "打开链接";
    }

    private static String execInAppPreset(Context ctx, ActionSpec spec, ContextSnapshot snap) {
        SceneTemplates.BuiltInPreset p = SceneTemplates.findPreset(spec.presetId);
        if (p == null) {
            HookLog.i("in_app_preset: unknown presetId=" + spec.presetId);
            return "未知内置预设";
        }
        if ("camera_shutter".equals(p.id)) {
            return execCameraShutter(ctx,
                    ActionSpec.cameraShutter(ActionSpec.SHUTTER_TIER_AUTO,
                            ActionSpec.SHUTTER_KEY_AUTO, 35, false), snap);
        }
        if (p.uri != null) {
            return execDeepLink(ctx, p.uri, p.packageName, p.title);
        }
        if (p.packageName != null && p.className != null) {
            return execLaunchActivity(ctx, ActionSpec.launchActivity(p.packageName, p.className, p.title));
        }
        if (p.packageName != null) {
            return execLaunchApp(ctx, ActionSpec.launchApp(p.packageName, p.title));
        }
        return p.title;
    }

    private static String execPartialScreenshot(Context ctx) {
        // Attempt 1: ScreenshotHelper TAKE_SCREENSHOT_SELECTED_REGION (=2)
        try {
            Class<?> helperCls = Class.forName("com.android.internal.util.ScreenshotHelper");
            Constructor<?> ctor = helperCls.getConstructor(Context.class);
            Object helper = ctor.newInstance(ctx);
            for (Method m : helperCls.getMethods()) {
                if (!"takeScreenshot".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 6 && pt[0] == int.class && pt[4] == Handler.class) {
                    m.invoke(helper, 2, true, true, 5, getExecHandler(), null);
                    HookLog.i("partial_screenshot: triggered via ScreenshotHelper(6-arg region)");
                    return null;
                }
            }
        } catch (Throwable t) {
            HookLog.i("partial_screenshot region helper skipped, falling back to full screenshot: " + t);
        }
        // Fallback: standard full screenshot (always works reliably)
        return execScreenshot(ctx);
    }

    private static String execStatusBarExpand(Context ctx, boolean quickSettings) {
        try {
            Object sb = ctx.getSystemService("statusbar");
            if (sb != null) {
                Method m = sb.getClass().getMethod(
                        quickSettings ? "expandSettingsPanel" : "expandNotificationsPanel");
                m.invoke(sb);
                HookLog.i("status_bar: expanded " + (quickSettings ? "QS" : "Notifications"));
                return null;
            }
        } catch (Throwable t) {
            HookLog.e("execStatusBarExpand failed", t);
        }
        return quickSettings ? "展开快速设置失败" : "下拉通知栏失败";
    }

    private static String execContextVarBroadcast(Context ctx,
                                                  KeyId key,
                                                  GestureType gesture,
                                                  ActionSpec spec,
                                                  ContextSnapshot snap) {
        String action = spec.intentAction != null
                ? spec.intentAction : "io.github.rtzhang.keypilot.CONTEXT_TRIGGER";
        String fgPkg = snap != null ? snap.topPackage : "";
        String fgAct = snap != null ? snap.topActivity : "";
        String fgAppName = resolveAppLabel(ctx, fgPkg);

        Intent intent = new Intent(action);
        if (spec.packageName != null) {
            intent.setPackage(spec.packageName);
        }
        intent.putExtra("keypilot_fg_package", fgPkg);
        intent.putExtra("keypilot_fg_app_name", fgAppName);
        intent.putExtra("keypilot_fg_activity", fgAct);
        intent.putExtra("keypilot_key", key.id);
        intent.putExtra("keypilot_gesture", gesture.id);

        for (Map.Entry<String, Object> e : spec.extras.entrySet()) {
            Object val = e.getValue();
            if (val instanceof String) {
                String s = ((String) val)
                        .replace("${fg_pkg}", fgPkg)
                        .replace("${fg_app_name}", fgAppName)
                        .replace("${fg_activity}", fgAct);
                intent.putExtra(e.getKey(), s);
            } else if (val instanceof Boolean) {
                intent.putExtra(e.getKey(), (Boolean) val);
            } else if (val instanceof Integer) {
                intent.putExtra(e.getKey(), (Integer) val);
            } else if (val instanceof Long) {
                intent.putExtra(e.getKey(), (Long) val);
            } else if (val != null) {
                intent.putExtra(e.getKey(), String.valueOf(val));
            }
        }
        sendBroadcastAsCurrentUser(ctx, intent);
        HookLog.i("context_var_broadcast: action=" + action + " fg=" + fgPkg + " (" + fgAppName + ")");
        return "已发送变量广播 (" + fgPkg + ")";
    }

    private static String resolveAppLabel(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return "";
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            return cs != null ? cs.toString() : pkg;
        } catch (Throwable ignored) {
            return pkg;
        }
    }

    private static String execMicMutePtt(Context ctx, GestureType gesture) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "音频服务不可用";
        boolean curMuted = am.isMicrophoneMute();
        if (gesture == GestureType.LONG) {
            sPttPrevMuted = curMuted;
            am.setMicrophoneMute(false);
            HookLog.i("mic_mute_ptt: LONG PTT active (unmuted; prev=" + curMuted + ")");
            return "麦克风已开启 (松手恢复)";
        }
        boolean next = !curMuted;
        am.setMicrophoneMute(next);
        HookLog.i("mic_mute_ptt: toggled muted=" + next);
        return next ? "麦克风已静音" : "麦克风已取消静音";
    }

    // --------------------------------------------------------- v1 core actions

    private static String execLaunchApp(Context ctx, ActionSpec spec) {
        if (spec.packageName == null) {
            HookLog.i("launch_app: missing packageName");
            return "未指定应用包名";
        }
        Intent intent = null;
        try {
            intent = ctx.getPackageManager().getLaunchIntentForPackage(spec.packageName);
        } catch (Throwable ignored) {
        }
        if (intent == null) {
            intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setPackage(spec.packageName);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivityAsCurrentUser(ctx, intent);
        return "启动 " + (spec.label != null ? spec.label : spec.packageName);
    }

    private static String execLaunchActivity(Context ctx, ActionSpec spec) {
        if (spec.packageName == null || spec.className == null) {
            HookLog.i("launch_activity: missing packageName or className");
            return "Activity 参数不完整";
        }
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(spec.packageName, spec.className));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAsCurrentUser(ctx, intent);
        return "启动 " + spec.className;
    }

    private static String execCustomIntent(Context ctx, ActionSpec spec) {
        Intent intent = new Intent();
        if (spec.intentAction != null) {
            intent.setAction(spec.intentAction);
        }
        if (spec.packageName != null && spec.className != null) {
            intent.setComponent(new ComponentName(spec.packageName, spec.className));
        } else if (spec.packageName != null) {
            intent.setPackage(spec.packageName);
        }
        if (spec.dataUri != null) {
            intent.setData(Uri.parse(spec.dataUri));
        }
        for (Map.Entry<String, Object> e : spec.extras.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v instanceof Boolean) {
                intent.putExtra(k, (Boolean) v);
            } else if (v instanceof Integer) {
                intent.putExtra(k, (Integer) v);
            } else if (v instanceof Long) {
                intent.putExtra(k, (Long) v);
            } else if (v instanceof Double) {
                intent.putExtra(k, (Double) v);
            } else if (v instanceof Float) {
                intent.putExtra(k, (Float) v);
            } else if (v != null) {
                intent.putExtra(k, String.valueOf(v));
            }
        }
        String mode = spec.intentType != null ? spec.intentType : ActionSpec.INTENT_TYPE_ACTIVITY;
        if (ActionSpec.INTENT_TYPE_BROADCAST.equalsIgnoreCase(mode)) {
            sendBroadcastAsCurrentUser(ctx, intent);
            return "已发广播 " + (spec.intentAction != null ? spec.intentAction : "");
        } else if (ActionSpec.INTENT_TYPE_SERVICE.equalsIgnoreCase(mode)) {
            startServiceAsCurrentUser(ctx, intent);
            return "已启服务 " + (spec.intentAction != null ? spec.intentAction : spec.packageName);
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAsCurrentUser(ctx, intent);
            return "已启 Intent " + (spec.intentAction != null ? spec.intentAction : spec.packageName);
        }
    }

    private static String execFlashlight(Context ctx) throws Throwable {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            HookLog.i("flashlight: CameraManager null");
            return "手电筒不可用";
        }
        String camId = findTorchCameraId(cm);
        if (camId == null) {
            HookLog.i("flashlight: no flash-capable camera found");
            return "未找到闪光灯";
        }
        ensureTorchCallback(cm, camId);
        boolean nextState = !sTorchEnabled;
        sLastTorchSetUptimeMs = SystemClock.uptimeMillis();
        try {
            Method setTorch = CameraManager.class.getMethod("setTorchMode", String.class, boolean.class);
            setTorch.invoke(cm, camId, nextState);
            sTorchEnabled = nextState;
        } catch (Throwable t) {
            // Q4: Unwrap InvocationTargetException to detect MAX_CAMERAS_IN_USE / CAMERA_IN_USE cleanly
            Throwable cause = (t.getCause() != null) ? t.getCause() : t;
            String msg = String.valueOf(t) + " | " + String.valueOf(cause);
            if (msg.contains("CAMERA_IN_USE") || msg.contains("IN_USE")
                    || msg.contains("insufficient resources")) {
                HookLog.i("flashlight: camera busy (MAX_CAMERAS_IN_USE), skipping torch toggle");
                return "相机占用中，无法切换手电筒";
            }
            cm.setTorchMode(camId, nextState);
            sTorchEnabled = nextState;
        }
        HookLog.i("flashlight: cameraId=" + camId + " -> " + (nextState ? "ON" : "OFF"));
        return nextState ? "手电筒已开启" : "手电筒已关闭";
    }

    private static void ensureTorchCallback(CameraManager cm, final String targetCamId) {
        if (!sTorchCallbackRegistered.compareAndSet(false, true)) {
            return;
        }
        try {
            cm.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    if (targetCamId != null && targetCamId.equals(cameraId)) {
                        if (SystemClock.uptimeMillis() - sLastTorchSetUptimeMs > 500L) {
                            sTorchEnabled = enabled;
                        }
                    }
                }
            }, getExecHandler());
        } catch (Throwable t) {
            sTorchCallbackRegistered.set(false);
            HookLog.e("registerTorchCallback failed", t);
        }
    }

    private static String findTorchCameraId(CameraManager cm) {
        if (sTorchCameraId != null) {
            return sTorchCameraId;
        }
        try {
            String fallback = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Boolean hasFlash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (hasFlash != null && hasFlash) {
                    if (fallback == null) {
                        fallback = id;
                    }
                    Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        sTorchCameraId = id;
                        return id;
                    }
                }
            }
            if (fallback != null) {
                sTorchCameraId = fallback;
                return fallback;
            }
        } catch (Throwable t) {
            HookLog.e("findTorchCameraId failed", t);
        }
        return "0";
    }

    private static String execScreenshot(Context ctx) {
        Object pwm = StandardKeysHook.getPhoneWindowManager();
        if (pwm != null) {
            if (tryInvokeTakeScreenshotOnPwm(pwm)) {
                HookLog.i("screenshot: triggered via PhoneWindowManager");
                return null;
            }
        }
        if (tryInvokeScreenshotHelper(ctx)) {
            HookLog.i("screenshot: triggered via ScreenshotHelper");
            return null;
        }
        HookLog.i("screenshot: falling back to POWER+VOLUME_DOWN bypass chord injection");
        StandardKeysHook.injectBypassChord(
                ctx,
                KeyEvent.KEYCODE_POWER, KeyId.POWER.scanCode,
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyId.VOLUME_DOWN.scanCode,
                250L);
        return null;
    }

    private static boolean tryInvokeTakeScreenshotOnPwm(Object pwm) {
        try {
            for (Class<?> c = pwm.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!"takeScreenshot".equals(m.getName())) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    m.setAccessible(true);
                    if (pt.length == 1 && pt[0] == int.class) {
                        m.invoke(pwm, 0);
                        return true;
                    } else if (pt.length == 2 && pt[0] == int.class && pt[1] == int.class) {
                        m.invoke(pwm, 1, 0);
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            HookLog.i("screenshot tier1 (PWM) skipped: " + t);
        }
        return false;
    }

    private static boolean tryInvokeScreenshotHelper(Context ctx) {
        try {
            Class<?> helperCls = Class.forName("com.android.internal.util.ScreenshotHelper");
            Constructor<?> ctor = helperCls.getConstructor(Context.class);
            Object helper = ctor.newInstance(ctx);
            for (Method m : helperCls.getMethods()) {
                if (!"takeScreenshot".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 3 && pt[0] == int.class && pt[1] == Handler.class) {
                    m.invoke(helper, 0, getExecHandler(), null);
                    return true;
                }
                if (pt.length == 6 && pt[0] == int.class && pt[4] == Handler.class) {
                    m.invoke(helper, 1, true, true, 0, getExecHandler(), null);
                    return true;
                }
            }
        } catch (Throwable t) {
            HookLog.i("screenshot tier2 (ScreenshotHelper) skipped: " + t);
        }
        return false;
    }

    private static String execCamera(Context ctx, ActionSpec spec) {
        // A2: Support launching a user-selected camera package (e.g., GCam port / Nothing Camera)
        if (spec != null && spec.packageName != null) {
            try {
                Intent specific = ctx.getPackageManager().getLaunchIntentForPackage(spec.packageName);
                if (specific != null) {
                    specific.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivityAsCurrentUser(ctx, specific);
                    return "打开相机 (" + (spec.label != null ? spec.label : spec.packageName) + ")";
                }
            } catch (Throwable ignored) {
            }
        }
        boolean locked = false;
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) {
                locked = km.isKeyguardLocked();
            }
        } catch (Throwable ignored) {
        }
        Intent intent = new Intent(locked
                ? MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
                : MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (ctx.getPackageManager().resolveActivity(intent, 0) != null) {
                startActivityAsCurrentUser(ctx, intent);
                return "打开相机";
            }
        } catch (Throwable ignored) {
        }
        Intent fallback = ctx.getPackageManager().getLaunchIntentForPackage("com.nothing.camera");
        if (fallback != null) {
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAsCurrentUser(ctx, fallback);
            return "打开相机";
        }
        startActivityAsCurrentUser(ctx, intent);
        return "打开相机";
    }

    private static String execVoiceAssistant(Context ctx) {
        Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (ctx.getPackageManager().resolveActivity(intent, 0) != null) {
                startActivityAsCurrentUser(ctx, intent);
                return "唤起语音助手";
            }
        } catch (Throwable ignored) {
        }
        Intent assist = new Intent(Intent.ACTION_ASSIST);
        assist.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAsCurrentUser(ctx, assist);
        return "唤起语音助手";
    }

    private static String execMediaKey(Context ctx, int keyCode, String label) {
        boolean injected = injectKeyTap(ctx, keyCode, 0, false);
        if (!injected) {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                long now = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(now, now + 20, KeyEvent.ACTION_UP, keyCode, 0));
                HookLog.i("media key dispatched via AudioManager fallback: keyCode=" + keyCode);
            }
        }
        return "媒体：" + label;
    }

    private static String execVolumeAdjust(Context ctx, int direction, String label) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.adjustSuggestedStreamVolume(
                    direction,
                    AudioManager.USE_DEFAULT_STREAM_TYPE,
                    AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND);
        }
        return label;
    }

    private static String execRingerCycle(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return "音频服务不可用";
        }
        int cur = am.getRingerMode();
        int next;
        String label;
        if (cur == AudioManager.RINGER_MODE_NORMAL) {
            next = AudioManager.RINGER_MODE_VIBRATE;
            label = "振动模式";
        } else if (cur == AudioManager.RINGER_MODE_VIBRATE) {
            next = AudioManager.RINGER_MODE_SILENT;
            label = "静音模式";
        } else {
            next = AudioManager.RINGER_MODE_NORMAL;
            label = "响铃模式";
        }
        boolean ok = false;
        try {
            Method m = AudioManager.class.getMethod("setRingerModeInternal", int.class);
            m.invoke(am, next);
            ok = true;
        } catch (Throwable ignored) {
        }
        if (!ok) {
            am.setRingerMode(next);
        }
        HookLog.i("ringer_cycle: " + cur + " -> " + next + " (" + label + ")");
        return label;
    }

    private static void execHome(Context ctx) {
        if (!injectKeyTap(ctx, KeyEvent.KEYCODE_HOME, 0, false)) {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAsCurrentUser(ctx, home);
        }
    }

    private static String execOpenSettings(Context ctx) {
        Intent i = new Intent(Settings.ACTION_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAsCurrentUser(ctx, i);
        return "打开系统设置";
    }

    private static String execLockScreen(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            long now = SystemClock.uptimeMillis();
            try {
                Method m = PowerManager.class.getMethod("goToSleep", long.class, int.class, int.class);
                m.invoke(pm, now, 4, 0);
                HookLog.i("lock_screen: PowerManager.goToSleep(3-arg) invoked");
                return null;
            } catch (Throwable ignored) {
            }
            try {
                Method m = PowerManager.class.getMethod("goToSleep", long.class);
                m.invoke(pm, now);
                HookLog.i("lock_screen: PowerManager.goToSleep(1-arg) invoked");
                return null;
            } catch (Throwable t) {
                HookLog.e("lock_screen reflection failed", t);
            }
        }
        StandardKeysHook.injectBypassTap(ctx, KeyEvent.KEYCODE_POWER, KeyId.POWER.scanCode);
        return null;
    }

    // -------------------------------------------------------- shared utilities

    public static boolean injectKeyTap(Context ctx, int keyCode, int scanCode, boolean registerBypass) {
        return injectKeyPulse(ctx, keyCode, scanCode, 25, registerBypass);
    }

    public static boolean injectKeyPulse(final Context ctx,
                                         final int keyCode,
                                         final int scanCode,
                                         final int pulseMs,
                                         final boolean registerBypass) {
        final long downTime = SystemClock.uptimeMillis();
        if (registerBypass) {
            StandardKeysHook.registerBypassDownTime(keyCode, downTime);
        }
        boolean d = injectKeyEvent(ctx, keyCode, scanCode, KeyEvent.ACTION_DOWN, downTime, downTime);
        int clamped = Math.max(15, Math.min(2000, pulseMs));
        if (clamped <= 60) {
            SystemClock.sleep(clamped);
            long upTime = SystemClock.uptimeMillis();
            boolean u = injectKeyEvent(ctx, keyCode, scanCode, KeyEvent.ACTION_UP, downTime, upTime);
            return d && u;
        } else {
            // Non-blocking delayed UP for longer burst/video pulses (e.g., B4 600ms)
            getExecHandler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    long upTime = SystemClock.uptimeMillis();
                    injectKeyEvent(ctx, keyCode, scanCode, KeyEvent.ACTION_UP, downTime, upTime);
                }
            }, clamped);
            return d;
        }
    }

    public static boolean injectKeyEvent(Context ctx,
                                         int keyCode,
                                         int scanCode,
                                         int action,
                                         long downTime,
                                         long eventTime) {
        KeyEvent ev = new KeyEvent(
                downTime,
                eventTime,
                action,
                keyCode,
                0,
                0,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                scanCode,
                KeyEvent.FLAG_FROM_SYSTEM,
                InputDevice.SOURCE_KEYBOARD);

        try {
            Class<?> imgCls = Class.forName("android.hardware.input.InputManagerGlobal");
            Method getInst = imgCls.getMethod("getInstance");
            Object img = getInst.invoke(null);
            if (img != null) {
                Method inject = imgCls.getMethod("injectInputEvent", InputEvent.class, int.class);
                Object res = inject.invoke(img, ev, INJECT_INPUT_EVENT_MODE_ASYNC);
                if (res instanceof Boolean && (Boolean) res) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Object im = null;
            try {
                Method getInst = InputManager.class.getMethod("getInstance");
                im = getInst.invoke(null);
            } catch (Throwable ignored) {
            }
            if (im == null && ctx != null) {
                im = ctx.getSystemService(Context.INPUT_SERVICE);
            }
            if (im != null) {
                Method inject = im.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
                Object res = inject.invoke(im, ev, INJECT_INPUT_EVENT_MODE_ASYNC);
                return !(res instanceof Boolean) || (Boolean) res;
            }
        } catch (Throwable t) {
            HookLog.e("injectKeyEvent failed keyCode=" + keyCode, t);
        }
        return false;
    }

    public static void startActivityAsCurrentUser(Context ctx, Intent intent) {
        try {
            UserHandle current = getCurrentUserHandle();
            if (current != null) {
                Method m = Context.class.getMethod("startActivityAsUser", Intent.class, UserHandle.class);
                m.invoke(ctx, intent, current);
                return;
            }
        } catch (Throwable ignored) {
        }
        ctx.startActivity(intent);
    }

    private static void sendBroadcastAsCurrentUser(Context ctx, Intent intent) {
        try {
            UserHandle current = getCurrentUserHandle();
            if (current != null) {
                Method m = Context.class.getMethod("sendBroadcastAsUser", Intent.class, UserHandle.class);
                m.invoke(ctx, intent, current);
                return;
            }
        } catch (Throwable ignored) {
        }
        ctx.sendBroadcast(intent);
    }

    private static void startServiceAsCurrentUser(Context ctx, Intent intent) {
        try {
            UserHandle current = getCurrentUserHandle();
            if (current != null) {
                Method m = Context.class.getMethod("startServiceAsUser", Intent.class, UserHandle.class);
                m.invoke(ctx, intent, current);
                return;
            }
        } catch (Throwable ignored) {
        }
        ctx.startService(intent);
    }

    private static UserHandle getCurrentUserHandle() {
        try {
            Field f = UserHandle.class.getField("CURRENT");
            Object val = f.get(null);
            if (val instanceof UserHandle) {
                return (UserHandle) val;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void vibrateLight(Context ctx) {
        try {
            Vibrator vib = null;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) {
                    vib = vm.getDefaultVibrator();
                }
            }
            if (vib == null) {
                vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vib != null && vib.hasVibrator()) {
                vib.vibrate(VibrationEffect.createOneShot(22L, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void showToastSafe(final Context ctx, final String text) {
        try {
            getMainHandler().post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** A8: Side-edge feedback Toast positioned near the hardware side button. */
    private static void showEdgeToastSafe(final Context ctx, final String text) {
        try {
            getMainHandler().post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast t = Toast.makeText(ctx, text, Toast.LENGTH_SHORT);
                        t.setGravity(Gravity.TOP | Gravity.END, 24, 520);
                        t.show();
                    } catch (Throwable ignored) {
                        showToastSafe(ctx, text);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
