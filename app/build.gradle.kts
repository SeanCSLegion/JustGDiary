import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0 起内置 Kotlin 支持，不再应用 org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 版本号只从 version.properties 读；CI / 发版脚本改这一个文件即可（见 docs/release.md）。
val versionProperties = Properties().apply {
    val file = rootProject.file("version.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun versionValue(name: String, fallback: String): String =
    versionProperties.getProperty(name)?.takeIf { it.isNotBlank() } ?: fallback

// 正式签名只从 keystore.properties / 环境变量读。文件不存在时 release 仍可构建，
// 但不会签名 —— CI 上如果没有配 secrets，`assembleRelease` 会产出 unsigned APK。
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "JUSTDIARY_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "JUSTDIARY_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "JUSTDIARY_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "JUSTDIARY_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.cov.justdiary"
    compileSdk = 37

    defaultConfig {
        // 与 iOS 版完全一致，不要改成任何变体（见 docs/android-migration-plan.md §0）
        applicationId = "com.cov.justdiary"
        minSdk = 33
        targetSdk = 36
        versionCode = versionValue("VERSION_CODE", "1").toInt()
        versionName = versionValue("VERSION_NAME", "1.0")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("en", "zh-rCN")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // 不加 applicationIdSuffix：包名必须始终是 com.cov.justdiary（见 docs/android-migration-plan.md §0）
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        // P2-9：Log / DebugFlags 依赖 BuildConfig.DEBUG 做发布期门禁
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Robolectric 运行时要下载 android-all-instrumented（~100MB）。
                // 直连 repo1.maven.org 在本机网络下会被 TLS 重置，因此指到阿里云镜像
                // （已确认该镜像有全部版本）。这只是一个测试期依赖，不进 APK。
                test.systemProperty(
                    "robolectric.dependency.repo.url",
                    "https://maven.aliyun.com/repository/public",
                )
                test.systemProperty("robolectric.dependency.repo.id", "aliyun")
                test.systemProperty("robolectric.logging.enabled", "false")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Compose 编译期报告：`build/compose-reports/` 列出每个 composable 是否 skippable、
// 以及哪个参数被判成 unstable —— "跨月时所有已挂载的块都重组" 这类问题靠它定位，
// 比逐帧打日志快得多。产物只在构建目录里，不进 APK。
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose-reports")
    metricsDestination = layout.buildDirectory.dir("compose-metrics")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // JVM 上也能跑 Compose 组合测试（Robolectric）：`keyedRange*Test` 用它钉住
    // "挂载窗口挪一块时，其余块会不会被重建" 这类只有运行时才说得清的行为。
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
}
