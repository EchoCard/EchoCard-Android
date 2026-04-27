# MCU：HFP → `APP_EVT_INCOMING_CALL` 状态机（代码级设计）

> 目标：在 **无 ANCS**（Android 主机）或 **ANCS 与 HFP 并存**（iOS）时，由 **HFP AG 指标** 可靠产生 **至多一次** 的 `APP_EVT_INCOMING_CALL`，并与现有 `app.c` / `protocol_send_incoming_call` 衔接。  
> 本文档为 **固件设计说明**；具体符号名以 `mcu` 仓库实现为准。

---

## 1. 背景与约束

### 1.1 现状（源码事实）

- **ANCS 路径**：`ancs_handler.c` 在 `BLE_ANCS_CATEGORY_ID_INCOMING_CALL` + `NOTIFICATION_ADDED` 时置 `current_call.uid = noti_uid` 并发 `APP_EVT_INCOMING_CALL`。
- **HFP 路径**：`bt_hfp_events.c` 大量处理 `hfp_call_indicator` / `hfp_callsetup_indicator`，但 **当前未** 在「纯来电振铃」处 **独立、去重地** 触发 `APP_EVT_INCOMING_CALL`（与 ANCS 并列的第一入口）。

### 1.2 设计目标

1. **单会话单声**：同一物理来电，**只投递一次** `APP_EVT_INCOMING_CALL`（除非明确「会话被废弃后新来电」）。
2. **与 ANCS 共存**：iOS 上 **ANCS 往往先到**；HFP 随后到。**禁止** 第二次 `APP_EVT_INCOMING_CALL` 导致 App 收到两次 `incoming_call`。
3. **Android 主机**：无 ANCS 时，**仅 HFP**（+ 可选 App 下发 `sync_call_state`）驱动 `APP_EVT_INCOMING_CALL`。

---

## 2. HFP 指标（AG → HF）

以下与现有日志一致（见 `bt_hfp_events.c` 注释）：

| `callsetup` (type 3) | 常见含义 |
|----------------------|----------|
| 0 | 无 incoming/outgoing setup |
| 1 | **Incoming call process**（来电振铃阶段，依 AG） |
| 2,3 | 外呼相关 setup（与 outgoing 逻辑相关，见现有代码） |

| `call` (type 2) | 含义 |
|-----------------|------|
| 0 | 无活跃呼叫 |
| 1 | 存在活跃呼叫 |

**工程经验**（与现有代码一致）：**Latency / 部分场景** 下会出现 `(call=0, setup=1)` 或 `(call=1, setup=0)` 的组合，需 **防抖** 与 **与 ANCS 互斥**。

---

## 3. 状态机（MCU 侧建议）

### 3.1 新增/使用内部子状态：`hfp_incoming_fsm_t`

```c
typedef enum {
    HFP_INCOMING_FSM_IDLE = 0,           /* 无 HFP 侧「待处理来电会话」 */
    HFP_INCOMING_FSM_CANDIDATE,          /* 检测到振铃候选，待确认或等 ANCS */
    HFP_INCOMING_FSM_ARMED,              /* 已发或即将发 APP_EVT_INCOMING_CALL */
    HFP_INCOMING_FSM_CONSUMED,           /* 本会话已交给 app 状态机（INCOMING_CALL / ANSWERED） */
} hfp_incoming_fsm_t;
```

可挂在 `app_env_t` 或 `static` 于 `bt_hfp_events.c`。

### 3.2 与 ANCS 的互斥（关键）

| 条件 | 行为 |
|------|------|
| `env->state == APP_STATE_INCOMING_CALL` **且** `current_call.uid != 0`（来自 ANCS） | **禁止** HFP 再发 `APP_EVT_INCOMING_CALL`；可选：仅 **补全** `current_call.number`（若 CLIP 到达且原为空）。 |
| ANCS 未触发、仅 HFP 振铃 | 允许进入 **HFP 主导** 流程并发 `APP_EVT_INCOMING_CALL`，`uid` 使用 **合成 UID**（见 §4）。 |

### 3.3 转移表（简化）

**事件**：`BT_NOTIFY_HF_INDICATOR_UPDATE` 更新 `hfp_call_indicator`、`hfp_callsetup_indicator`。

```
[IDLE]
  callsetup: 0->1 && call==0 && HFP_CONNECTED && !latency_test
  && !(APP_STATE_INCOMING_CALL && uid_from_ancs)
    -> [CANDIDATE]  (可选：短防抖 30~80ms)

[CANDIDATE]
  仍满足「来电振铃」且未与 ANCS 冲突
    -> [ARMED] -> app_notify(APP_EVT_INCOMING_CALL) 一次
    -> env->state 将由 app.c 置为 INCOMING_CALL

[ARMED]
  callsetup 回到 0 且 call==0 且未接通
    -> 走现有「未接/结束」路径（与现有 protocol_send_call_state ENDED 等一致）
  或 ANCS/App 已接管
    -> [CONSUMED] / IDLE
```

**禁止**：在 `[ARMED]` 内对 **同一会话** 再次 `APP_EVT_INCOMING_CALL`。

### 3.4 与现有 `APP_EVT_CALL_ANSWERED` / `CALL_ENDED` 的衔接

- 保持 **现有** `bt_hfp_events.c` 对 `CALL_ANSWERED`、`call=0/setup=0` 结束逻辑。
- 新增逻辑 **只** 在「**尚未** 因 ANCS 进入 `INCOMING_CALL`」时补 **第一条** `APP_EVT_INCOMING_CALL`。

---

## 4. 合成 `uid`（Android / 纯 HFP）

`incoming_call_info_t.uid` 当前语义为 **ANCS notification uid**。纯 HFP 无 ANCS uid 时建议：

- **`uid = 0x80000000 | (session_counter & 0x7FFFFFFF)`**（置高位区分「非 ANCS」），或  
- **单调递增 `g_hfp_call_uid`**（32-bit），在日志与 App 协议中标注 `source=hfp`。

App 侧 Aggregator 应使用 **同一 `sid`** 与 MCU 对齐（见 `CALL_STATE_ARCHITECTURE.md`）。

---

## 5. 伪代码（放入 `BT_NOTIFY_HF_INDICATOR_UPDATE` 分支末尾，示意）

```c
static void hfp_try_emit_incoming_from_ag_indicators(app_env_t *env,
    uint8_t prev_call, uint8_t prev_setup,
    uint8_t call, uint8_t setup)
{
    if (env->latency_test_mode)
        return;

    /* ANCS already owns this ring */
    if (env->state == APP_STATE_INCOMING_CALL && env->current_call.uid != 0 &&
        (env->current_call.uid & 0x80000000u) == 0) {
        /* optional: merge CLIP into number */
        return;
    }

    /* Incoming alerting: 0->1 on setup, call still 0 — typical ring */
    int incoming_ring = (call == 0 && setup == 1 && prev_setup == 0);

    if (!incoming_ring)
        return;

    if (g_hfp_incoming_fsm == HFP_INCOMING_FSM_ARMED)
        return;

    g_hfp_incoming_fsm = HFP_INCOMING_FSM_ARMED;

    memset(&env->current_call, 0, sizeof(env->current_call));
    env->current_call.uid = next_synthetic_hfp_uid();
    /* title/caller/number from CLIP buffer if already parsed in AT path */

    app_event_t evt = { .type = APP_EVT_INCOMING_CALL, .param = env->current_call.uid };
    app_notify_event(&evt);
}
```

**注意**：真实实现需与 **CLIP 到达时序**、**双卡**、**callsetup 抖动** 联合调试；上表为 **结构** 而非最终阈值。

---

## 6. 与 BLE `sync_call_state` 的配合

- App 重连后下发 `sync_call_state`：若 MCU 处于 `HFP_INCOMING_FSM_IDLE` 但手机仍振铃，可由 App **补一次** 等价会话（或触发 MCU **软重放** `APP_EVT_INCOMING_CALL` —— **二选一**，避免重复，以协议版本固定）。

---

## 7. 验收用例（固件）

| # | 场景 | 期望 |
|---|------|------|
| 1 | Android + 仅 HFP | 振铃后 **恰好 1 次** `incoming_call`（BLE） |
| 2 | iOS + ANCS 先到 | ANCS 已 `INCOMING` 后 HFP 振铃 | **不再** 第二次 `APP_EVT_INCOMING_CALL` |
| 3 | 秒挂 | `call/setup` 回 0 | `CALL_ENDED` / `ended`，无卡死 RINGING |
| 4 | BLE 晚连 | App 发 `sync_call_state` | MCU / UI 恢复一致 |

---

## 8. 相关文件（mcu）

| 文件 | 作用 |
|------|------|
| `src/ble/ancs_handler.c` | ANCS → `APP_EVT_INCOMING_CALL` |
| `src/bt/bt_hfp_events.c` | CIEV / 指标 → 建议 **在此文件或子模块** 实现 `hfp_try_emit_incoming_*` |
| `src/core/app.c` | `APP_EVT_INCOMING_CALL` → `protocol_send_incoming_call` |

---

*本状态机为工程评审稿；合入前需与硬件团队走完整机型矩阵与协议版本号。*
