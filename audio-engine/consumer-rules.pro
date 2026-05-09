# Keep AudioEngine native bridge surface so JNI lookup by name does not get renamed.
-keep class com.coreline.audio.AudioEngine {
    private long handle;
    private native <methods>;
    public static final int MAX_AUTOEQ_FILTERS;
    public static final int MAX_MANUAL_FILTERS;
}

# Keep the diagnostics data class — read by user code via reflection-safe public getters,
# and consumed by tooling that may keep classes by name.
-keep class com.coreline.audio.AudioEngine$Diagnostics {
    *;
}

# Keep the EqFilterType enum — its native ordinal mapping is part of the JNI contract.
-keep class com.coreline.audio.EqFilterType {
    *;
}
