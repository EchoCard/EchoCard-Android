# EchoCard Android 二次开发指南

> 元信息
> - Tags: `android` `dev` `workflow` `build` `product`
> - Scope: `docs`
> - Priority: `high`
> - Related: `docs/MOBILE_BLE_PREBUILDS.md`, `docs/ARCHITECTURE.md`, `docs/CALLMATE_QUICK_INDEX.md`

> 适用对象：基于公开 `EchoCard-Android` 做二次开发或学习的团队。BLE 预编译 AAR 已放在 `app/libs/`，不依赖私有 Maven。
>
> 说明：为了兼容现有包结构，仓内部分 package / class / Gradle module 仍保留 `CallMate` 命名；对外交付仓、文档和产品名已经切换为 `EchoCard`。
>
> 本文重点说明：
> 1. Android 主仓哪些地方可以直接二开
> 2. 预编译 BLE 依赖如何被引用
> 3. 哪些改动会涉及协议或固件，不适合只在主仓里单改

---

## 1. 先看交付边界

Android 侧当前也已经分成两层：

| 层 | 位置 | 是否开放二开 | 说明 |
|---|---|---|---|
| 主 App | `./` | 是 | Compose UI、业务流程、数据层、后端通信、提示词、设置页、外呼逻辑等都在这里 |
| 预编译 BLE 库 | `app/libs/callmate-ble-0.1.0.aar` | 否（默认） | 以 AAR 形式入仓，协议与 MCU 绑定的能力不在二开范围 |

你可以放心改的，通常包括：

- 品牌与应用标识
- 界面、导航、文案、主题
- 后端地址、鉴权、WebSocket、语音克隆、OTA 服务地址
- 默认代接策略、Prompt、外呼模板
- Room / DataStore 本地数据结构

默认不要单独改的，包括：

- BLE 命令字、UUID、ACK 行为
- 音频帧格式
- OTA 包协议
- 与 MCU 强绑定的协议字段

---

## 2. 本地开发环境

### 2.1 必备条件

- Android Studio
- Android SDK
- JDK 11 兼容环境
- 无需为 BLE 配 GitHub Packages；预编译在 `app/libs/`

### 2.2 预编译 BLE 接入方式

`app/build.gradle.kts` 中：

```kotlin
implementation(files("libs/callmate-ble-0.1.0.aar"))
```

`settings.gradle.kts` **不**为 BLE 再声明私有 `maven.pkg`；内部若另有私有库发 `callmate-ble` 的流水线，不取代本仓对外默认路径，以免公开历史与文档与私有包强绑定。

### 2.3 本地配置步骤

先复制本地配置模板：

```bash
cd /path/to/EchoCard-Android
cp local.properties.example local.properties
```

然后填写：

- Android SDK 路径
- 如需本地或 CI 发布 release 包，再补签名信息

`local.properties.example` 当前字段：

```properties
release.storeFile=key/release.keystore
release.storePassword=
release.keyAlias=callmate
release.keyPassword=
```

### 2.4 编译验证

推荐先跑：

```bash
cd /path/to/EchoCard-Android
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
```

若 `compileDebugKotlin` 与 BLE AAR 有关，先确认 `app/libs/callmate-ble-0.1.0.aar` 已随仓存在。

---

## 3. 项目结构与常用入口

客户二开优先看这些文件：

| 目标 | 先看这些文件 |
|---|---|
| App 入口 / 进程级单例 | `app/src/main/java/com/vaca/callmate/MainActivity.kt`, `CallMateApplication.kt` |
| 首页 / 主界面结构 | `app/src/main/java/com/vaca/callmate/ui/screens/MainTabView.kt` |
| 绑定流程 | `app/src/main/java/com/vaca/callmate/ui/screens/BindingFlow.kt` |
| 设置页 | `app/src/main/java/com/vaca/callmate/ui/screens/SettingsScreen.kt` |
| AI 分身页 | `app/src/main/java/com/vaca/callmate/ui/screens/AISecView.kt` |
| 来电 / 通话流程 | `app/src/main/java/com/vaca/callmate/features/calls/` |
| 外呼聊天 / AI 配置向导 | `app/src/main/java/com/vaca/callmate/features/outbound/OutboundChatController.kt` |
| 后端注册 / JWT | `app/src/main/java/com/vaca/callmate/core/network/BackendAuthManager.kt` |
| 设备固件升级 | `app/src/main/java/com/vaca/callmate/core/firmware/FirmwareUpdateService.kt` |
| 本地轻量配置 | `app/src/main/java/com/vaca/callmate/data/AppPreferences.kt` |
| 默认代接策略 | `app/src/main/java/com/vaca/callmate/data/ProcessStrategyStore.kt` |
| Room 数据库 | `app/src/main/java/com/vaca/callmate/data/local/` |
| 外呼模板默认值 | `app/src/main/java/com/vaca/callmate/data/outbound/OutboundDefaultTemplates.kt` |
| Prompt 资源 | `app/src/main/assets/prompts/` |
| 应用权限、深链、前台服务 | `app/src/main/AndroidManifest.xml` |

---

## 4. 客户最常改的内容

### 4.1 改应用名、包名、图标

常见修改点：

- `namespace` / `applicationId` / 版本号：
  - `app/build.gradle.kts`
- App 名称：
  - `app/src/main/res/values/strings.xml`
- 应用图标：
  - `app/src/main/res/mipmap-*`
- Manifest 配置：
  - `app/src/main/AndroidManifest.xml`

如果你修改了 `applicationId`，记得同步检查：

- `FileProvider` authority
- 深链 scheme / host
- 推送、后台服务、三方平台绑定配置

### 4.2 改后端地址和接口

Android 侧的后端地址同样分散在多个模块里，切环境时要一起核对。

重点文件：

- 注册 / token / 设备上报：
  - `core/network/BackendAuthManager.kt`
- 主 WebSocket：
  - `features/outbound/OutboundChatController.kt`
  - `features/calls/LiveCallIncomingWebSocket.kt`
  - `features/calls/SimulationCallController.kt`
- 摘要服务：
  - `core/network/ChatSummaryService.kt`
- 语音克隆 / 音色：
  - `core/network/SettingsVoiceRepository.kt`
- TTS filler：
  - `core/network/TTSFillerService.kt`
- 固件服务器：
  - `core/firmware/FirmwareUpdateService.kt`
  - `core/update/AppSelfUpdateService.kt`

建议统一 grep：

```bash
rg -n "echocard.xiaozhi.me|120.79.156.134|120.24.162.199|ws://|wss://|api/voice-clone|api/chat/summaries|api/firmware" app/src/main/java
```

### 4.3 改 Prompt、默认策略、引导流程

常见入口：

- Prompt 资源：
  - `app/src/main/assets/prompts/*.txt`
- AI 配置向导 / 外呼聊天：
  - `features/outbound/OutboundChatController.kt`
- 来电场景 Prompt：
  - `features/calls/LiveCallIncomingWebSocket.kt`
  - `features/calls/SimulationCallController.kt`
- 默认代接规则：
  - `data/ProcessStrategyStore.kt`
- 默认外呼模板：
  - `data/outbound/OutboundDefaultTemplates.kt`

推荐顺序：

1. 先改 `assets/prompts/`
2. 再改 `ProcessStrategyStore.defaultRules()`
3. 最后再调 `OutboundChatController.kt` 中与 prompt 发送时机相关的逻辑

### 4.4 改 UI 和本地数据

常见入口：

- 页面：
  - `ui/screens/`
- 通用主题：
  - `ui/theme/`
- DataStore 轻量配置：
  - `data/AppPreferences.kt`
- Room 表结构：
  - `data/local/`
- Repository：
  - `data/repository/`

如果要加字段或改本地结构，优先遵循现有风格：

- 短配置用 `DataStore`
- 列表、记录、模板等结构化数据用 `Room`

### 4.5 改外呼模板与联系人管理

相关文件：

- 默认模板：
  - `data/outbound/OutboundDefaultTemplates.kt`
- 模板持久化：
  - `data/local/OutboundPromptTemplateEntity.kt`
  - `data/local/OutboundPromptTemplateDao.kt`
- 模板仓储：
  - `data/repository/OutboundRepository.kt`
- 外呼 UI：
  - `ui/screens/outbound/`

---

## 5. BLE 相关改动应该怎么理解

Android 主仓目前保留了一层 `core/ble/`，但它已经不是完整 BLE 源码仓了。

客户需要这样理解：

- `core/ble/` 更像是主业务和 BLE 能力之间的桥接层
- 高风险底层 BLE 能力已经封装在私有 `callmate-ble` 制品中
- 主仓二开更适合围绕“状态消费、UI 呈现、业务编排”展开

因此，客户通常可以安全做的包括：

- 修改绑定页和设备页交互
- 调整连接状态、错误提示、按钮逻辑
- 基于已有事件流接入自己的业务规则

默认不要单独改的包括：

- GATT 服务/特征定义
- 命令字和 ACK 协议
- 音频上下行包格式
- OTA 传输协议

如果改动涉及这些内容，请按“**Android 主 App + 私有 BLE 制品 + MCU 固件**”联动评估。

---

## 6. 签名、发包与发布

### 6.1 Release 签名

当前工程会先读取：

1. `key/release_signing.properties`
2. 再由 `local.properties` 同名键覆盖

说明文档见：

- `key/README.md`
- `local.properties.example`

如果客户计划自行上架，建议在正式对外发布前确认：

- 是否沿用现有签名
- 还是替换成客户自己的签名体系

注意：

- **一旦更换签名**，已安装旧签名版本的用户将无法直接覆盖升级

### 6.2 发布脚本

仓库内已有发布脚本说明：

- `script/README.md`

其中：

- `release_apk.sh` 会自动升版并执行 `assembleRelease`
- `publish_all.sh` 会联动 MCU 和 Android 发布

如果客户只是二开验证，通常先用：

```bash
./gradlew :app:assembleDebug
```

---

## 7. 推荐的二开顺序

建议客户按这个顺序推进：

1. 先改应用标识
   - 包名、图标、App 名称、签名策略
2. 再改后端环境
   - JWT、WebSocket、摘要服务、语音克隆、固件服务
3. 再改 AI 能力
   - Prompt、默认代接策略、外呼模板
4. 最后改业务 UI
   - 首页、设置页、AI 分身页、设备页
5. BLE/协议改动放最后，单独和交付方协作

---

## 8. 交付前自检清单

至少验证下面这些项目：

1. Android Studio 同步工程成功
2. `:app:compileDebugKotlin` 成功
3. `assembleDebug` 成功
4. 蓝牙权限、麦克风权限、通知权限文案符合客户产品要求
5. 设备可以正常绑定
6. 来电流程、AI 接听、摘要、设置页、模板页可正常工作
7. 若改了后端环境，确认 JWT、WebSocket、语音克隆、OTA 都指向新环境

---

## 9. 客户需要知道的限制

- Android 主仓不包含完整 BLE 实现源码（以 `app/libs/callmate-ble-0.1.0.aar` 预编译形式随仓分发的默认方式不需要 GitHub Packages 凭据）
- 如果修改协议字段，必须同步评估 MCU 固件影响
- 如果只改了 Android 主工程代码，最终仍需要 **Android Studio 重新编译安装**
