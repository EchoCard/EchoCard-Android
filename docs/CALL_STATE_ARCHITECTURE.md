# Call 状态架构：真相源、聚合器与同步总线

> 在 **Android** 上，**MCU 不能作为来电的「唯一真相源」**；**系统 + App 协调层** 才是真相，MCU 是 **执行终端**。本文档与 `ANDROID_MCU_INCOMING_CALL_STRATEGY.md` 互补，偏 **工程落地与行业级稳定性**。

---

## 1. 隐性风险：真相源错位

| 平台 | 现实链路 | MCU 角色 |
|------|-----------|----------|
| **iOS** | 系统 → **ANCS** → MCU | 可视为 **来电发现** 的第一跳，与系统通知强一致 |
| **Android** | 系统 → **HFP / Telecom / 通知** → **App 聚合** → MCU | MCU 只能是 **聚合后的执行端**，不是「系统来电」的定义者 |

### 推荐心智模型

```
❌ 旧隐含模型（Android 易翻车）
   MCU = 状态机核心，App = UI

✅ 推荐模型（Android）
   Phone（系统电话抽象）= 真相
   App = 状态协调器（Coordinator / Aggregator）
   MCU = 执行终端（Executor：音频、BLE 信令、与固件策略）
```

**结论**：Android 上应 **禁止**「HFP 报一次 + Notification 再报一次」未经聚合就直达 MCU；**上游只产生一条「会话级」决策流**。

---

## 2. Call State Aggregator（调用状态聚合器）

### 2.1 职责

统一 **三类输入**，输出 **单一** `UnifiedCallState`（见下文 sealed class），**仅此状态** 经规则转换后下发 MCU（BLE）。

| 来源 | 类型 | 可信度 | 典型用途 |
|------|------|--------|----------|
| **HFP** | 物理电话指标（CIEV call / callsetup、RING、CLIP） | **最高**（电话语义） | 振铃、接通、挂断主判断 |
| **Telecom / InCallService** | 系统级通话状态 | **高**（干净，但权限与机型差异） | 校准、无 HFP 时的补充 |
| **Notification** | UI 层提示 | **低** | 号码/联系人 **补全**，**禁止**单独触发 `incoming_call` |

### 2.2 统一输出（Android 侧）

```kotlin
sealed class UnifiedCallState {
    data object Idle : UnifiedCallState()
    data class Ringing(val number: String?, val source: CallSignalSource) : UnifiedCallState()
    data object Offhook : UnifiedCallState()
    data object Disconnected : UnifiedCallState()
}
```

（实现见 `core/telephony/UnifiedCallState.kt`。）

### 2.3 聚合规则（原则）

1. **单会话令牌**：每个物理通话使用 **`sid`（或 `callSessionId`）** 在 App 内唯一；聚合器内 **只存在一个活跃 `sid`**（或严格队列）。
2. **优先级**：`HFP/Telecom` 冲突时以 **HFP 指标 + 时间窗口** 为准；Notification 仅填充 **缺失字段**，不产生第二条 `incoming_call`。
3. **禁止双事件**：**不允许** Notification 与 HFP 各向 MCU 发一次 `incoming_call`；若曾依赖「去重补丁」，应上移到 **Aggregator 层消灭双源**。

---

## 3. HFP 能力边界（≈ 80%「类 ANCS」电话语义）

在 **电话语义** 上，HFP **比 ANCS 更贴近真实通话状态**（ANCS 是「通知」，HFP 是「通话控制面」）。

| 信号 | 含义（典型） |
|------|----------------|
| RING | 振铃 |
| +CIEV: callsetup=1 | 来电建立中（incoming alerting，依 AG 实现） |
| +CIEV: call=1 | 存在活跃呼叫 |
| CLIP | 主叫号码（多机型支持，非 100%） |

**结论**：**纯 HFP 可覆盖大部分「来电/接通/挂断」状态机**；号码与联系人可 **CLIP + 通讯录** 或 **Notification 补充**。

---

## 4. 可恢复状态同步（非纯事件）

### 4.1 问题场景

| 场景 | 风险 |
|------|------|
| 来电已 HFP 振铃，**BLE 未连** | MCU 收不到 |
| MCU 已 `INCOMING`，**App 未连 BLE** | UI 无展示 |
| 用户 **秒挂** | MCU / App 仍停留在 RINGING |

### 4.2 要求

- **不仅依赖事件流（event-based）**，必须有 **state-based 同步**。
- **每次 BLE 控制通道就绪 / 重连** 后，App → MCU（或双向）携带 **当前聚合状态快照**。

### 4.3 建议 JSON（示例）

```json
{
  "type": "sync_call_state",
  "sid": "uuid-or-app-session-id",
  "state": "RINGING",
  "number": "+86138xxxx",
  "version": 1
}
```

- **`state`** 与 `UnifiedCallState` 对齐（字符串枚举约定）。
- MCU 侧收到后 **对齐本地会话**（若 `sid` 一致则刷新；若本地无会话则 **补发** 等价于 `incoming_call` 的恢复路径——具体以固件协议定稿为准）。

---

## 5. 跨设备通话状态总线（演进方向）

长期可演进为 **Call Bus**：

```
Phone
 ├── HFP
 ├── Telecom
 └── Notification（仅增强）
        ↓
   CallState Aggregator（Android App）
        ↓
   BLE /（可选）Cloud
        ↓
 ├── MCU（执行）
 ├── iOS App
 ├── Android App
 └── 其他终端
```

价值：同一 **会话级 `sid` 生命周期** 在多端一致，而不是「各端各猜」。

---

## 6. 行业级体验检查清单（硬指标）

| 项 | 目标 | 手段 |
|----|------|------|
| 来电延迟 | **&lt; 200ms 感知**（产品目标，需实测） | HFP 常连、减少 BLE 队列阻塞 |
| 状态不乱 | **无重复 incoming**、无「已挂仍响」 | Aggregator + `sid` + 状态同步 |
| 无感恢复 | 蓝牙断连再连 | `sync_call_state` + App UI 恢复 |

---

## 7. 文档与代码索引

| 内容 | 位置 |
|------|------|
| Aggregator + UnifiedCallState（Kotlin 骨架） | `app/src/main/java/com/vaca/callmate/core/telephony/` |
| HFP → `APP_EVT_INCOMING_CALL` MCU 状态机（代码级设计） | `docs/HFP_TO_APP_EVT_INCOMING_CALL_STATE_MACHINE.md` |
| Android vs iOS / MCU 基础策略 | `docs/ANDROID_MCU_INCOMING_CALL_STRATEGY.md` |

---

*版本：1.0 — 与产品评审、固件协议、权限清单同步迭代。*
