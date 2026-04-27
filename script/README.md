# 发布脚本

- **`release_apk.sh`**：运行前会**自动**把 `app/build.gradle.kts` 里 **`versionCode` +1**，并按规则递增 **`versionName`**（`x.y` → `x.y.1`，`x.y.z` → `x.y.(z+1)`）再执行 `assembleRelease`，**默认会上传**到固件服务。`SKIP_BUMP=1` 不修改版本号；`SKIP_UPLOAD=1` 不上传（仅编译）。签名依赖 `key/release_signing.properties` 或 `local.properties`。`CLEAN=1` 先 clean。上传参数：`device=callmate-android`，`version` = `versionCode` 字符串。
- **`publish_all.sh`**：先上传 MCU `.bin`，再上传 Android APK。APK 的 `version` 从 **`app/build.gradle.kts` 的 `versionCode`** 读取；若需先升版再传，可先跑 `release_apk.sh` 或手动改 `versionCode`。

MCU 单独上传仍可使用仓库 `firmware_server/upload_firmware.sh`。
