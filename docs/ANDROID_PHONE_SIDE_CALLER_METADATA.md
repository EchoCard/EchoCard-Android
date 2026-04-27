# Android 手机侧：来电号码 / 联系人元数据 — 调研结论

> **结论（产品级）**：普通第三方 App **不应**依赖「Android 手机系统 API」在**振铃瞬间**拿到可靠来电号码，并据此区分联系人与非联系人。  
> **号码与联系人语义**应以 **MCU / 经典蓝牙 HFP（HF 端）/ BLE 上报** 为真相源；本文记录实测与依据，避免后续重复踩坑。

**相关架构文档**：[ANDROID_MCU_INCOMING_CALL_STRATEGY.md](./ANDROID_MCU_INCOMING_CALL_STRATEGY.md)、[HFP_TO_APP_EVT_INCOMING_CALL_STATE_MACHINE.md](./HFP_TO_APP_EVT_INCOMING_CALL_STATE_MACHINE.md)

---

## 1. 实测：`PhoneStateListener` 与 `PHONE_STATE` 广播

### 1.1 实验设置

- 权限：`READ_PHONE_STATE`、运行时 `READ_CONTACTS`（用于在**有号码**时 `PhoneLookup` 解析联系人名）。
- 实现：`core/telephony/IncomingCallDebugLogger.kt`，logcat tag：`IncomingCallDebug`。
- 设备：真实 Android 机（与 OEM 相关，但 Google 方向自 Android 10 起对第三方限制趋同）。

### 1.2 样例日志（振铃 → 空闲）

以下片段为真机抓取；**振铃时 `number` 与 `EXTRA_INCOMING_NUMBER` 均为空**。

```
PhoneStateListener: state=RINGING number=
IncomingCallDebug       I    RINGING: contactName=(null) readContacts=true
Broadcast PHONE_STATE: extraState=RINGING EXTRA_INCOMING_NUMBER=(null)
PhoneStateListener: state=IDLE number=
Broadcast PHONE_STATE: extraState=IDLE EXTRA_INCOMING_NUMBER=(null)
```

### 1.3 解读

| 观测 | 含义 |
|------|------|
| `PhoneStateListener` 第二参数 `phoneNumber` 为空 | 与 Android 10+ 对非特权应用的来电号码限制一致；**不能**据此做联系人查询。 |
| `EXTRA_INCOMING_NUMBER=(null)` | 广播侧同样不提供号码（历史上曾依赖该 extra，现对普通应用基本不可用）。 |
| `readContacts=true` 但 `contactName=(null)` | **不是**通讯录权限无效；根因是**没有号码**，`ContactsContract.PhoneLookup` 无从查起。 |

**工程含义**：在「默认电话应用 / Telecom 特权角色」等路径之外，**手机侧 Telephony 回调无法作为「来电号码 → 是否联系人」的数据源**。

---

## 2. 系统策略背景（为何如此）

- 自 **Android 10（API 29）** 起，Google 强化对 **通话记录与来电标识** 的隐私管控；第三方应用通过 `TelephonyManager` / 隐式来电广播获取号码的能力被大幅收紧。
- **READ_CONTACTS** 只解决「给定号码 → 显示名」；**不**创造号码本身。
- 若需系统级来电能力，通常涉及 **默认拨号应用（Default Dialer）**、**Call Screening** 等，与「普通配件配套 App」形态与合规成本不匹配，**本文档不展开为实现承诺**。

---

## 3. HFP：号码从哪里来、手机 App 能否读

### 3.1 协议事实

在 **HFP（Hands-Free Profile）** 中，来电号码常通过 **+CLIP**、呼叫列表 **+CLCC** 等 **AT 结果码** 在链路上传递；典型拓扑是 **手机 = Audio Gateway (AG)**，**耳机 / 车机 / 配件 = Hands-Free (HF)**。

### 3.2 与「手机上的 CallMate App」的关系

- **+CLIP / +CLCC 的接收端是 HF 设备上的协议栈**（固件），不是「同一只手机里的任意 Java/Kotlin 应用」可调用的公开 API。
- Android 对 **本机作为 AG** 的场景，**没有**向第三方 App 暴露「读取当前 HFP AT 流 / 解析 CLIP」的稳定公共接口；号码是否发给 HF、以何种时机发，由系统蓝牙栈与手机侧电话栈实现。
- **`BluetoothHeadsetClient`（HFP Client）** 适用于 **本机作为 HF 去连别的 AG** 等场景，与「手机 + 自家配件（AG+HF）」常见拓扑不一致，**不能**当作「手机 App 读来电号码」的通用解。

**工程含义**：**区分联系人与非联系人**若依赖「号码」，该号码应来自 **HF 侧固件解析 HFP 后经 BLE 上报**，或由 **MCU 其它约定字段**（如设备侧会话 id）在 App 内映射；**不要**指望手机侧 App 单独从 HFP API 拿到与系统电话完全一致的实时号码。

---

## 4. 与 BLE / GATT 的补充说明（为何与「系统已连接蓝牙」不一致）

- 系统状态栏「蓝牙已连接」常指 **经典蓝牙（如 HFP/A2DP）** 或聚合状态；**BLE GATT** 是否连接是另一条会话。
- 回连策略上，业界常见结论（如 [RxAndroidBle Wiki: Cannot connect](https://github.com/dariuszseweryn/RxAndroidBle/wiki/FAQ:-Cannot-connect)）：**关过蓝牙后仅靠 MAC 直连可能失败**、**扫描未停就发起连接**在部分机型上会失败等——这与「来电元数据」主题独立，但同属 **不要假设手机侧栈行为与 UI 文案一致**。

---

## 5. 产品决策（归档）

| 项目 | 决策 |
|------|------|
| 手机侧实时来电号码 | **不作为**联系人/非联系人策略的依赖来源。 |
| 联系人判定所需数据 | 以 **设备经 BLE 上报** + 协议约定为准；必要时由 **HFP HF 端** 提供号码或等价标识，再经 App 与通讯录匹配。 |
| 调试日志 `IncomingCallDebug` | 仅用于验证系统限制；正式产品可关闭 `AppFeatureFlags.incomingCallDebugLoggingEnabled`。 |

---

## 6. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-03-21 | 初版：基于真机日志与公开资料整理；确认放弃纯手机侧号码方案。 |
