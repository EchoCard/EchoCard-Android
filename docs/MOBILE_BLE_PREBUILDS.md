# 移动端 BLE 预编译依赖（对公开客户端工程）

**BLE 实现源码不随**本仓库在 GitHub 上公开；公开分支只包含**可公开的预编译** AAR，见 `app/libs/callmate-ble-0.1.0.aar`。

- `app/build.gradle.kts` 使用 `implementation(files("libs/callmate-ble-0.1.0.aar"))`，不依赖任何私有 Git 或 Package 的 BLE 拉取方式即可在默认配置下完成编译。
- 若内部团队用私有 `EchoCardAndroidBLE` 与 GitHub Packages 做发版，那是**内部流水线**，不写入本仓对外文档的**唯一**集成路径，以免公开历史与文档中残留「需私有包权限才拿到 BLE 源码/制品」的表述。
