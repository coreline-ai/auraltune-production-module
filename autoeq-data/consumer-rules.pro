# Keep @Serializable model classes so kotlinx-serialization can find their generated $serializer.
-keep,includedescriptorclasses class com.coreline.autoeq.model.** { *; }
-keepclassmembers class com.coreline.autoeq.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.coreline.autoeq.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
