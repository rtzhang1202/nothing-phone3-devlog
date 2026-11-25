package io.github.rtzhang.keypilot.config;

/**
 * Complete catalog of supported key-mapping actions in KeyPilot (v1 + v2 P1).
 */
public enum ActionType {
    // ---- v1 core actions (preserved for 100% backward compatibility) ----
    SYSTEM("system", "跟随系统 (原生)", "放行原始按键事件，保留系统默认行为"),
    NONE("none", "无动作 (屏蔽)", "拦截并吞掉该手势，不触发任何操作"),
    LAUNCH_APP("launch_app", "启动应用", "从已安装应用列表中选择并启动"),
    LAUNCH_ACTIVITY("launch_activity", "启动指定 Activity", "通过包名与完整类名启动指定页面"),
    CUSTOM_INTENT("custom_intent", "发送自定义 Intent", "自定义 Action / 类型 / Extras 下发"),
    FLASHLIGHT("flashlight", "手电筒开关", "切换后置摄像头闪光灯手电筒"),
    SCREENSHOT("screenshot", "截屏", "触发系统截屏 (TakeScreenshotService)"),
    CAMERA("camera", "相机", "打开系统相机或指定相机 App"),
    VOICE_ASSISTANT("voice_assistant", "语音助手", "唤起默认语音助手"),
    MEDIA_PLAY_PAUSE("media_play_pause", "媒体：播放 / 暂停", "注入 KEYCODE_MEDIA_PLAY_PAUSE"),
    MEDIA_PREV("media_prev", "媒体：上一曲", "注入 KEYCODE_MEDIA_PREVIOUS"),
    MEDIA_NEXT("media_next", "媒体：下一曲", "注入 KEYCODE_MEDIA_NEXT"),
    VOLUME_UP("volume_up", "音量加", "调高当前音频流音量"),
    VOLUME_DOWN("volume_down", "音量减", "调低当前音频流音量"),
    RINGER_CYCLE("ringer_cycle", "响铃 / 振动 / 静音循环", "循环切换铃声模式（支持贴近侧键反馈 Toast）"),
    NAV_BACK("nav_back", "返回 (Back)", "注入 KEYCODE_BACK"),
    NAV_HOME("nav_home", "主页 (Home)", "返回主屏幕"),
    NAV_RECENTS("nav_recents", "最近任务 (Recents)", "打开多任务切换视图"),
    OPEN_SETTINGS("open_settings", "打开系统设置", "启动系统设置主界面"),
    LOCK_SCREEN("lock_screen", "锁屏", "立即熄屏并锁定设备"),

    // ---- v2 P1 context-aware & in-app actions ----
    INHERIT("inherit", "继承全局", "仅在场景 Profile 中生效：不覆写该手势，自动回退全局绑定"),
    CAMERA_SHUTTER("camera_shutter", "相机快门", "在相机内触发拍照快门（自动适配 GCam/Nothing/OpenCamera 三档策略）"),
    KEY_INJECT("key_inject", "注入按键码", "向当前前台窗口注入指定 Android KeyCode（如翻页/确认）"),
    MEDIA_TRANSPORT("media_transport", "精准媒体控制", "直控活跃 MediaSession（切歌/快进退/Spotify 兼容模拟键）"),
    DEEP_LINK("deep_link", "Deep Link 直达", "通过 URI Scheme 直接跳转目标应用内部功能页"),
    IN_APP_PRESET("in_app_preset", "中文高频功能内置包", "一键直达微信/支付宝扫一扫、收付款码等内置快捷入口"),
    PARTIAL_SCREENSHOT("partial_screenshot", "区域截图", "触发局部/区域截屏（不支持时优雅回落全屏截图）"),
    EXPAND_NOTIFICATIONS("expand_notifications", "下拉通知栏", "展开系统通知面板"),
    EXPAND_QS("expand_qs", "展开快速设置", "展开系统快捷开关面板"),
    CONTEXT_VAR_BROADCAST("context_var_broadcast", "前台应用变量广播", "携带前台包名/应用名/Activity 广播对接 Tasker/MacroDroid"),
    MIC_MUTE_PTT("mic_mute_ptt", "麦克风静音 / PTT", "切换系统麦克风静音（长按支持按住说话/松手恢复）");

    public final String id;
    public final String displayName;
    public final String description;

    ActionType(String id, String displayName, String description) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
    }

    public static ActionType fromId(String id) {
        if (id == null) {
            return SYSTEM;
        }
        for (ActionType a : values()) {
            if (a.id.equals(id)) {
                return a;
            }
        }
        return SYSTEM;
    }
}
