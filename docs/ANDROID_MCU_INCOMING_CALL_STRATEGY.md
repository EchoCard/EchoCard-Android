# Android × MCU 来电感知：方案研究与实施建议

> 本文档基于对 **MCU**（`/Volumes/vaca/callmate/mcu`）、**iOS**（`/Volumes/vaca/callmate/CallMate`）、**Android**（`/Volumes/vaca/callmate/Android/CallMateAndroid`）源码与蓝牙协议事实的梳理，供产品与研发对齐「来电如何到达 MCU、再到达 App」的架构决策。

**延伸阅读（行业级架构，建议必读）**

| 文档 | 内容 |
|------|------|
| [CALL_STATE_ARCHITECTURE.md](./CALL_STATE_ARCHITECTURE.md) | 真相源重构、**CallStateAggregator**、`sync_call_state`、Call Bus 演进 |
| [HFP_TO_APP_EVT_INCOMING_CALL_STATE_MACHINE.md](./HFP_TO_APP_EVT_INCOMING_CALL_STATE_MACHINE.md) | **HFP → `APP_EVT_INCOMING_CALL`** MCU 状态机与伪代码 |
| [ANDROID_PHONE_SIDE_CALLER_METADATA.md](./ANDROID_PHONE_SIDE_CALLER_METADATA.md) | **手机侧无法依赖**：`PhoneStateListener`/广播号码、HFP 与 App 边界、实测 log |
| `core/telephony/UnifiedCallState.kt` | Android 聚合输出 sealed class |

---

## 1. 背景与问题

### 1.1 iOS 为何「成熟」

- iPhone 系统提供 **Apple Notification Center Service（ANCS）**：配件（EchoCard / MCU 侧协议栈）作为 **ANCS Client** 可订阅系统通知。
- **来电**在系统通知中心表现为 **Incoming Call 类别**；MCU 在 `ancs_handler.c` 中解析该类别与事件（Added/Removed），触发 `APP_EVT_INCOMING_CALL`，再通过 BLE 控制通道向 App 发送 `incoming_call` JSON（与 `CallMate` iOS 逻辑一致）。

### 1.2 Android 的核心差异

- **Android 不提供苹果的 ANCS 服务**，配件无法通过「与 iOS 完全相同」的 GATT 路径拿到同一类通知。
- 因此：**不能把「MCU 仅依赖 ANCS Incoming Call」在 Android 上原样复刻**；必须在 **HFP（经典蓝牙免提）**、**App 侧中继** 或 **混合方案** 中做产品级选择。

### 1.3 当前 MCU 代码中的事实（重要）

在现有 MCU 源码中，**`APP_EVT_INCOMING_CALL` 仅由 ANCS 路径投递**：

- 文件：`mcu/src/ble/ancs_handler.c`
- 条件：`noti->cate_id == BLE_ANCS_CATEGORY_ID_INCOMING_CALL` 且 `evt_id == NOTIFICATION_ADDED` 等逻辑分支内，最终 `evt.type = APP_EVT_INCOMING_CALL` → `app_notify_event`。
- `mcu/src/core/app.c` 中 `case APP_EVT_INCOMING_CALL`：调用 `protocol_send_incoming_call`，向手机 App 推送 `incoming_call`。

**结论**：若主机为 Android 且 **无 ANCS**，则 **当前固件不会走上述「发现来电 → 推 App」的同一条入口**，除非：

1. **固件扩展**：增加 **HFP 振铃/来电检测** 并合成同一套状态机；或  
2. **App → MCU 指令**：由 Android App 检测来电后，通过 BLE **显式通知 MCU**（需协议与状态机支持）。

### 1.4 当前代码快照中的“真实首跳”与回传链

上面 1.3 说的是 **MCU 内部谁负责发 `APP_EVT_INCOMING_CALL`**；但若从 **整条 Android 来电链路** 看，当前仓库里的**第一跳**已经不是 MCU，而是 **Android 手机侧通话状态**。

#### 已落地的首跳

- Android App 在 **`semi` 模式**下通过 `SemiModeHfpTelephonyBridge.kt` 监听 `PhoneStateListener`。
- 当系统进入 **`RINGING` / `OFFHOOK`** 时，`BleManager.applySemiModeHfpFromTelephony()` 会下发 **`hfp_connect`** 到 MCU。
- 这一步的作用是：**让 MCU 及时拉起经典蓝牙 HFP 链路**，从而获取后续 HFP 指示、`+CLIP` 号码、PBAP 联系人结果。

因此，当前更准确的因果顺序是：

```text
Android Telephony / PhoneStateListener
  -> App 发 hfp_connect 给 MCU
  -> MCU 建立/保持 HFP
  -> MCU 从 HFP 指示 / +CLIP / PBAP 获得来电上下文
  -> MCU 回 caller_resolved /（后续固件补齐时可再回 incoming_call）
  -> Android BLE 分发进入来电 UI / AI 会话
```

#### 不是主业务首跳的部分

- `IncomingCallDebugLogger.kt` 确实注册了 `ACTION_PHONE_STATE_CHANGED` 广播，但当前仅用于 **调试日志**，**不是**生产业务链的首跳。
- `CallMateInCallService.kt` 目前仍是占位，尚未承担正常来电的主业务入口。

#### 当前 Android 无 ANCS 场景下，最明确已落地的 MCU → App 回传入口

- 从现代码快照看，**`caller_resolved`** 是 Android 无 ANCS 场景下最明确的回传入口：
  - `bt_hfp_events.c` 解析 `BT_NOTIFY_HF_CLIP_IND`
  - `bt_pbap_contact.c` 基于号码做 PBAP 查询
  - `protocol_send_caller_resolved(...)` 把 `uid/sid/title/caller/number/is_contact` 回给 App
  - Android `BleControlDispatch.handleCallerResolved()` 会复用 `IncomingCall` 继续进入 UI/AI 链路
- 也就是说，现阶段联调时不应只盯 `incoming_call`；在 Android 上，**`caller_resolved` 可能比 `incoming_call` 更早、也更常见**。

#### 联调建议：先看这条顺序

1. Android 是否收到 `RINGING` / `OFFHOOK`
2. App 是否发出 `hfp_connect`
3. MCU 是否收到 HFP 指示并解析到 `+CLIP`
4. PBAP 是否回了 `caller_resolved`
5. Android `BleControlDispatch` 是否进入 `handleCallerResolved()` 或 `handleIncomingCall()`

---

## 2. 方案总览对比

| 方案 | 原理概要 | 可靠性 | 功耗 | 与 iOS 语义对齐 | 主要风险 |
|------|-----------|--------|------|------------------|----------|
| **A. HFP 常连** | 手机与配件 HFP 已连接，用 **RING / CIEV callsetup** 等判断振铃/通话 | 高（电话相关标准路径） | 略增待机（通常可接受） | 高（与耳机类产品一致） | OEM 对 HFP 指标细节差异；需固件整合 |
| **B. 仅通知监听** | `NotificationListenerService` 解析「来电类」通知，再经 BLE 告诉 MCU | 中低 | App 侧后台与唤醒成本 | 中（形似 ANCS） | OEM 通知样式差异大、漏报/误报、权限与杀后台 |
| **C. 混合（推荐）** | **HFP 为主**（状态+音频）；**通知为辅**（补号码/联系人） | 高 | 适中 | 最高 | 实现复杂度略高 |

---

## 3. 方案 A：HFP 一直保持连接

### 3.1 机制

- 经典蓝牙 **ACL + HFP** 保持连接；无通话时多为 **sniff/低占空比** 待机。
- 来电时：**振铃**通常伴随 **HFP 指标**（如 `callsetup`）与 **RING/CLIP**（依手机与协议栈实现）。

### 3.2 优点

- **与真实电话状态一致**，不依赖各厂商通知栏长什么样。
- 与 MCU 已有 **接听/挂断/SCO 音频** 路径天然一致（见 `mcu/src/bt/bt_hfp_events.c` 等）。
- 用户体验与 **蓝牙耳机/车载** 行业习惯一致：**常连换低延迟与稳定**。

### 3.3 缺点与对策

- **待机功耗**：比「完全断开 HFP」略高；通常远小于屏幕/蜂窝/CPU。对策：协议栈侧 **sniff 参数**、**无业务时降低活动**（芯片能力范围内）。
- **仅 HFP 不一定带齐号码/联系人**：可用 **CLIP** 或下方 **方案 C** 补全。

### 3.4 对固件的含义（若选 A 为主路径）

需在 MCU 中 **增加**（当前仓库未见从 HFP 单独触发 `APP_EVT_INCOMING_CALL` 的完整等价实现）：

- 在 **振铃可判定** 时填充 `incoming_call_info_t`（`uid` 可用自增或基于 session 的 synthetic id，需与 App 约定）。
- 与现有 **ANCS 路径** 互斥/去抖：避免 **双源** 重复上报。

---

## 4. 方案 B：Android 读系统通知再告诉 MCU

### 4.1 机制

- App 使用 **通知监听**（与现有「ANCS 验证探针」语义类似：验证管道可用性，见 `NotificationPipelineVerifier`、`CallMateNotificationListener`）。
- 解析通知 **category / text / phone**（高度依赖 OEM），再通过 BLE **自定义命令** 通知 MCU「疑似来电」。

### 4.2 优点

- 不依赖 HFP 也能尝试 **感知界面层来电提示**（演示或过渡阶段可用）。
- 与「模拟 ANCS」产品叙事接近，便于市场话术。

### 4.3 缺点（决定其不宜作为唯一真相）

- **碎片化**：系统电话、VoIP、厂商通话全屏页，通知是否出现、标题是否含号码均不一致。
- **稳定性**：省电策略、后台限制、用户关闭通知类权限会导致 **漏报**。
- **语义不等价于电话状态机**：通知消失 ≠ 通话结束的唯一条件，需与 HFP/call_state 交叉校验。
- **合规与上架**：需明确隐私说明（读取通知范围最小化）。

### 4.4 适用场景

- **补充信息**（号码、应用名）当 HFP 未带 CLIP。
- **研发阶段** 在 HFP 未打通前的 **权宜之计**，不建议作为与 iOS 同等级量产主路径。

---

## 5. 方案 C：混合架构（推荐）

### 5.1 原则

1. **主路径**：**HFP** 负责 **振铃/接听/挂断/SCO 音频** 与 MCU 状态机（与电话网一致）。
2. **辅路径**：**通知** 仅用于 **丰富 UI**（主叫号码、联系人命中）或 **HFP 未携带 CLIP 时补全**。
3. **去重与优先级**：同一物理来电，**MCU 侧或 App 侧** 需 **统一 session id（sid）/uid 规则**，避免双源重复触发 `incoming_call`。

### 5.2 与现有 Android App 的衔接

- App 侧已从 BLE **订阅 `incoming_call`**（`BleManager` / `BleControlDispatch`）；若 MCU 改为可由 **HFP 或 App 中继** 触发，需 **协议版本或 capability 协商**，避免旧版 App 行为异常。

---

## 6. 功耗：HFP 常连是否「很费电」

| 项目 | 说明 |
|------|------|
| **HFP 空闲连接** | 维持 ACL/HFP 信令，射频周期性唤醒，**有固定待机成本**，通常 **小于** 活跃通话（SCO）与大数据业务。 |
| **对比「断开」** | 断开最省，但 **重连时延与失败率** 上升，与「随时代接」产品目标冲突。 |
| **结论** | 对「代接电话」类产品，**常连 HFP 是合理默认**；若需极致省电，再考虑 **可配置策略**（如夜间断开 + 明确提示能力降级）。 |

---

## 7. 实施路线图（建议）

### 阶段 0：对齐认知（已完成）

- 文档化：**ANCS 仅 iOS 主机具备**；Android 必须 **HFP 和/或 App 中继**。

### 阶段 1：固件（MCU）

- [ ] 定义 **Android 主机** 下的 **incoming 检测源**：HFP 指标门限、与 ANCS 的互斥规则。
- [ ] 实现 **从 HFP 到 `APP_EVT_INCOMING_CALL`** 的状态机（若当前仅有 ANCS，则本项为 **必选项** 方可与 Android 对齐）。
- [ ] **uid/sid** 生成规则与 App、后端一致，避免与 iOS ANCS uid 语义混淆（文档化）。

### 阶段 2：Android App

- [ ] **Telecom / PhoneStateListener / 既有 BLE** 与 MCU 新指令对齐（以协议定稿为准）。
- [ ] 保持 **通知管道** 用于探针与 **可选补全**，不单独作为唯一来电源。
- [ ] 设置与文案：**说明 HFP 与通知各自作用**，降低用户「为何与 iOS 设置项不完全一致」的困惑。

### 阶段 3：验收标准

- [ ] 主流 Android 机型：**来电 → MCU → App `incoming_call` → UI** 全链路成功率与 iOS 对标（定义允许偏差）。
- [ ] 异常：飞行模式、蓝牙关闭、双机切换音频路由时的降级策略。

---

## 8. 风险登记

| 风险 | 缓解 |
|------|------|
| OEM HFP 行为差异 | 机型矩阵测试；指标门限可配置 |
| 双源重复上报 | MCU 去重；统一 sid |
| 用户未授予通知监听 | 辅路径降级，主路径仍靠 HFP |
| 固件与 App 版本不一致 | 协议版本号 + 能力协商 |

---

## 9. 仓库内相关参考（便于联调）

| 层级 | 路径/说明 |
|------|-----------|
| MCU ANCS 来电 | `mcu/src/ble/ancs_handler.c`（`BLE_ANCS_CATEGORY_ID_INCOMING_CALL`） |
| MCU 来电后推 App | `mcu/src/core/app.c`（`APP_EVT_INCOMING_CALL` → `protocol_send_incoming_call`） |
| MCU HFP 事件 | `mcu/src/bt/bt_hfp_events.c` |
| Android BLE 分发 | `app/.../ble/BleControlDispatch.kt`（`incoming_call`） |
| Android 通知管道验证 | `core/notifications/NotificationPipelineVerifier.kt`、`CallMateNotificationListener.kt` |
| iOS 对照 | `CallMate/Core/Bluetooth/CallMateBLEClient+ControlJSON.swift` 等 |

---

## 10. 结论（给决策的一页纸）

1. **仅「Android 看通知告诉 MCU」不足以作为与 iOS 同级的量产主方案**（碎片化、稳定性、语义不等价）。
2. **HFP 常连 + 在 MCU 侧补齐「来电发现 → `incoming_call`」** 是与 **真实通话** 对齐的正路；**通知** 适合作为 **增强与补全**。
3. **当前 MCU 仅以 ANCS 明确触发 `APP_EVT_INCOMING_CALL`**，但 Android 端已存在一条**手机侧 Telephony 首跳 -> `hfp_connect` -> MCU HFP/CLIP/PBAP -> `caller_resolved` 回传**的已落地链路；若要与 iOS 的 `incoming_call` 语义完全对齐，仍需继续补齐固件侧 HFP → `APP_EVT_INCOMING_CALL` 状态机。

---

*文档版本：1.0 · 基于仓库快照整理，后续随协议与固件迭代更新。*
