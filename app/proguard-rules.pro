-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keep class *$$serializer { *; }
-keepclassmembers class * {
    *** Companion;
}
-keepclassmembers class * {
    *** $serializer;
}

-keep class de.lwp2070809.speculonic.network.model.** { *; }

-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
