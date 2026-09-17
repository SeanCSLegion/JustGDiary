# JustGDiary ProGuard/R8 规则
# 目前 release 未开启 minify（见 app/build.gradle.kts）。开启时需要补充：
#  - kotlinx.serialization 的 @Serializable 类与 serializer 保留规则
#  - WorkManager Worker 的构造与类名保留
#  - play-services-location 的可选类
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
