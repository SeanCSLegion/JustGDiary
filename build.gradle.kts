// JustGDiary —— 顶层构建脚本
// 所有版本声明集中在 gradle/libs.versions.toml（唯一版本真源）
plugins {
    alias(libs.plugins.android.application) apply false
    // 注意：AGP 9.0 起内置 Kotlin 支持，**不再需要** org.jetbrains.kotlin.android 插件
    // （见 https://kotl.in/gradle/agp-built-in-kotlin）。
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
