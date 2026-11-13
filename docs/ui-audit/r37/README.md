# R37 · P2 发布链路 + 性能/内存第一批（2026-11-13）

本轮按用户指定的“第 1、2 步”完成：

- **第 1 步**：P2-7 发布配置、P2-8 CI + instrumented、P2-9 release 日志门禁、P2-10 文档收口；
- **第 2 步**：P2-1 图片异步解码/按槽位、P2-2 页面级 ViewModelStore、P2-4 读页 renderModel remember，
  以及 P2-6b 的设置写入串行化 / 地点筛选防抖 / 页内搜索移出主线程。

## 证据

| 文件 | 内容 |
| --- | --- |
| `release-signing.txt` | release APK 的 `apksigner verify --print-certs` 输出（自签名测试证书） |
| `test-summary.txt` | 631 条 JVM 单测 + 7 条 instrumented 的汇总，以及 release APK/AAB 体积 |

设备：Pixel_10_Pro AVD（API 36）。

## 实测

- 签名 release APK **4.2 MB**（debug 37 MB），AAB **6.0 MB**。
- release 冷启动正常；release 下 logcat 不再出现 `JustDiaryApplication created` 这类 info 日志
  （P2-9：`Log` 的 debug/info 已按 `BuildConfig.DEBUG` 抑制）。
- release 设置页“导出备份（含设置）”成功写出 `.jdiary`（验证 R8 没打断 kotlinx.serialization）。
- `./gradlew :app:connectedDebugAndroidTest` → **7/7 通过**。
- `./gradlew :app:testDebugUnitTest` → **631/631 通过**。
- `tools/verify_docs.py` / `tools/verify_sqlite_schema.py` 通过。

## 说明

- 本轮签名用的 `signing/justdiary-release.jks` 只是本机测试证书，已被 `.gitignore` 忽略；
  正式发版请用 `keystore.properties` / `JUSTDIARY_*` 环境变量注入真实证书（见 `docs/release.md`）。
- 没有做 P2-11/12/13 的无障碍与真机视觉走查（用户明确本轮不做）。
