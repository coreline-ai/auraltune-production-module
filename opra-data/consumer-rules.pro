# OPRA data module consumer ProGuard rules (propagate into the app's R8 when minified).
#
# kotlinx.serialization needs reflective access to the generated $serializer descriptors for the
# OPRA JSONL DTOs (parsed at runtime, incl. in RELEASE via the bundled snapshot) and the bundled
# manifest DTO. Without these keeps, R8 can rename/remove them and release parsing breaks even
# though debug works. Mirrors the app module's rules for the AutoEq models.
-keepclassmembers class com.coreline.auraltune.opra.** {
    *** Companion;
}
-keepclasseswithmembers class com.coreline.auraltune.opra.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# DTOs deserialized by name (JSONL line envelope + bundled manifest, both in the dto package) —
# keep the classes, members, and generated $serializer descriptors.
-keep,includedescriptorclasses class com.coreline.auraltune.opra.dto.** { *; }
