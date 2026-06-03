# Add project specific ProGuard rules here.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# kotlinx.serialization
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class com.agentmonitor.app.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.agentmonitor.app.data.**$$serializer { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
