# 发布构建（P2-7）

## 版本号

只改根目录 `version.properties`：

```properties
VERSION_CODE=2
VERSION_NAME=1.1
```

`versionCode` 必须单调递增；`versionName` 用语义化版本。`app/build.gradle.kts` 不再写死这两个值。

## 签名

正式包只从以下二选一读取，**密钥文件不入库**：

1. `keystore.properties`（复制 `keystore.properties.example`，已被 `.gitignore` 忽略）；
2. 环境变量 `JUSTDIARY_STORE_FILE` / `JUSTDIARY_STORE_PASSWORD` / `JUSTDIARY_KEY_ALIAS` / `JUSTDIARY_KEY_PASSWORD`。

四项缺任意一项时 release 仍可构建，但产物**未签名**（CI 上应视为失败）。

```bash
./gradlew :app:assembleRelease   # APK
./gradlew :app:bundleRelease     # AAB（Play 用）
```

产物在 `app/build/outputs/apk/release/` / `app/build/outputs/bundle/release/`。

## R8 / 资源压缩

release 已开 `isMinifyEnabled = true` + `isShrinkResources = true`；keep 规则在
`app/proguard-rules.pro`，覆盖 kotlinx.serialization、WorkManager Worker、清单入口与反射点。
每次改依赖/新增 `@Serializable` 后跑一次 `assembleRelease` + 真机冒烟。

## CI

`.github/workflows/android.yml` 跑：

- `assembleDebug` + `testDebugUnitTest`；
- `tools/verify_sqlite_schema.py`、`tools/verify_docs.py`；
- 独立 job 用 emulator 跑 `connectedDebugAndroidTest`。

签名 secrets 只在发版 job 注入；PR 上不需要。
