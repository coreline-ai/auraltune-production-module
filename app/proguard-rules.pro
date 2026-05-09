# app/proguard-rules.pro
# Defense-in-depth ProGuard rules for the AuralTune app module.
# The :audio-engine and :autoeq-data modules ship consumer-rules.pro that should propagate,
# but we restate the critical "keep" entries here to survive minification edge cases.

# --- AudioEngine native bridge ----------------------------------------------------
# The native library looks up methods by JNI name. Renaming would break the bridge.
-keep class com.coreline.audio.AudioEngine {
    private long handle;
    private native <methods>;
    public static final int MAX_AUTOEQ_FILTERS;
    public static final int MAX_MANUAL_FILTERS;
}
-keep class com.coreline.audio.AudioEngine$Diagnostics { *; }
-keep class com.coreline.audio.EqFilterType { *; }

# --- AutoEQ serialized models -----------------------------------------------------
# kotlinx-serialization needs reflective access to generated $serializer descriptors.
-keep,includedescriptorclasses class com.coreline.autoeq.model.** { *; }
-keepclassmembers class com.coreline.autoeq.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.coreline.autoeq.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- App-local @Serializable types we persist via SettingsStore --------------------
-keep,includedescriptorclasses class com.coreline.auraltune.data.** { *; }
-keepclassmembers class com.coreline.auraltune.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.coreline.auraltune.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Compose / Coroutines safety ---------------------------------------------------
# Compose runtime metadata needed for previews and recomposition.
-keep class kotlin.Metadata { *; }

# Keep enclosing method info for stable stack traces from coroutines.
-keepattributes EnclosingMethod, InnerClasses, Signature, *Annotation*

# Coroutines internal
-dontwarn kotlinx.coroutines.debug.**
