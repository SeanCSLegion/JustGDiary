pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像优先。实测在本机网络下直连 repo.maven.apache.org 会被 TLS 重置
        // （SSL peer shut down incorrectly），而阿里云镜像约 6–8 MB/s；
        // 官方源保留在最后作为其他网络环境下的兜底。
        maven("https://maven.aliyun.com/repository/public") // Maven Central(+JCenter) 镜像
        google()
        mavenCentral()
    }
}

rootProject.name = "JustGDiary"
include(":app")
