-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keep class *$$serializer { *; }
-keepclassmembers class de.lwp2070809.speculonic.** {
    *** Companion;
    *** $serializer;
}
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    *** $serializer;
}

-keep class de.lwp2070809.speculonic.network.model.** { *; }

-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
