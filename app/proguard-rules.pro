# ===== Gson 反射序列化 =====
-keepattributes Signature,InnerClasses,EnclosingMethod
# API DTO 全靠 Gson 反射读写，混淆会重命名字段导致请求/响应解析全挂
-keep class dev.liuyouwu.familiar.data.remote.dto.** { <fields>; }
-keep class dev.liuyouwu.familiar.data.local.** { <fields>; }
-keep class dev.liuyouwu.familiar.backup.BackupManager$* { <fields>; }
# TutorPrompts 的 TutorConfig/TutorState/Section 走 Gson 反射（课程配置/进度持久化），
# 已加 @SerializedName 锁键名，keep 规则双保险防字段重命名
-keep class dev.liuyouwu.familiar.util.TutorPrompts$* { <fields>; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# 内核重构新增的 Gson 反射 DTO（无 @SerializedName，键名靠字段名匹配；R8 类合并/字段
# 重命名会让解析全挂——release 启动崩实证）：probe 目录先验 / wire 请求响应 / skills 定义
-keep class dev.liuyouwu.familiar.core.llm.probe.** { <fields>; }
-keep class dev.liuyouwu.familiar.core.llm.wire.** { <fields>; }
-keep class dev.liuyouwu.familiar.skills.** { <fields>; }
# 同族审计项补齐：BlockCodec$Envelope 是 DB blocks 列的双向 Gson 编解码，混淆改名会让旧库行解析崩
-keep class dev.liuyouwu.familiar.core.llm.model.** { <fields>; }
-keep class dev.liuyouwu.familiar.viewmodel.skillhooks.ClassroomHooks$ClassroomState { <fields>; }
# ClassroomSection/等其余嵌套 Gson DTO 也要 keep：只保 ClassroomState 时 R8 会合并掉
# ClassroomSection（sections 字段的泛型签名随之丢失），Gson 反序列化出 LinkedTreeMap，
# stateToPromptBlock 遍历 sections 即 ClassCastException → 课堂发消息闪退（2026-10-31
# vivo 真机实证，与 f361e66/TypeToken 同族——R8 泛型签名丢失系列第三案）
-keep class dev.liuyouwu.familiar.viewmodel.skillhooks.ClassroomHooks$* { <fields>; }
-keep class dev.liuyouwu.familiar.ui.screen.chatroom.ParticipantDraft { <fields>; }

# ===== PDFBox =====
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.**

# ===== Kotlin 协程/元数据 =====
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# P5 皮肤包：pet.json DTO Gson 反射反序列化，防 R8 类横向合并 + 字段改名（2026-10-25 事故同款，f361e66 修法）
-keep class dev.liuyouwu.familiar.pet.skindto.** { <fields>; }

# TypeToken 匿名子类靠 getGenericSuperclass() 的泛型签名工作；R8 full-mode 类横向合并会
# 丢 Signature（-keepattributes Signature 挡不住合并），MCP_SERVERS_TYPE 初始化即炸
# IllegalStateException → release 启动崩实证（2026-10-30 vivo/三星）。keep 住子类防合并。
-keep class * extends com.google.gson.reflect.TypeToken
