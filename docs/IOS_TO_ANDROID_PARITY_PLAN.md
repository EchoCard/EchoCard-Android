# CallMate：iOS 一比一还原至 Android 完整计划

本文档基于对 **iOS 项目**（`/Users/macbook/Desktop/CallMate`）与 **Android 项目**（`/Users/macbook/AndroidStudioProjects/CallMate`）的对比，制定将 iOS 功能与体验一比一还原到 Android 的宏大计划。

---

## 一、现状对比总览

| 维度 | iOS（参考） | Android（当前） |
|------|-------------|-----------------|
| **架构** | SwiftUI + TabView(3 tabs) | 单 Activity + Compose，无底部 Tab |
| **主界面** | 接电话 / AI分身 / 打电话 三 Tab | 仅「接电话」单屏，Settings/Detail 为子视图 |
| **数据** | SwiftData + UserDefaults + 文件 | 全 Mock，无持久化 |
| **通话/电话** | CallSessionController、CallKit、WebSocket、BLE | 无真实通话与来电逻辑 |
| **设备绑定** | 真实 BLE 扫描/连接/保存 | 模拟 2.5s 后固定设备 |
| **推送/通知** | APNs + 来电/转写/外呼任务 | 无 |
| **外呼** | 完整外呼任务、模板、联系人、定时 | 无 |
| **设计系统** | DesignSystem 统一色/字/间距/圆角 | 部分 Color.kt，未统一 DS |
| **国际化** | zh-Hans / en + 大量 t(zh,en) | 仅 t(zh,en) 无 strings 资源 |

---

## 二、阶段划分与目标

### 阶段 0：基础架构与设计系统（前置）

**目标**：与 iOS DesignSystem 对齐，并建立可扩展的 Compose 架构。

- [ ] **0.1 设计系统**
  - 将 iOS `AppColors` / `AppTypography` / `AppSpacing` / `AppRadius` / `AppShadow` 映射到 Android：
    - `ui/theme/Color.kt`：Primary #007AFF、Accent #5856D6、Success/Warning/Error、textPrimary/Secondary/Tertiary、background/surface、separator/border。
    - `ui/theme/Type.kt`：与 AppTypography 对应（largeTitle → displayLarge 等），统一字重与行高。
    - 新建 `ui/theme/Spacing.kt`、`ui/theme/Shape.kt`、`ui/theme/Elevation.kt`（或沿用 Material3 的 Elevation）。
  - 强制浅色主题与 iOS 一致（`preferredColorScheme(.light)` → `darkTheme = false` 或仅 light 主题）。
- [ ] **0.2 依赖与模块**
  - 引入：Navigation Compose、ViewModel、Room、DataStore（或 SharedPreferences）、Kotlin Coroutines、可选 Hilt/Koin。
  - 规划包结构：`app`、`core`（telephony/ble/audio/network）、`data`（models/repositories/db）、`features`（calls/device/settings/outbound）、`ui`（theme/components）。

---

### 阶段 1：主框架与导航（与 iOS 主流程一致）

**目标**：主界面从「单屏 CallsView」改为与 iOS 一致的「底部三 Tab」。

- [ ] **1.1 MainTabView 等价实现**
  - 使用 `Scaffold` + `BottomNavigation`（或 `NavigationBar`) 三个 Tab：
    1. **接电话**（Receive）→ 对应现有 `CallsView` 增强版。
    2. **AI分身**（AI Avatar）→ 新建 `AISecView`（当前 Android 无此 Tab）。
    3. **打电话**（Call Out）→ 新建 `OutboundCallsView` 及子流程。
  - Tab 图标与文案与 iOS 一致：`t("接电话","Receive")`、`t("AI分身","AI Avatar")`、`t("打电话","Call Out")`。
- [ ] **1.2 根导航与状态持久化**
  - `ContentView` 等价：法律协议覆盖层 → Landing/Scanning/Bound → Onboarding → Main（MainTabView）。
  - 使用 DataStore 或 SharedPreferences 持久化：是否已同意协议、是否已完成 Onboarding、当前语言、绑定状态等，与 iOS UserDefaults/@AppStorage 对应。
- [ ] **1.3 深链**
  - 支持 `callmate://livecall` 等价：Android 通过 Intent Filter（custom scheme 或 App Links）打开对应「进行中通话」界面。

---

### 阶段 2：设备绑定与 BLE（与 iOS BindingFlow + BLE 一致）

**目标**：真实蓝牙扫描、连接、保存设备，替代当前模拟绑定。

- [ ] **2.1 BindingFlowView 还原**
  - 步骤：Landing（产品介绍 + 绑定设备）→ Scanning（真实 BLE 扫描列表）→ 选择设备 → Bound（成功 + 开始训练）→ 可选策略选择 Sheet。
  - 扫描阶段使用 Android BLE API（扫描、过滤 EchoCard 相关 Service/Name），连接后保存 peripheral 标识（等价 iOS UserDefaults 存 UUID）。
- [ ] **2.2 CallMateBLEClient 等价**
  - 封装 BLE 中心角色：扫描、连接、断线重连、发送指令（拨号、策略、固件、control JSON、音频等）。
  - 与 iOS `CallMateBLEModels` 对齐指令与数据格式，便于后端/设备兼容。
- [ ] **2.3 音频编解码**
  - iOS 使用 libopus（float）或 mSBC；Android 需接入 Opus（如 libopus 或 Android Opus API）及 mSBC 以兼容同一设备与后端。

---

### 阶段 3：通话核心（来电、通话中、挂断、转写）

**目标**：实现与 iOS 同等的来电接听、AI 会话、实时转写、摘要。

- [ ] **3.1 电话集成**
  - 使用 Android `ConnectionService` / `TelecomManager` 与 `InCallService`，实现来电识别、接听/拒绝、通话状态同步到 app。
  - 与 iOS `SystemCallObserver`（CallKit）等价：系统来电接听后，通知 app 开始/结束 AI 会话。
- [ ] **3.2 CallSessionController 等价**
  - 统一会话状态机：状态与转换与 iOS `CallStateMachine` / `CallSessionContext` 一致。
  - 协调：BLE 事件、WebSocket（STT/TTS/hello/listen/abort）、音频路由、生命周期、紧急流程、Live Activity 等价（见阶段 5）。
- [ ] **3.3 通话中 UI**
  - **LiveCallView**：实时转写列表、 handoff/hangup、与 iOS 布局与交互一致。
  - 音频：`CallTransportCoordinator` / `CallAudioRouter` 等价，麦克风与听筒/扬声器路由。
- [ ] **3.4 通话后**
  - **CallDetailView**：摘要、完整转写、录音播放（`CallRecordingPlayer`）、反馈、统计。
  - **CallSummaryCoordinator** 等价：通话结束后调用 `ChatSummaryService` 拉取/更新摘要并写回本地 DB。
- [ ] **3.5 通话列表与全部通话**
  - **CallsView**：首页 BLE 状态、设备入口、来电列表（全部/重要）、模式（Standby/Smart/Full）、MCU 更新提示、ANCS 引导（见阶段 6）。
  - **AllCallsView**：完整历史列表、搜索、筛选，与 iOS 一致。

---

### 阶段 4：数据层与持久化（与 iOS SwiftData + UserDefaults + 文件一致）

**目标**：本地数据与 iOS 一一对应，便于双端逻辑一致。

- [ ] **4.1 Room 与 SwiftData 映射**
  - `CallLog` → 表 call_log（id, 通话信息、摘要、标签、时间等）。
  - `TranscriptLine` → 表 transcript_line（关联 call_id）。
  - `CallFeedback` → 表 call_feedback。
  - `OutboundPromptTemplate` → 表 outbound_prompt_template。
  - `OutboundContactBookEntry` → 表 outbound_contact_book_entry。
- [ ] **4.2 偏好与配置**
  - DataStore/SharedPreferences 存：协议已接受、Onboarding 完成、语言、已保存 BLE 设备、JWT、app_code、pid_id、语音/音色、策略 JSON、外呼相关开关、MCU 提示、接听延迟、ANCS/HFP 等，与 iOS UserDefaults key 对齐。
- [ ] **4.3 策略与文件**
  - `ProcessStrategyStore`：策略规则存本地（如 ws_process_strategy），默认规则、重置、加载/保存逻辑与 iOS 一致。
  - 录音文件：本地存储路径与命名规则与 iOS `CallAudioStore` 对齐。
  - 外呼任务：JSON 文件或 DB 表存 `outbound_tasks.json` 等价数据；结构与 iOS `OutboundTask` 一致。

---

### 阶段 5：后端与实时通信（与 iOS WebSocket + REST 一致）

**目标**：同一套后端同时服务 iOS 与 Android。

- [ ] **5.1 认证**
  - `BackendAuthManager` 等价：
    - POST `https://echocard.xiaozhi.me/api/app/register`（pid_id, app_code）。
    - POST `/api/app/token`（app_code → JWT）。
    - POST `/api/device/report`（device_id, app_code, bluetooth_id）。
  - 安全存储 JWT（如 EncryptedSharedPreferences 或 Keystore）。
- [ ] **5.2 WebSocket**
  - 连接 `ws://120.79.156.134:8081`，鉴权头带 JWT；场景与消息类型与 iOS 一致（call, init_config, update_config, evaluation, outbound_chat；hello, listen, abort, stt, tts, error, mcp；二进制 Opus/mSBC）。
- [ ] **5.3 摘要服务**
  - `ChatSummaryService`：POST `http://120.79.156.134:8002/api/chat/summaries`（session_id），轮询或回调更新 CallLog 的 summary/label/fullSummary 等。

---

### 阶段 6：推送与「Live Activity」等价

**目标**：推送处理与锁屏/通知栏上的「进行中通话」体验与 iOS 对齐。

- [ ] **6.1 FCM**
  - 集成 Firebase Cloud Messaging；设备 token 上报后端（等价 APNs device token）。
  - 处理与 iOS 相同的 payload 结构：
    - **来电/通话**：`call`（event, call_id, caller_name, caller_number, status_text, tts_text, duration_seconds, can_handoff, can_hangup）→ 更新/启动「进行中通话」UI。
    - **BLE 转发**：`ble`（cmd, params, expect_ack, auto_connect）→ 调用 BLE 发送指令。
    - **拨号**：`dial`（phone_number, auto_connect, expect_ack）→ BLE 拨号指令。
- [ ] **6.2 进行中通话的持续展示**
  - iOS 为 Live Activity（锁屏 + 灵动岛）；Android 使用**常驻通知**（Ongoing notification）+ 可选 Bubble 或自定义 Heads-up 界面，展示通话状态、实时转写、接听/挂断按钮。
- [ ] **6.3 本地通知**
  - 「实时转写」通知（含 call_id）→ 点击打开 LiveCall 或 CallDetail。
  - 外呼任务到时 → 本地通知提醒用户，点击打开 app（与 iOS `OutboundTaskBGScheduler.scheduleLocalNotification` 等价）。

---

### 阶段 7：外呼（Outbound）完整功能

**目标**：与 iOS 外呼模块一比一。

- [ ] **7.1 外呼 Tab 与列表**
  - **OutboundCallsView**：任务列表、创建任务、模板、联系人、设置入口。
- [ ] **7.2 创建/编辑任务**
  - **OutboundCreateTaskView**：选择联系人、模板、定时；与 iOS 流程一致。
  - **OutboundTaskDetailView** / **OutboundAllHistoryTasksView**：任务详情与历史。
- [ ] **7.3 模板与联系人**
  - **OutboundTemplateSettingsView**、**OutboundCallSettingsView**、**OutboundContactsManagementView**：模板管理、通话设置、通讯录管理；数据来自 Room/文件。
- [ ] **7.4 调度与风控**
  - 后台任务：使用 WorkManager 或 AlarmManager 在指定时间触发外呼任务（等价 iOS BGAppRefreshTask + 本地通知）。
  - **OutboundDialRiskControl**：紧急号码、深夜时段（如 23:00–08:00）禁止外呼，与 iOS 一致。

---

### 阶段 8：设置与设备管理（与 iOS Settings + Device 一致）

**目标**：设置项、设备弹窗、诊断、权限引导与 iOS 一致。

- [ ] **8.1 SettingsView**
  - 与 iOS `SettingsSections` 对齐：通用（接听延迟、语言等）、AI 配置、测试相关；语音/音色选择（SettingsVoiceToneSheet、VoiceToneOptionCard、TTSVoiceOptionCard）。
  - **EchoCardPermissionsCard**：麦克风、通知、网络等权限状态展示与跳转设置。
- [ ] **8.2 DeviceModalView**
  - 设备信息（名称、电量、固件）、诊断入口、灯光控制、固件升级、断开/重新绑定/恢复出厂。
- [ ] **8.3 诊断与固件**
  - **DeviceDiagnosticsView**、**DeviceLightControlView**、**MCUCrashLogView**；**FirmwareUpdateService** 与 iOS 逻辑对齐（进度、错误提示）。
- [ ] **8.4 权限引导**
  - **ANCSPermissionGuideView** 等价：Android 侧为「通知访问权限」或必要系统权限引导，文案与流程与 iOS 一致；可通过 Feature Flag 控制是否展示（如 `ancsAuthorizationEnabled`）。**技术映射与实施顺序**见仓库根目录 **`docs/ANDROID_ANCS_PARITY.md`**；BLE 命令除 `ancs_verify` 外，Android 在探针成功后需发 **`ancs_verify_app_ok`**（协议见 **`docs/ios-mcu-communication-protocol.md`**）。

---

### 阶段 9：提示词、反馈与 AI 分身

**目标**：规则编辑、反馈聊天、AI 分身 Tab 与 iOS 一致。

- [ ] **9.1 提示词**
  - **PromptModalView**：规则展示与 **PromptEditorView** 编辑；与 ProcessStrategyStore 及后端配置同步（若 iOS 有）。
- [ ] **9.2 反馈聊天**
  - **FeedbackChatModalView**：流式回复、good/bad 反馈类型与 iOS 一致；可对接同一评估/客服接口。
- [ ] **9.3 AISecView**
  - **AI分身** Tab 内容：与 iOS AISecView 功能与布局一致（说明、入口、配置等）。

---

### 阶段 10：测试与仿真（与 iOS 保持一致）

**目标**：开发与演示用仿真不缺失。

- [ ] **10.1 仿真界面**
  - **SimulationView**、**LockScreenSimulationView**、**LocalPlaybackTestCallView**：与 iOS 行为一致（模拟来电、锁屏样式、本地录音回放测试）。
- [ ] **10.2 设计系统展示**
  - **DesignSystemShowcaseView**：可放在 debug 或设置「关于」中，便于 UI 对齐与验收。

---

### 阶段 11：法律与合规

**目标**：与 iOS 一致的首次启动体验与合规。

- [ ] **11.1 法律协议**
  - **LegalAgreementViews** / **LegalConsentOverlay**：用户协议、隐私政策、同意勾选；仅首次启动显示，持久化「已同意」。
- [ ] **11.2 权限文案**
  - AndroidManifest 与运行时权限说明与 iOS Info.plist 描述对齐（蓝牙、麦克风、通知等）；如需后台 BLE/音频，说明用途。

---

### 阶段 12：国际化与资源

**目标**：双语言与资源管理方式与 iOS 对齐。

- [ ] **12.1 字符串**
  - 将 `t(zh, en)` 的文案迁移到 `res/values/strings.xml` 与 `res/values-zh/strings.xml`（或 zh-rCN），key 与 iOS Localizable.strings 对应，便于后续多语言扩展。
- [ ] **12.2 资产**
  - 与 iOS Assets.xcassets 对应：AppIcon、AccentColor、ChatBg、EchoCardBrandIcon 等；尺寸与密度按 Android 规范生成。

---

## 三、技术映射速查表

| iOS | Android |
|-----|--------|
| SwiftUI + TabView | Jetpack Compose + Scaffold + BottomNav |
| SwiftData | Room |
| UserDefaults / @AppStorage | DataStore / SharedPreferences |
| CallKit + SystemCallObserver | ConnectionService / TelecomManager + InCallService |
| CoreBluetooth (central) | Android BLE (GATT client) |
| Live Activity + ActivityKit | Ongoing notification + Bubble/Heads-up UI |
| APNs + UNUserNotificationCenter | FCM + NotificationManager |
| BGAppRefreshTask + 本地通知 | WorkManager / AlarmManager + 本地通知 |
| libopus (CocoaPods) | libopus / Android Opus API |
| callmate://livecall | Intent Filter (custom scheme / App Links) |
| Info.plist 权限与 ATS | AndroidManifest + 运行时权限；Network Security Config |

---

## 四、建议实施顺序（依赖关系）

1. **阶段 0** → 所有 UI 与架构基础。
2. **阶段 1** → 立即让主界面与 iOS 一致（三 Tab），便于后续按 Tab 分模块开发。
3. **阶段 4**（数据层）与 **阶段 5**（后端）→ 可并行或略早于通话/外呼，便于 Mock 切换真实数据。
4. **阶段 2**（BLE）→ 为 **阶段 3**（通话）提供设备通道。
5. **阶段 3**（通话）→ 依赖 **阶段 5**（WebSocket）、**阶段 4**（DB）、**阶段 6**（推送/通知）。
6. **阶段 6**（推送）→ 与 **阶段 3** 紧密配合。
7. **阶段 7**（外呼）→ 依赖 **阶段 4**、**阶段 5**、后台任务与本地通知。
8. **阶段 8、9、10、11、12** 可与 3/6/7 并行或穿插，按优先级排期。

---

## 五、验收标准（一比一还原）

- 主界面：底部三 Tab（接电话 / AI分身 / 打电话）与 iOS 一致。
- 设备绑定：真实 BLE 扫描、连接、保存，流程与 iOS BindingFlow 一致。
- 来电与通话：来电识别、接听、AI 会话、实时转写、摘要、录音、反馈与 iOS 行为一致。
- 外呼：任务创建、模板、联系人、定时、风控与 iOS 一致。
- 设置与设备：设置项、设备弹窗、诊断、权限引导与 iOS 一致。
- 数据与后端：同一后端、同一 API/WebSocket 契约，双端数据模型一致。
- 推送与「进行中通话」：Payload 一致，Android 用常驻通知 + 可选 Bubble 达到近似 Live Activity 体验。
- 设计系统与国际化：颜色、字体、间距、圆角与 iOS DesignSystem 一致；中英文与 iOS 一致并可持续扩展。

---

本文档可作为产品与开发对齐的基准；实施时可将各阶段拆成更细的 Task 并放入 Issue/Task 系统跟踪。每完成一个阶段即可做一次与 iOS 的对照验收，确保一比一还原。
