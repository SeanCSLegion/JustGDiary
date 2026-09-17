# JustGDiary

JustGDiary 是日记应用 **[JustADiary](../JustADiary)** 的 Android 版本。本仓库用于存放该应用的 Android 端实现，iOS 端的代码与后续迁移工作在其他仓库中进行（**本仓库即项目根目录，不再嵌套同名子目录**）。

> 当前状态：**仓库初始化阶段**。仅包含 `.gitignore`、`README` 等基础文件，尚未包含应用代码；Android 工程结构与技术栈将在迁移阶段落地。

## 关键约定

| 项目 | 约定 |
| --- | --- |
| 应用包名（applicationId） | `com.cov.justdiary` |
| 与 iOS 版的关系 | 包名与 iOS 版 **完全一致**，便于跨端数据/服务对接 |
| 默认分支 | `main` |

> 注意：包名固定为 `com.cov.justdiary`。虽然仓库名为 JustGDiary，但**不要**将包名改成 `com.cov.justgdiary` 或任何其他变体。

## 项目定位

- 参考 [JustADiary](../JustADiary) 的产品形态与功能设计，实现 Android 原生版本。
- 与 iOS 版保持包名、数据结构与业务语义上的一致，便于后续统一维护与迁移。

## 目录结构

```
JustGDiary/
├── .gitignore
├── .gitattributes
├── .editorconfig
└── README.md
```

Android 工程（Gradle、`app` 模块、源码目录等）将在迁移阶段加入。

## 环境要求（预计）

以下版本将在迁移时确认并写入 `gradle/libs.versions.toml`：

- Android Studio（最新稳定版）
- JDK 17 或更高
- Android Gradle Plugin / Kotlin：待定
- 最低支持版本 `minSdk`：待定

## 构建

尚无应用代码，暂不可构建。

```bash
# 迁移完成后形如：
# ./gradlew assembleDebug
```

## 相关仓库

- **JustADiary** — 参考实现（iOS 端）

## 许可证

待定。
