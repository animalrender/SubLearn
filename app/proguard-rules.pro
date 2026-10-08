# R8 configuration for the release build.
#
# Shrinking is on (unused Compose icons, Media3 renderers and ML Kit code are dropped), renaming is
# off: a crash report from an early build must point at real class names without a mapping file.
-dontobfuscate

# kotlinx.serialization: the plugin generates `$serializer` classes and `Companion.serializer()`
# accessors that are reached reflectively through the serializers module. Keep them for every
# @Serializable type (the settings schema, AI payloads, export/import JSON).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.sublearn.**$$serializer { *; }
-keepclassmembers class com.sublearn.** {
    *** Companion;
}
-keepclasseswithmembers class com.sublearn.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room, Media3, OkHttp, DataStore and the Google Play services libraries ship their own consumer
# rules; the Android defaults keep enums' values()/valueOf() and everything the manifest names.
