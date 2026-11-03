# File: app/proguard-rules.pro

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# 1. kotlinx.serialization 规则收敛：
#    org.jetbrains.kotlinx:kotlinx-serialization-core-jvm 内置的 consumer 规则已包含
#    -if @kotlinx.serialization.Serializable 条件保留，无需保留全部 Companion 与 $$serializer。

# 2. JAudioTagger 精细化保留规则：
#    (a) 保留 Tag 实现类的原始名称，供 SongAudioMetadataExtractor 中 tag.javaClass.simpleName 提取标签格式（如 FLAC/ID3）
-keepnames class * implements org.jaudiotagger.tag.Tag

#    (b) 保留 ID3 帧体类及其反射实例化的构造函数（防范 Class.forName 动态加载被 R8 移除）
-keep class org.jaudiotagger.tag.id3.framebody.** {
    public <init>(...);
}

# 忽略第三方库警告
-dontwarn org.jaudiotagger.**
