# JustGDiary ProGuard/R8 规则（P2-7：release 已开 minify + shrinkResources）。
#
# 速记：
#  - kotlinx.serialization：生成的 `$$serializer` / `Companion.serializer()` 由插件生成，
#    R8 需要保留入口；库自带 consumer rules，这里再钉住本项目的 @Serializable 数据类。
#  - WorkManager：`DailyReminderWorker` 的类名写进 WorkManager 数据库，靠反射实例化。
#  - 反射/序列化相关注解、泛型签名、枚举 values()。

-keepattributes *Annotation*, InnerClasses, Signature, Exceptions

# ---- kotlinx.serialization ----
-keepclassmembers class **$$serializer {
    *** descriptor;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.cov.justdiary.data.**$$serializer { *; }
-keep,includedescriptorclasses class com.cov.justdiary.model.**$$serializer { *; }
-dontnote kotlinx.serialization.**

# ---- WorkManager Worker（类名存在数据库里，不能用混淆名）----
-keep class com.cov.justdiary.service.DailyReminderWorker { <init>(...); }

# ---- 应用入口 / 清单里按类名加载 ----
-keep class com.cov.justdiary.JustDiaryApplication { *; }
-keep class com.cov.justdiary.app.MainActivity { *; }
-keep class com.cov.justdiary.service.ReminderTimeChangeReceiver { *; }

# ---- 可选依赖：没有对应运行时实现时不要因缺类而失败 ----
-dontwarn com.google.android.gms.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
