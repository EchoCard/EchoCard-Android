# EchoCard Android

EchoCard 的 Android 客户二次开发仓。

当前仓库已经把对外交付名称切到 `EchoCard`，并将 BLE 能力封装为预编译 AAR（已内置在 `app/libs/`）。为了兼容现有代码，部分内部 package / class / Gradle module 仍保留 `CallMate` 命名，这不影响正常二开和编译。

## 快速开始

无需任何 token 或私有仓库权限，开箱即用：

1. 克隆本仓库
2. 进入仓库执行：

```bash
./gradlew :app:assembleDebug
```

默认情况下，没有 release 签名材料也可以完成 debug 构建。BLE 库已作为本地 AAR 内置，无需额外配置。

**复合构建 `android-ble`（使用仓库内 `../android-ble` 源码时）**：Gradle 通过 `includeBuild` 单独配置子工程，**需要在 `android-ble/local.properties` 中写入 `sdk.dir=...`**（与 `android/local.properties` 相同，指向本机 Android SDK）。该文件已列入 `android-ble/.gitignore`，需各自本机维护。

## 文档

- `docs/ANDROID_SECONDARY_DEVELOPMENT_GUIDE.md`
- `docs/MOBILE_BLE_PREBUILDS.md`

## 交付边界

- 可直接二开：UI、业务流程、本地数据、提示词、后端地址、外呼模板
- 私有封装：BLE 协议、OTA 传输、与 MCU 强绑定的控制命令和音频链路
