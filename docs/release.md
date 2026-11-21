# 发布构建（P2-7）

## 版本号

只改根目录 `version.properties`：

```properties
VERSION_CODE=3
VERSION_NAME=1.2
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

## GitHub Releases 与应用内更新

更新检查使用公开仓库 `SeanCSLegion/JustGDiary` 的 GitHub Releases API，用户从“设置 → 关于 → 检查更新”手动启动检查。GitHub 仓库需要保持 **Public**，最新正式版必须有名为 **`JustGDiary-${VERSION_NAME}.apk`** 的 APK 资产（例如 `JustGDiary-1.2.apk`）；兼容固定名 `JustGDiary.apk`。标签用 `v主.次[.修订]`，并与 APK 的 `versionName` 完全一致。不要把预发布版本作为最新正式版。

发布前：

1. 将 `version.properties` 的 `VERSION_CODE` 单调加一，并把 `VERSION_NAME` 改成新版本；更新代码中版本标签仍须数值递增。
2. 用正式签名密钥执行 `./gradlew :app:assembleRelease`。**必须使用与已发布版本相同的签名证书**；否则已安装用户无法覆盖升级。
3. 运行 `python3 tools/verify_release_apk.py app/build/outputs/apk/release/app-release.apk`。检查会验证 APK 容器、包名、版本与签名。
4. 在 GitHub 创建正式 Release，标签为 `v${VERSION_NAME}`，上传验证过的 APK 并命名为 `JustGDiary-${VERSION_NAME}.apk`。发布后通过应用内检查下载并校验 APK，再在 Android 安装器中确认升级。

应用在下载完成后检查资产大小、GitHub 提供的 SHA-256（若有）、APK ZIP/Manifest、applicationId、versionName、递增的 versionCode 和当前安装证书。未知来源安装授权由 Android 系统按应用单独管理；授权后仍须在系统安装器确认。APK 只写入应用私有缓存，并由 `FileProvider` 授权系统安装器读取。

本机完整检查（连接 Android 设备/模拟器时）：

```bash
./gradlew :app:assembleRelease
python3 tools/verify_release_apk.py app/build/outputs/apk/release/app-release.apk
```

验证下载时，先在公开 Release 使用测试版本资产完成一次应用内下载与校验，再确认安装器显示包名 `com.cov.justdiary` 和预期版本。不要用临时测试密钥发布正式更新。

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
