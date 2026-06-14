# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep serializable model classes and their serializers
-keep,includedescriptorclasses class ch.lkmc.kararead.**$$serializer { *; }
-keepclassmembers class ch.lkmc.kararead.** {
    *** Companion;
}
-keepclasseswithmembers class ch.lkmc.kararead.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit
-keepattributes Signature, Exceptions
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Jsoup
-keeppackagenames org.jsoup.nodes
-dontwarn org.jsoup.**
