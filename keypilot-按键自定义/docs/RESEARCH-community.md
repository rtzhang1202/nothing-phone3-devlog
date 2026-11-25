# KeyPilot v2 社区调研：按键重映射真实玩法用例 & 现成产品机制

> 产出：RESEARCH-community.md · 调研日期 2026-11-25 · 纯网络调研（无真机/adb）
> 检索通道：外网搜索在 SearXNG 不可用时自动降级到 keyless 检索层（见 cn-web-research skill），所有链接均来自实际检索结果，重点条目已做二次抓取核验。
> 目的：为 v2「应用上下文感知」（同手势在不同前台 App 执行不同动作 + 直达 App 内功能）补充动作目录与避坑。

---

## 1. 社区用例集（per-app 按键定制真实玩法，22 条）

> 每条格式：**场景 → 机型/工具 → 一句话机制归因 → 出处**

### 1.1 Nothing / Essential Key 场景

**U1. 相机内=快门，相机外=无动作；双击=开相机；长按=响铃/震动切换（Phone (3)）**
- 工具：Shizuku + Hail + Button Mapper + Tasker + AutoInput
- 机制归因：Button Mapper 把 Essential Key（`TV_INPUT_COMPONENT_2`）的单/双击设为 no action 交给 Tasker，Tasker 用 AutoInput **模拟触摸屏幕上的软件快门坐标**实现"仅在相机内拍照"——因为 Phone (3) 的部分动作本来就是模拟 touch 事件，坐标依赖屏幕 X/Y。
- 出处：https://www.reddit.com/r/NothingTech/comments/1urqy07/essential_key_remap_setup_for_phone_3/ （已核验全文）
- 附带坑（原帖自述）：Tasker 把 Essential Key 和 Glyph Key 识别成同一个键，误触会连带拍照；锁屏开相机是"模拟长按锁屏右下角相机快捷方式"的坐标 hack。

**U2. Essential Key → 手电筒（Phone (3a)，免电脑）**
- 工具：无线调试 + Termux `adb pair` 禁用 `com.nothing.ntessentialspace` / `com.nothing.ntessentialrecorder` → Button Mapper
- 机制归因：先用 ADB 禁用消费该键的两个系统包，键事件才落到无障碍服务；Button Mapper 双击=Toggle flashlight。
- 出处：https://www.reddit.com/r/NothingTech/comments/1nx7ssu/guide_to_remap_the_useless_essential_button_to_a/ 、https://www.reddit.com/r/NothingTech/comments/1jv6gea/quick_guide_to_remap_the_essential_space_button/

**U3. Essential Key 三种手势全自定义（3a/3a Pro）**
- 工具：ADB + KeyMapper
- 机制归因：ADB 放开键位后，KeyMapper 录制触发器，单击/双击/长按分别绑动作；这是 Nothing 社区流传最广的"三手势"路线（教程帖 97 分）。
- 出处：https://www.reddit.com/r/NothingTech/comments/1jv36f1/how_to_remap_the_essential_key_full_tutorial/ 、https://www.reddit.com/r/NothingTech/comments/1jwmcdh/essential_key_remapping_all_three_different_inputs/

**U4. 保留 Essential Space 同时重映射（Nothing OS 4.1）**
- 工具：官方 `Settings → Intelligence Toolkit → Essential Key Settings → Activate with single tap before use` + KeyMapper/Automate
- 机制归因：官方开关把"单击立即触发 Essential Space"改成"双击才触发"，于是**单击与长按这两个手势被腾出来**给重映射，Essential Space 数据不丢——但双击仍被官方占用。
- 出处：https://www.reddit.com/r/NothingTech/comments/1u9tytf/demo_for_nothing_phone_essential_key_remap/ 、https://www.reddit.com/r/NothingTech/comments/1vy0yzv/you_can_remap_the_essential_key_without/

**U5. 单击在相机内=快门 / 平时=无动作（Nothing 官方论坛玩法）**
- 出处：nothing.community 帖 o0o_paw："Essential Space button remapped to open the camera APK (long press) and short press to take a photo once the camera APK is opened"，配合 XDA 的 ADB 命令 + Play 商店按键映射器。
- 机制归因：**长按=启动相机（全局），短按=快门（仅相机内）**——正是 KeyPilot v2 想要的"分层语义"。
- 出处：https://nothing.community/d/42070-missing-feature-on-nothing-cmf-phone-2-pro-volume-buttons-as-camera-shuttershutter （已核验）

**U6. 四手势堆叠：单击=手电、双击=截图、三击=开 App、长按=助手（Phone (3) / Nothing OS 5.0 Beta）**
- 工具：Kea（免 ADB，学习硬件指纹 + 无障碍）
- 机制归因：无障碍服务记录按键的"硬件指纹"后按压模式分类；Pro 版 Action Lab 支持**链式动作**（静音+手电+锁屏一按完成）。
- 出处：https://gadgets.beebom.com/news/kea-app-made-nothing-phone-3-essential-key-useful-for-me

**U7. 息屏也能用的 Essential Key 重映射（3a / 4a / 4a Pro）**
- 工具：essential_remap（开源，AbdulKus）
- 机制归因：屏幕亮时用无障碍拦截；息屏靠**无线调试持久化 ADB key 后常驻一个 shell 监听 `getevent` 键事件**的后台脚本，绕开"息屏时无障碍收不到键"的系统限制。
- 出处：https://github.com/AbdulKus/essential_remap 、https://www.reddit.com/r/NothingTech/comments/1weqsgl/essential_key_remapper_with_sleep_mode/

**U8. 双/三击免费抢、单击需"解锁向导"（EssentialKeyTools）**
- 机制归因（作者自述，非常关键）：Essential Key 在 Android 输入管线里是 `keyCode=0`、`scanCode=250`，public keylayout 未映射，所以无障碍能看到事件；但 **"Consuming the accessibility key event does not stop that system action"——无障碍消费掉事件也拦不住 Nothing 系统策略对单击的处理**，因此单击必须手动 disable Nothing 的包。
- 出处：https://github.com/KoukeNeko/EssentialKeyTools

**U9. Shizuku + Hail 冻结 + Button Mapper（Sam Beckman 视频方案）**
- 配置：单击=Toggle ring/vibrate、双击=Open camera、长按=Assistant、开 Screen-off actions + Turn screen on
- 机制归因：Hail 用 Shizuku 模式"冻结"（不卸载）Essential Space/Recorder，Button Mapper 用 Shizuku 免 ADB 添加自定义键。
- 出处：https://www.samuelbeckman.com/article/nothing-phone-hidden-tricks

### 1.2 阅读 / 翻页场景

**U10. 音量键在任意阅读器里翻页（LineageOS Hisense A9 / Boox Palma 玩家）**
- 工具：Key Mapper → Action = **Swipe screen**（`X:0→0, Y:1170→500`，按机型改坐标）
- 机制归因：App 原生不支持翻页键时，用无障碍**模拟一次纵向滑动**等效翻页——坐标必须按屏高实测，是典型的"每个 App 每台机都要调参"的痛点。
- 出处：https://www.reddit.com/r/hisenseA9/comments/1diyhwj/page_scrolling_with_buttons_in_android_apps/ 、https://www.reddit.com/r/hibreak/comments/1jy5osv/accurate_page_scrolling_with_buttons/

**U11. 只在特定阅读器里生效的翻页（Meebook/Logos，Button Mapper Constraints）**
- 工具：Button Mapper → Action = Swipe Screen（横向 1053→243）+ **Constraint: App in Foreground** 选中只支持左右滑的阅读 App
- 机制归因：Button Mapper 的 per-app 规则本质是"**选 App 黑/白名单决定映射是否启用**"，不是真正的动作分流。
- 出处：https://www.reddit.com/r/eink/comments/1825l9d/meebook_m7_review_my_optimizations/

**U12. 无障碍快捷键（音量+&-）唤起 Google Reading Mode，Key Mapper 把音量键变 PageUp/PageDown（Bigme Hibreak）**
- 机制归因：**系统无障碍快捷键负责"进入模式"，Key Mapper 负责"模式内键位"**，两者叠加实现"全系统通用阅读器"。
- 出处：https://www.reddit.com/r/Bigme/comments/1rc23ij/my_favourite_article_reader_mode_setup_google/

**U13. 蓝牙指环/翻页器在漫画 App 里失效（Kotatsu）**
- 现象：TikTok scroll ring 发的是 dpad/arrow，Kotatsu 只认滚动量不认翻页；用户改用 Shizuku 把"媒体模式"的上一曲/下一曲**翻译成音量键**再由阅读器消费。
- 机制归因：外设键码 → App 响应不一致是老问题；"换一条键码路径"比"改 App"更常见。
- 出处：https://github.com/KotatsuApp/Kotatsu/issues/1298

### 1.3 音乐 / 播客场景

**U14. 息屏双击音量键切歌（XDA 经典 Tasker profile）**
- 工具：Tasker + KC Tasker Processes 插件；Event `%VOLM` 变化 + 750ms 内二次变化判定双击
- 机制归因：Tasker **没有原生"音量键按下"事件**，只能靠"媒体音量变了"倒推按键，再用 `Media Control → Next/Previous`；Spotify 不响应标准媒体键，必须勾 **Simulate Media Button**。
- 出处：https://www.xda-developers.com/tasker-pro-skip-music-tracks-using-volume-keys/
- 已知坑：音量已到顶时双击音量+不会触发（音量没变化就没有事件）。

**U15. 蓝牙媒体键"仅在某设备上"回退播客几秒（r/tasker）**
- 工具：Key Mapper（按键可限定 **Key device = 某个蓝牙设备**）→ send Intent → Tasker `Intent Received`
- 机制归因：Key Mapper 的 per-device 限定 + Intent 桥接，实现"夹在裤子上的蓝牙媒体键只做这一件事"。
- 出处：https://www.reddit.com/r/tasker/comments/1bgmd89/devicespecific_button_remapping/

**U16. 用 Key Mapper + Tasker 把音量键当截图键，息屏恢复原生（r/tasker，用户对比贴）**
- 出处：https://www.reddit.com/r/tasker/comments/1fvubxy/questionnoob_why_no_volume_button_shortnormal/
- 归因：用户明确说"Key Mapper 做这件事从用户视角非常简单，Tasker 反而要绕圈"——**键位类需求专用 App 优于通用自动化**。

### 1.4 相机 / 快门场景

**U17. 指纹传感器在相机 App 内当快门（Dactyl，Nexus 6P 时代）**
- 工具：Dactyl（无障碍）
- 机制归因：**只在"已支持的相机 App"前台时**才监听指纹按压并触发，App 支持列表靠开发者逐个适配推送——per-app 白名单是无障碍方案的标配做法。
- 出处：https://www.reddit.com/r/Nexus6P/comments/4q6eb3/dev_use_your_fingerprint_to_take_photos_in_the/

**U18. WearOS 手表遥控手机相机（CameraRemote，2026）**
- 工具：CameraRemote（无障碍）
- 机制归因：作者明确"不用自建相机，而是用 AccessibilityService 控制**你现有的相机 App**（开相机/拍照/录像/闪光/变焦）"，代价是**必须按包名适配每个相机 App 的节点**。
- 出处：https://github.com/WitherredAway/CameraRemote

**U19. 潜水壳蓝牙快门键的"翻转/镜头切换"键没功能 → 用 Key Mapper 改键**
- 出处：https://www.reddit.com/r/androidapps/comments/1v64kb3/free_keybind_changing_app_for_external_shutter/
- 归因：外设发的是键码，重映射 App 可接管；评论区直接推荐开源 Key Mapper。

**U20. 蓝牙遥控器当骑行台换挡键（MyWhoosh，Key Mapper + Shizuku）**
- 出处：https://www.reddit.com/r/mywhoosh/comments/1py2ciu/virtual_shifting_on_android_via_cheap_bluetooth/
- 归因：截图定位游戏内换挡按钮坐标 → Key Mapper 绑定"滑动/点击到该坐标"，**外设键 → 屏幕坐标点击**是"直达 App 内功能"的通用土办法。

### 1.5 中文社区 / 其它

**U21. 音量键直达微信扫一扫 / 支付宝收付款 / OCR（中文工具类 App）**
- 工具：万能音量键（无障碍，4 组触发：短按音量±、长按音量±）
- 机制归因：内置"快捷指令"分类直接封装**微信扫一扫、支付宝扫一扫/收款码/付款码**入口 Intent，无需用户知道 Activity 名。
- 出处：https://www.i3zh.com/54980.html 、http://www.anxiaoqu.cn/soft/224088.html

**U22. 一加侧键模块把"前台应用"做成变量 + 内置微信/支付宝命令（OPlusKey-lsposed）**
- 工具：LSPosed 模块（hook 系统按键处理链）
- 机制归因：1.6.0 更新日志明确"新增变量**「前台应用」**（输出包名/应用名）"、"新增**「前台应用以外」条件**（可多选）"、内置微信扫一扫/收付款命令——**这就是 KeyPilot v2 想做的事，Xposed 社区已经做到**。
- 出处：https://modules.lsposed.org/module/io.github.lo0kie.opluskey/
- 同类：BetterZUIKey 的「应用模板」= 为指定前台 App 单独定义快捷键覆写，未列出的键继承全局（https://modules.lsposed.org/module/moe.lovefirefly.betterzuikey/ ）；小米 AI 键 aikey-plus-lsp 81 个动作（https://github.com/jumaoli/aikey-plus-lsp ）。

---

## 2. 现成产品机制对比

### 2.1 一览表

| 产品 | 前台检测机制 | 动作粒度 | per-app 分流方式 | 主要坑 |
|---|---|---|---|---|
| **Button Mapper**（flar2，无障碍） | 无障碍服务读取**按键 + 屏幕上前台 App 名**（Play 商店原文："reads button presses, and the name of the app on screen to apply your per-app rules"） | 单击/双击/长按（新版 uk.appsby.mapper 另有三击、组合键、按压次数 5 档） | "Profiles for different situations" + **Turn mappings off in the apps you choose**（黑名单式：在选中 App 里关闭映射）+ Bypass Camera/Phone/SystemUI | 不能 remap 电源键/屏内导航键；息屏需 `pm grant ...WRITE_SECURE_SETTINGS`（ADB）或 Shizuku/root；多无障碍服务互相干扰；厂商后台杀进程（dontkillmyapp） |
| **Key Mapper**（开源，无障碍） | 无障碍服务监听按键 + **当前活动窗口**；另有 Input Method 服务做按键中继 | 100+ 动作、多动作 macro + delay、单/双/长/任意次、组合键、指纹手势 | **Constraints 系统**：`App in/not foreground`、media playing、蓝牙、横竖屏、息屏(需 root)、IME、锁屏/解锁、通话中、时间、充电（AND/OR 组合） | Android 14+ 因广播限速 1/s 改走 Binder+relay service（架构复杂）；`Interact with app element` 依赖 3rd party 暴露无障碍节点，"may not work for all use cases"；息屏检测仅支持音量/耳机/相机键等少数键且需 root |
| **Tasker** | State → App / Display State；`Application Launched` 类需 **Usage Access**；另有 AutoInput 读屏 | 动作最丰富（Task/插件生态），但**没有原生"音量键短按"事件** | 建 N 个 Profile，每个配 App 上下文 | 事件要绕（%VOLM 音量变化倒推、Media Button Grab、AutoInput 插件）；AutoInput 息屏不可用；付费；学习成本最高 |
| **MacroDroid** | Trigger `Application Launched/Closed` 需 **Usage Access**；Constraint `Application Running (foreground)` | 音量键触发两种监控：**Accessibility 模式**（支持长按、双键同按，需无障碍）vs **Monitor Volume Changes 模式**（息屏可用，需 Notification Policy Access） | Constraint 里选前台 App，或一 App 一宏 | 社区报告：**OPPO 设备物理键监听要求 MacroDroid 在前台**；`Application Closed` 实际是"退出前台"不是"进程结束"；后台被杀是头号故障原因 |
| **AutoInput**（无障碍） | 无障碍读屏 + 物理键拦截 | 模拟点击/滑动/输入 + UI Query 取屏上文字 | 由 Tasker Profile 决定何时调用 | 官方答复：**accessibility service 只在屏幕亮时工作**；部分 App 主动禁用无障碍点击；与屏幕遮罩 App（Lux/Twilight）冲突导致无法开权限；常驻有轻微卡顿 |
| **GravityBox**（Xposed） | Xposed hook SystemUI/PhoneWindowManager 层，**全局** Hardware Key Actions | 导航键/音量键动作、GB Actions 快捷方式、App Launcher | **没有 per-app 分流**（只有"在某 App 里禁用"这类旁路思路）；Xposed Additions 曾支持 per-app 但 2015 停更且只到 Oreo | 只适配 AOSP 系 ROM；现代 LSPosed 生态里 GravityBox 作用域受限 |
| **KeyStar** | 通知 + 读日志权限 | 单击~五连击 + 每档长按 | **Contexts**：同一键在不同 App/锁屏下不同动作；可排除 Camera/Clock 保持原生 | 音量键免费、电源/导航键付费；读日志权限敏感 |
| **bxActions / sideActions**（Jawomo，XDA） | 无障碍 + ADB（full remap 脚本） | 单/双/长按，30–35+ 动作 | 6.00 起 **"Custom per app remapping"**：相机内=拍照、浏览器=截图、息屏=手电 | 依赖厂商键位（Bixby/侧键），Samsung 更新可能封杀；部分功能需 AOD |
| **Kea / KeySnap / Everything Key / 万能音量键** 等专用 App | 无障碍（KeySnap 另有无线调试 "Advanced Mode" 读全键） | 3–4 手势 + 链式动作（Kea Action Lab） | 有限的 per-app（多为黑名单/条件自动化） | 厂商键位随系统更新失效；KeySnap 自述"Nothing 官方动作可能仍会同时触发" |

### 2.2 无障碍授权的共性坑（社区实证）

1. **息屏即失效**：AutoInput 作者 joaomgcd 官方答复"accessibility service only works while the screen is on"（https://forum.joaoapps.com/index.php?threads/new-to-autoinput-intercept-hardware-inputs-while-display-is-off.12392/ ）。Button Mapper/Key Mapper 息屏都要 ADB/Shizuku/root 补权。
2. **银行/企业 App 检测无障碍直接拒绝**：r/androidroot 有用户因 Key Mapper 被银行 App 拉黑（"detected by my banking app under the accessibility services section, using DoNotTryAccessibility also doesn't work"），最后被迫上 LSPosed/HMA 路线（https://www.reddit.com/r/androidroot/comments/1uazoml/keys_remapper/ ）。
3. **无障碍服务互相打架 + 抢走系统键**：Google 官方 2026-01 确认 Select to Speak 开启会导致**音量键改调无障碍音量、相机内按音量键不拍照**（https://support.google.com/android/thread/401727904/ ；https://android.gadgethacks.com/news/android-volume-bug-confirmed-by-google-fix-coming/ ，影响面含 Pixel/Samsung/ASUS ROG Phone 9）。
4. **GCam 的 UI 会因"开了任何无障碍 App"而改变**（缩放按钮变滑杆），见 https://www.reddit.com/r/GooglePixel/comments/rjgkfe/if_anyones_zoom_buttons_disappeared_from_gcam/ ——说明相机 App 会**读取无障碍状态自适应**，第三方无障碍介入会改变相机行为。
5. **后台被杀**：dontkillmyapp.com 是各重映射 App FAQ 的固定引用；Macrodroid/Key Mapper 都要求"不限制电池"。
6. **点按坐标/节点随版本漂移**：Phone (3) 的 Tasker 方案依赖快门的屏幕坐标，换分辨率/版本就崩（原帖自述）；无障碍点按还要看 App 是否暴露 `contentDescription`。

### 2.3 现成产品做不到什么 → KeyPilot v2（Xposed 路线）的差异优势

| 现成产品做不到 | 我们能做 |
|---|---|
| **真正的"同一手势→不同动作"**：Button Mapper/KeyStar 的 per-app 实际是"启用/禁用映射"或"换一套完整 Profile"，没有"默认动作 + 某 App 覆写"的分层语义 | Xposed 在 `interceptKeyBeforeDispatching`/`PhoneWindowManager` 层拿到键事件时**同时持有前台包名**，可做"默认动作 + per-app 覆写 + 优先级" |
| **拦截厂商系统动作**：Essential Key 单击被 Nothing 系统策略消费，无障碍"吞掉事件也拦不住系统动作"（EssentialKeyTools 作者原话） | Hook 系统侧可以**直接改写/取消**系统对键的处理，不依赖"先禁用系统包"的破坏性做法 |
| **息屏/锁屏稳定收键**：所有无障碍方案息屏都要额外补丁 | Xposed 路线与屏幕状态无关（KeyPilot 现有四键×三手势已真机验证） |
| **不触发银行/支付 App 的无障碍检测** | Xposed 不依赖无障碍授权（仍需 LSPosed，但检测面不同） |
| **动作直达 App 内功能（deep link 级）**：现成产品多为"打开 App"，做不到"微信内=扫一扫、支付宝=付款码"（只有个别中文 App 硬编码） | 可内置包名→Activity/ShortcutIntent 映射表；或结合无障碍点按作为兜底 |
| **对系统按键事件的原子性**：Tasker/MacroDroid 靠"音量变化倒推"，音量到顶/被其它服务抢走就失效 | 直接拿 KeyEvent，无中间损耗 |
| **键值层面的机型适配**：Essential Key 是 `keyCode=0 / scanCode=250` 这类"未映射键"，靠各家 App 各自摸索 | 我们在 Nothing Phone (3) 上已真机验证四键，可沉淀一份 scanCode 表 |

反向也要清醒：Xposed 路线的代价是**需要 LSPosed 作用域（系统框架）+ 每个 Android 大版本适配**，而无障碍产品是"装了就能用"。动作目录上应**尽量复用社区已验证的动作**，少造轮子。

---

## 3. Nothing Essential Key 专项

### 3.1 官方能力边界

- **默认语义**（Nothing Support 文档）：短按 = 截图存入 Essential Space；长按（按住）= 语音备忘录音；双击 = 打开 Essential Space；相机内短按 = 拍照并加注释存 Essential Space（见俄文汇总 https://www.comss.ru/page.php?id=21865 ）。
- **官方设置项**：`Settings → Intelligence toolkit → Essential Key Settings`，部分 Nothing OS 4.x 构建提供 **"Activate with single tap before use"**（双击才触发）以及延迟/禁用类选项——**社区报告性质，随版本/地区浮动**（https://rottenwifi.com/how-to-disable-or-remap-the-essential-key-on-nothing-phone-3-and-3a/ ）。**官方设置里没有"让键什么都不做"的选项**（KeySnap 文档实测结论）。
- **官方重映射已官宣**：Nothing 软件负责人 Dan Elseworth 于 2026-09-04 在 nothing.community 帖《How we build Software》原话："Nothing OS 5.1 will be a smaller release, but still with a few exciting changes. (**We will finally be allowing users to remap the Essential Key**, for example)."（https://nothing.community/d/62374-how-we-build-software ）
  - 媒体确认：Android Authority（https://www.androidauthority.com/nothing-os-5-1-6-0-3707867/ ）、Beebom（https://gadgets.beebom.com/news/nothing-os-5-1-will-bring-essential-key-remapping ）
  - **动作清单未公布**（相机快捷方式？手电？启动 App？自定义？均未确认），无时间表；Phone (1)/(2) 不在支持列表。
  - Reddit 反响：https://www.reddit.com/r/NothingTech/comments/1w72rjd/essential_key_remapping_to_nothing_os_v51_finally/ （120 分）
- **对 KeyPilot 的含义**：官方 5.1 重映射大概率只覆盖"键→单一动作"，**per-app 语义（相机内=快门）与链式动作仍是空白**，v2 的上下文感知仍有机会；同时应预判"官方重映射上线后抢键冲突"（参考 Essential Key 被系统策略消费的既有事实）。

### 3.2 社区玩法谱系（按侵入度排序）

| 玩法 | 侵入度 | 代表 | 关键点 |
|---|---|---|---|
| 官方"单击前先激活"开关 + KeyMapper/Automate | 零 ADB | r/NothingTech lattemelon、nova3830（bonton mapper） | 只能腾出单击+长按，双击仍归官方 |
| 无障碍录键（Kea / KeySnap / Everything Key / Button Mapper） | 低（部分需无线调试 Advanced Mode） | Beebom Kea 评测、KeySnap 指南 | 单击通常仍被系统动作"加戏" |
| ADB 禁用 `com.nothing.ntessentialspace` + `com.nothing.ntessentialrecorder` | 中（破坏 Essential Space，卸载模块后仍保持禁用，需手动恢复） | DKmarc/Pealoaf/David_Ign 教程、rwilco12 | 社区最通用路线；只禁 Space 不禁 Recorder 会残留行为 |
| Shizuku + Hail 冻结（可随时恢复） | 中低 | Sam Beckman | 比 `pm disable-user` 可逆 |
| 息屏 shell 监听（essential_remap） | 中（改 `nt_block_essential_key=1`，需无线调试配对） | AbdulKus/essential_remap | 唯一系统化解决息屏的开源方案；卸载前需 Restore |
| 模拟 touch 坐标实现"相机内快门/锁屏开相机" | 高（坐标随分辨率/版本漂移） | r/NothingTech findings1mo（已核验） | Tasker 方案的天花板与脆弱点 |

---

## 4. 相机快门触发的社区已知坑

**P1. KEYCODE_CAMERA 不是万能键，各家相机实现差异大**
- AOSP Camera2：`onKeyDown` 只在 `repeatCount==0` 时触发 `onShutterButtonClick()`，`KEYCODE_FOCUS`、`KEYCODE_DPAD_CENTER` 也走同一入口；`onKeyUp` 里视频模式用 `repeatCount==1` 判定（https://android.googlesource.com/platform/packages/apps/Camera2/+/master/src/com/android/camera/VideoModule.java ）
- LineageOS Aperture：`KEYCODE_CAMERA / VOLUME_UP / VOLUME_DOWN` 都 `shutterButton.performClick()`，但 **QR 模式下 onKeyUp 不触发**、视频模式按 down 判定（https://github.com/LineageOS/android_packages_apps_Aperture/blob/master/app/src/main/java/org/lineageos/aperture/CameraActivity.kt ）
- 老 XDA 实测：把键改成 `FOCUS(211)` 后 "FOCUS appears to do nothing"，只有 `CAMERA(212)` 能拍照——**同一 ROM 内两个相机相关键码行为都不同**（https://xdaforums.com/t/rfc-remapping-volume-buttons.1480130/ ）

**P2. 第三方注入 KEYCODE_CAMERA / 音量键快门需要系统级权限**
- `android.intent.action.CAMERA_BUTTON` 广播在**没有实体相机键的手机上"能开相机但不能拍照"**（https://stackoverflow.com/questions/39597012/simulate-taking-picture-in-default-system-camera-app ）
- `Instrumentation.sendKeyDownUpSync()` 需要 `INJECT_EVENTS`（仅系统 App）；音量键快门路径需要 `AudioManager.FLAG_FROM_KEY`，第三方无 root 注入不了（https://stackoverflow.com/questions/75646131/ ）
- 结论：**无障碍点按软件快门**是第三方唯一现实路径 → 进入 P3。

**P3. 无障碍点按快门必须按包名逐个适配**
- 经典做法 `findAccessibilityNodeInfosByText("Take photo")` 需要 `android:packageNames="com.google.android.GoogleCamera"` 限定，且**现代相机 App 多数不再给按钮暴露 text/contentDescription，直接失效**（SO 75646131 高票回答的结论）。
- 商业产品同样如此：Dactyl 维护"支持的相机 App 列表"、CameraRemote 逐包适配、AutoInput FAQ 明说"apps are coded in such a way that the accessibility click is disabled"。
- 连带坑：坐标模拟（Tasker/AutoInput）在分辨率、模式（拍照/人像/视频）、前后摄切换后都要重调。

**P4. 无障碍服务会反过来干掉音量键快门（官方实锤）**
- Google Android Community 2026-01：Select to Speak 开启时**音量键调的是 accessibility volume，且相机内按音量键不拍照**；后续滚动修复，但说明"多无障碍共存"是系统级风险（https://support.google.com/android/thread/401727904/ ）。→ KeyPilot 若同时建议用户装其它无障碍 App，要在 FAQ 里写清冲突。
- GCam 会因检测到任意无障碍服务而把缩放按钮换成滑杆（r/GooglePixel，见 2.2-P4）。

**P5. 相机 App 自己的"音量键行为"设置会覆盖一切**
- Samsung：相机设置 → 拍摄模式 → **"按下音量按钮来" = 拍照/录影、缩放、系统音量**（官方支持页 https://www.samsung.com/tw/support/mobile-devices/how-to-adjust-volume-key-function/ ）
- GCam：设置 → Gestures → **Volume key action** = shutter/zoom/volume/off（Android Authority 手势汇总）
- Open Camera：Settings → Volume keys = 拍照 / 自动对焦（双键按下拍照）/ 变焦 / 曝光 / 音量 / 无；**蓝牙自拍杆默认走音量键路径**（https://www.opencamera.org.uk/help.html ）
- 无实体快门的机型连"音量键=快门"都可能没默认映射：Nothing 官方论坛有 CMF Phone 2 Pro 用户反馈**音量键在 Nothing Camera 里不能当快门**，官方回复"Phone (2) 上是映射的，预期各机型一致"（https://nothing.community/d/42070-... ，已核验）——**同一品牌不同机型/版本行为不一致**。

**P6. 蓝牙遥控器其实是"模拟音量键"**
- 高票答案：绝大多数"中国产蓝牙快门"用 **HID over GATT 模拟音量键**（等同蓝牙键盘），系统直接配对即可，无需 App（SO 75646131 accepted answer）。→ 这解释了为什么"音量键快门"是事实标准，也解释了**为什么 KeyPilot 在相机内接管音量键会和这些外设打架**。
- 老 XDA（2014）：蓝牙遥控器的 scancode 可能映射成 `ZoomIn/ZoomOut`，需要 root + Enhanced Keyboard Helper + Camera FV-5 才能改（https://www.johnwillis.com/2014/10/nexus-5-map-bluetooth-control-to-camera.html ）。

**P7. 连拍/录像的 down/up 判定差异**
- AOSP/多数相机：**拍照用 onKeyUp 或 down+up 各一次**，录像启停常看 `repeatCount==1`；若 KeyPilot 只注入 down 或吞掉 up，会出现"拍两张/录像不停"类问题。Aperture 源码是现成参考。

---

## 5. 对 KeyPilot v2 动作目录的建议清单

### 5.1 强烈建议内置（社区高频 + 现成产品已验证）

| # | 动作 | 理由（社区证据） | 实现提示 |
|---|---|---|---|
| A1 | **相机内=快门（拍照）** | U1/U5、P1–P6 全部指向这一刚需；Nothing CMF 机型连原生都没有 | 首选：向相机发 `KEYCODE_CAMERA`/音量键事件（系统侧注入比无障碍稳）；失败兜底：无障碍按包名点快门；内置 Nothing Camera / GCam / Samsung Camera / Aperture 适配表 |
| A2 | **开相机（启动指定相机 App）** | U1 双击、U5 长按、U9 双击、XDA bxActions 主打 | 支持"选相机 App"（GCam 口 / Nothing Camera） |
| A3 | **手电筒开/关** | U2、U6、U9、Button Mapper/Key Star 高频第一动作 | 系统侧 torch，注意相机占用时的冲突 |
| A4 | **截图 / 区域截图** | U6、KeyMapper/Button Mapper 标配 | 息屏也可用 |
| A5 | **上一曲 / 下一曲 / 播放暂停** | U14/U15/U16、MacroDroid wiki 示例 | 注意 Spotify 类需 simulate media button |
| A6 | **微信扫一扫 / 支付宝扫一扫 / 收付款码** | U21、U22（OPlusKey 内置为常用命令） | **deep link 直达 App 内功能**，中文用户价值极高，做成内置包 |
| A7 | **打开任意 App / App 快捷方式（ShortcutInfo）** | 全产品标配；"直达某应用某功能"的正式实现 | 优先 ShortcutInfo（可直达"新建笔记"等），回落到 Activity |
| A8 | **铃声/震动/静音切换 + 反馈 toast** | U1/U9（iPhone Action Button 式体验被多次模仿） | 建议带贴近物理键的位置 toast |
| A9 | **唤醒助手（Assistant / Circle to Search）** | U6、U9、Kea | Nothing 上注意与系统助手抢键 |
| A10 | **返回 / Home / 最近任务 / 下拉通知 / 快速设置** | GravityBox、KeyMapper、EssentialKeyTools 全有 | 桌面与 App 内默认动作的主力 |

### 5.2 建议内置（per-app 语义差异化，竞品做不好）

| # | 动作 / 规则 | 理由 |
|---|---|---|
| B1 | **阅读类 App 内=翻页（PageUp/PageDown 或纵向 swipe）** | U10–U13 四条独立社区用例；坐标型 swipe 要给"按屏高归一化"的参数 |
| B2 | **导航/地图 App 内=缩放（zoom in/out）** | 需求真实（Carpe Iter 等骑行码表支持 per-app profile），但 Google Maps Android 端**不响应键盘缩放**（官方支持帖），大概率只能走模拟 pinch/按钮点击 → 建议标为"实验" |
| B3 | **直播/录音/通话类 App 内=麦克风静音（按住说话 PTT）** | TeamTalk/Discord 玩家用 Media Button Blocker 反向证明"键被别人抢"是痛点；PTT 需要按住/松开语义（KeyPilot 有长按，注意 up 事件要透传） |
| B4 | **相机内长按=连拍 / 录像启停** | P7 的 down/up 判定差异正好用上 |
| B5 | **音乐 App 内=切歌，非音乐 App 内=音量** | U14 的经典矛盾（音量到顶失效）在 Xposed 路线上可根治 |
| B6 | **游戏 App 内=禁用/透传（不拦截）** | OPlusKey 明确做过"游戏内按音量键不弹游戏空间音量条"；KeyStar 的"排除 App"是同一需求 |
| B7 | **微信/IM 内长按=语音消息** | 用户诉求真实，但**微信"按住说话"是触摸+按住语义**，键要转成长按 touch，且微信无公开快捷键 → 建议列为"实验/兜底无障碍" |
| B8 | **"前台应用"作为动作变量（输出包名/应用名）** | OPlusKey 1.6.0 已提供，说明是被验证的高级需求；可与 Tasker/宏生态互通 |

### 5.3 动作目录要内置的"防坑开关"

1. **App 黑名单**（银行/支付/相机游戏内不拦截）——现成产品全有，缺了会被骂。
2. **息屏/锁屏是否生效**的独立开关（Xposed 路线的天然优势要显式暴露）。
3. **保留默认动作（不吞键）**选项——Key Mapper 的 "Do not override default action"、Open Camera 的"音量键=音量"都是这个语义。
4. **冲突检测**：提示用户"检测到其它无障碍按键服务 / Select to Speak"（P4 官方事故）。
5. **快门注入方式三档**：系统键注入 → 无障碍按包名点按 → 坐标点按，并按机型记录可用档位。
6. **对官方 Essential Key 重映射（OS 5.1）的兼容策略**：官方接管时我们的单击动作如何让位/共存。

---

### 附：本次调研引用的主要出处（分组）

- Nothing/Reddit：r/NothingTech 1urqy07 / 1jv36f1 / 1jv6gea / 1jwmcdh / 1nx7ssu / 1u9tytf / 1vy0yzv / 1weqsgl / 1w72rjd / 1umttax；nothing.community d/62374、d/42070
- Nothing 教程站：rwilco12.com、wreck2053.github.io、rottenwifi.com、ardasenn.com/keysnap、samuelbeckman.com
- 开源：github.com/AbdulKus/essential_remap、KoukeNeko/EssentialKeyTools、keymapperorg/KeyMapper、sds100/KeyMapper、GravityBox/GravityBox、KotatsuApp/Kotatsu#1298、WitherredAway/CameraRemote、jumaoli/aikey-plus-lsp
- 产品官方：buttonmapper.com/faq、keymapper.app/user-guide/{constraints,actions,keymaps}、keymapper.app/contributing/codebase、macrodroidforum.com/wiki（Volume Button Press / Application Running）、joaoapps.com/autoinput/{faq}、forum.joaoapps.com、threestarsapps.com/keystar、opencamera.org.uk/help.html
- XDA：xda-developers.com/xda-spotlight-button-mapper…、xda-developers.com/tasker-pro-skip-music-tracks…、xdaforums bxActions 3903172、sideActions 3961142、RFC remapping volume buttons 1480130、remap physical buttons 4717581、per-app volume 4573445
- StackOverflow：39597012、75646131（含 HID-over-GATT 高票答案）
- 系统源码/官方：AOSP Camera2 VideoModule.java、LineageOS Aperture CameraActivity.kt、support.google.com/android/thread/401727904（Select to Speak 事故）、android.gadgethacks.com 同事件、samsung.com/tw 音量键功能页
- LSPosed 仓库：modules.lsposed.org OPlusKey-lsposed、BetterZUIKey
- 中文：i3zh.com/54980（万能音量键）、appinn.com/button-mapper-android、comss.ru/page.php?id=21865
