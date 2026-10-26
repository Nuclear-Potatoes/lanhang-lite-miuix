# Gson 反射序列化
#
# 【不要用文件名当类名写 keep 规则】
# Models.kt / ScheduleModels.kt / GradeModels.kt / MomentModels.kt 里装的都是一堆【顶层类】
# （ApiResponse、LoginInfo、Course…），并不存在 com.lanhang.miuix.net.Models 这个类。
# 之前的四条形如 `-keep class com.lanhang.miuix.net.Models { *; }` 是空规则，
# R8 照常把模型字段改名、把类合并掉 → Gson 按 JSON 键取不到值，
# 线上表现为「登录失败」「数据异常」（release 包才复现，debug 不混淆所以看正常）。
# 这里改成按【包】保留，以后往这些文件里新增模型类会自动生效。
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.lanhang.miuix.net.** { *; }
-keep class com.lanhang.miuix.schedule.** { *; }
-keep class com.lanhang.miuix.grades.** { *; }
-keep class com.lanhang.miuix.moments.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Coil
-dontwarn coil.**

# ---------------------------------------------------------------- 融云 IMLib（私信，2026-10-26）
# SDK 内部大量 JNI + 反射 + MessageContent 多态反序列化，R8 改名/裁剪会直接崩，
# 因此整包保留（体积影响可以忽略，肉都在 .so 里）。
-keep class io.rong.** { *; }
-keep class * implements io.rong.imlib.model.MessageContent { *; }
-keep class * extends io.rong.imlib.model.MessageContent { *; }
-dontwarn io.rong.**

# 融云推送模块引用了各厂商推送 SDK / Firebase，本项目只接了小米，其余按 R8 生成的清单忽略
-dontwarn com.google.android.gms.**
-dontwarn com.google.firebase.**
-dontwarn com.heytap.msp.push.**
-dontwarn com.hihonor.push.**
-dontwarn com.huawei.agconnect.**
-dontwarn com.huawei.hms.**
-dontwarn com.meizu.cloud.pushsdk.**
-dontwarn com.vivo.push.**
-dontwarn com.xiaomi.mipush.**
# 2026-10-26 第二轮：MiPush 已放弃（用户要求改用普通通知），小米那批 keep 规则一并删掉。
# 融云 push 模块仍会引用各厂商推送 SDK，没有对应依赖时按 R8 生成的清单忽略即可（见上）。
