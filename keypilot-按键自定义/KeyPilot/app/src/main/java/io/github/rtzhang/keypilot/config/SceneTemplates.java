package io.github.rtzhang.keypilot.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in high-frequency presets (A6) and community-validated per-app scene templates (B1-B8)
 * from docs/V2-PLAN.md v1.1 & docs/RESEARCH-community.md.
 */
public final class SceneTemplates {

    /** A6: Built-in one-tap in-app feature presets (WeChat/Alipay deep links, shutter, etc.). */
    public static final class BuiltInPreset {
        public final String id;
        public final String title;
        public final String description;
        public final String uri;
        public final String packageName;
        public final String className;
        public final String intentAction;

        public BuiltInPreset(String id, String title, String description,
                             String uri, String packageName, String className, String intentAction) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.uri = uri;
            this.packageName = packageName;
            this.className = className;
            this.intentAction = intentAction;
        }
    }

    /** B1-B8: Community per-app scene template descriptor. */
    public static final class TemplateMeta {
        public final String code;
        public final String title;
        public final String description;
        public final boolean experimental;

        public TemplateMeta(String code, String title, String description, boolean experimental) {
            this.code = code;
            this.title = title;
            this.description = description;
            this.experimental = experimental;
        }
    }

    public static final List<BuiltInPreset> BUILTIN_PRESETS = Collections.unmodifiableList(Arrays.asList(
            new BuiltInPreset(
                    "alipay_scan", "支付宝：扫一扫", "Deep Link 直达支付宝扫一扫 (saId=10000007)",
                    "alipays://platformapi/startapp?saId=10000007",
                    "com.eg.android.AlipayGphone", null, null),
            new BuiltInPreset(
                    "alipay_pay", "支付宝：付款码", "Deep Link 直达支付宝付款码 (appId=20000056)",
                    "alipays://platformapi/startapp?appId=20000056",
                    "com.eg.android.AlipayGphone", null, null),
            new BuiltInPreset(
                    "alipay_collect", "支付宝：收款码", "Deep Link 直达支付宝收款码 (appId=20000123)",
                    "alipays://platformapi/startapp?appId=20000123",
                    "com.eg.android.AlipayGphone", null, null),
            new BuiltInPreset(
                    "wechat_scan", "微信：扫一扫", "直达微信扫一扫（优先 Deep Link weixin://scanqrcode）",
                    "weixin://scanqrcode",
                    "com.tencent.mm", null, null),
            new BuiltInPreset(
                    "wechat_pay", "微信：收付款", "直达微信付款快捷页（weixin://widget/pay）",
                    "weixin://widget/pay",
                    "com.tencent.mm", null, null),
            new BuiltInPreset(
                    "camera_shutter", "相机快门（自动适配）", "根据当前相机包名自动选择快门注入路径",
                    null, null, null, null),
            new BuiltInPreset(
                    "nt_recorder_start", "Nothing 录音机：打开录音", "启动 Nothing SoundRecorder",
                    null, "com.nothing.soundrecorder", null, null)
    ));

    public static final List<TemplateMeta> SCENE_TEMPLATES = Collections.unmodifiableList(Arrays.asList(
            new TemplateMeta("B4", "B4 · 相机内：EK 短按快门 + 长按连拍/录像",
                    "适配 Nothing Camera / Google Camera / Open Camera；相机外保持全局打开相机", false),
            new TemplateMeta("B1", "B1 · 阅读器翻页（微信读书 / KOReader）",
                    "在阅读 App 内将 EK 短按/双按映射为 PAGE_DOWN / PAGE_UP 翻页", false),
            new TemplateMeta("B5", "B5 · 音乐 App 内切歌，非音乐 App 调音量",
                    "在音乐 App 前台双击音量±精准切歌（根治音量到顶失效，含 Spotify 模拟键）", false),
            new TemplateMeta("B6", "B6 · 游戏防误触（还原系统原生）",
                    "游戏前台高优先级透传音量键并屏蔽 EK 误触", false),
            new TemplateMeta("B3", "B3 · 录音 / 通话 / 直播麦克风静音 (PTT)",
                    "在会议/语音 App 内长按 EK 切换麦克风静音", false),
            new TemplateMeta("B8", "B8 · 前台应用变量广播（Tasker / MacroDroid）",
                    "触发时将 ${fg_pkg} / ${fg_app_name} / ${fg_activity} 广播给自动化工具", false),
            new TemplateMeta("B2", "B2 · 导航 / 地图缩放 [实验级]",
                    "[实验级] Google Maps Android 不响应键盘缩放，P1 注入 ZOOM 键仅部分地图支持，完整体验需 P2 无障碍手势", true),
            new TemplateMeta("B7", "B7 · 微信 / IM 内长按语音消息 [实验级]",
                    "[实验级] 微信无公开快捷键，按住说话为触摸长按语义；P1 仅占位与提示，需 P2 无障碍长按手势", true)
    ));

    public static final List<String> SUGGESTED_BLACKLIST = Collections.unmodifiableList(Arrays.asList(
            "com.icbc",
            "com.chinamworld.main",
            "com.cmbchina.ccd.pluto.cmbActivity",
            "cmb.pb",
            "com.unionpay",
            "com.eg.android.AlipayGphone"
    ));

    private SceneTemplates() {
    }

    public static BuiltInPreset findPreset(String id) {
        if (id == null) return null;
        for (BuiltInPreset p : BUILTIN_PRESETS) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    public static ActionProfile createFromTemplate(String code) {
        if ("B4".equals(code)) {
            ActionProfile p = new ActionProfile(
                    "prof_camera_shutter",
                    "相机内：EK 短按快门 / 长按连拍录像",
                    true, 200,
                    new ContextMatcher(
                            Arrays.asList("com.nothing.camera", "com.google.android.GoogleCamera*",
                                    "net.sourceforge.opencamera", "org.lineageos.aperture"),
                            null, "", ContextMatcher.KEYGUARD_ANY, ContextMatcher.SCREEN_ON_ONLY),
                    false);
            p.setBinding(KeyId.ESSENTIAL, GestureType.SHORT,
                    ActionSpec.cameraShutter(ActionSpec.SHUTTER_TIER_AUTO, ActionSpec.SHUTTER_KEY_AUTO, 35, false));
            p.setBinding(KeyId.ESSENTIAL, GestureType.LONG,
                    ActionSpec.cameraShutter(ActionSpec.SHUTTER_TIER_AUTO, ActionSpec.SHUTTER_KEY_AUTO, 600, false));
            return p;
        }
        if ("B1".equals(code)) {
            ActionProfile p = new ActionProfile(
                    "prof_reader_page_turn",
                    "阅读器翻页 (B1)",
                    true, 150,
                    new ContextMatcher(
                            Arrays.asList("com.tencent.weread", "org.koreader.launcher",
                                    "com.flyersoft.moonreader", "com.flyersoft.moonreaderp"),
                            null, "", ContextMatcher.KEYGUARD_UNLOCKED_ONLY, ContextMatcher.SCREEN_ON_ONLY),
                    false);
            p.setBinding(KeyId.ESSENTIAL, GestureType.SHORT,
                    ActionSpec.keyInject(93, 35, 1, "下一页 (PAGE_DOWN)", false));
            p.setBinding(KeyId.ESSENTIAL, GestureType.DOUBLE,
                    ActionSpec.keyInject(92, 35, 1, "上一页 (PAGE_UP)", false));
            return p;
        }
        if ("B5".equals(code)) {
            ActionProfile p = new ActionProfile(
                    "prof_music_track_skip",
                    "音乐 App 内切歌 (B5)",
                    true, 160,
                    new ContextMatcher(
                            Arrays.asList("com.netease.cloudmusic", "com.tencent.qqmusic",
                                    "com.spotify.music", "com.apple.android.music",
                                    "com.google.android.apps.youtube.music"),
                            null, "", ContextMatcher.KEYGUARD_ANY, ContextMatcher.SCREEN_ANY),
                    false);
            p.setBinding(KeyId.VOLUME_UP, GestureType.DOUBLE,
                    ActionSpec.mediaTransport(ActionSpec.MEDIA_CMD_NEXT, 15000, null, true));
            p.setBinding(KeyId.VOLUME_DOWN, GestureType.DOUBLE,
                    ActionSpec.mediaTransport(ActionSpec.MEDIA_CMD_PREV, 15000, null, true));
            return p;
        }
        if ("B6".equals(code)) {
            ActionProfile p = new ActionProfile(
                    "prof_game_bypass",
                    "游戏防误触：还原系统原生 (B6)",
                    true, 300,
                    new ContextMatcher(
                            Arrays.asList("com.miHoYo.Yuanshen", "com.tencent.tmgp.sgame",
                                    "com.tencent.tmgp.pubgmhd"),
                            null, "", ContextMatcher.KEYGUARD_UNLOCKED_ONLY, ContextMatcher.SCREEN_ON_ONLY),
                    false);
            p.setBinding(KeyId.VOLUME_UP, GestureType.DOUBLE, ActionSpec.system());
            p.setBinding(KeyId.VOLUME_DOWN, GestureType.DOUBLE, ActionSpec.system());
            p.setBinding(KeyId.ESSENTIAL, GestureType.SHORT, ActionSpec.simple(ActionType.NONE));
            return p;
        }
        if ("B3".equals(code)) {
            ActionProfile p = new ActionProfile(
                    "prof_ptt_mic_mute",
                    "语音/会议麦克风静音 PTT (B3)",
                    true, 180,
                    new ContextMatcher(
                            Arrays.asList("com.discord", "us.zoom.videomeetings",
                                    "com.tencent.wemeet.app", "com.nothing.soundrecorder"),
                            null, "", ContextMatcher.KEYGUARD_ANY, ContextMatcher.SCREEN_ON_ONLY),
                    false);
            p.setBinding(KeyId.ESSENTIAL, GestureType.LONG, ActionSpec.simple(ActionType.MIC_MUTE_PTT));
            return p;
        }
        if ("B8".equals(code)) {
            ActionProfile p = new ActionProfile(
                    "prof_context_var_broadcast",
                    "前台变量广播给自动化工具 (B8)",
                    true, 120,
                    ContextMatcher.any(),
                    false);
            Map<String, Object> ex = new LinkedHashMap<>();
            ex.put("pkg", "${fg_pkg}");
            ex.put("app_name", "${fg_app_name}");
            ex.put("activity", "${fg_activity}");
            p.setBinding(KeyId.ESSENTIAL, GestureType.DOUBLE,
                    ActionSpec.contextVarBroadcast("io.github.rtzhang.keypilot.CONTEXT_TRIGGER", ex, false));
            return p;
        }
        if ("B2".equals(code)) {
            ActionProfile p = new ActionProfile(
                    "prof_maps_zoom_exp",
                    "导航缩放 [实验级] (B2)",
                    true, 140,
                    new ContextMatcher(
                            Arrays.asList("com.google.android.apps.maps", "com.autonavi.minimap"),
                            null, "", ContextMatcher.KEYGUARD_UNLOCKED_ONLY, ContextMatcher.SCREEN_ON_ONLY),
                    true);
            // KEYCODE_ZOOM_IN=168, KEYCODE_ZOOM_OUT=169 (experimental: Google Maps ignores keyboard zoom)
            p.setBinding(KeyId.VOLUME_UP, GestureType.DOUBLE,
                    ActionSpec.keyInject(168, 35, 1, "放大 (ZOOM_IN · 实验)", false));
            p.setBinding(KeyId.VOLUME_DOWN, GestureType.DOUBLE,
                    ActionSpec.keyInject(169, 35, 1, "缩小 (ZOOM_OUT · 实验)", false));
            return p;
        }
        if ("B7".equals(code)) {
            ActionProfile p = new ActionProfile(
                    "prof_wechat_ptt_exp",
                    "微信长按语音 [实验级] (B7)",
                    true, 140,
                    new ContextMatcher(
                            Collections.singletonList("com.tencent.mm"),
                            null, "", ContextMatcher.KEYGUARD_UNLOCKED_ONLY, ContextMatcher.SCREEN_ON_ONLY),
                    true);
            p.setBinding(KeyId.ESSENTIAL, GestureType.LONG, ActionSpec.simple(ActionType.MIC_MUTE_PTT));
            return p;
        }
        return new ActionProfile("prof_custom_" + System.currentTimeMillis(), "自定义场景",
                true, 100, ContextMatcher.any(), false);
    }

    public static List<ActionProfile> createV2DefaultProfiles() {
        List<ActionProfile> list = new ArrayList<>();
        // Primary user-requested scenario: inside Camera EK short = shutter
        ActionProfile cam = createFromTemplate("B4");
        // Keep EK long inheriting global flashlight by default in the v2 test preset
        cam.setBinding(KeyId.ESSENTIAL, GestureType.LONG, ActionSpec.inherit());
        list.add(cam);
        list.add(createFromTemplate("B1"));
        list.add(createFromTemplate("B5"));
        list.add(createFromTemplate("B6"));
        return list;
    }
}
