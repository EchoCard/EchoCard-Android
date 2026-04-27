# 签名

客户交付仓默认 **不包含** `release.keystore` 与 `release_signing.properties`。

当前 `app/build.gradle.kts` 的行为是：

- 本地没有签名材料时，`debug` 构建会自动回落到 Android 默认 debug signing
- 只有在检测到完整的 release 签名配置后，才会复用自定义签名

如果你需要生成正式包，请自行提供以下配置之一：

1. 在 `key/` 下放入 `release.keystore` 与 `release_signing.properties`
2. 或在根目录 `local.properties` 中覆盖以下键：

```properties
release.storeFile=key/release.keystore
release.storePassword=
release.keyAlias=
release.keyPassword=
```

不要将真实签名信息提交到仓库。
