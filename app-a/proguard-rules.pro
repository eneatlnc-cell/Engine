# Keep core crypto classes
-keep class com.securesocial.core.crypto.** { *; }
-keep class com.securesocial.core.protocol.** { *; }
-keep class com.securesocial.core.ipc.** { *; }

# Keep serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
