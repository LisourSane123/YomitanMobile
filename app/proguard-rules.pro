# Annotations are required at runtime for Room/Hilt/Compose reflection
# and for kotlinx.serialization's `<class>.Companion.serializer()` lookup.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ---------------------------------------------------------------------------
# kotlinx.serialization
# ---------------------------------------------------------------------------
# Keep the serialization runtime itself.
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
# Every @Serializable class generates a companion `$serializer` object that
# the runtime resolves by name. R8 can otherwise strip the generated
# serializer and the JSON round-trip stored in DictionaryEntry.examples_json
# will fail at runtime with `SerializationException: Serializer for class
# X is not found`.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.yomitanmobile.**$$serializer { *; }
-keep class com.yomitanmobile.domain.model.ExamplePair { *; }
-keep class com.yomitanmobile.domain.model.ExamplePair$Companion { *; }

# ---------------------------------------------------------------------------
# Room entities and DAOs
# ---------------------------------------------------------------------------
# Room generates DAO impls at compile time via KSP and references the
# entities' field names through reflection in the generated code paths.
# Keep entity classes wholesale so column-name reflection survives.
-keep class com.yomitanmobile.data.local.entity.** { *; }
-keep class com.yomitanmobile.data.local.dao.** { *; }
# Room internal types
-keep class androidx.room.** { *; }
-keep interface androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------
# Hilt
# ---------------------------------------------------------------------------
# Hilt's @EntryPoint interfaces are accessed via EntryPointAccessors, which
# resolves them by class name at runtime — the WidgetEntryPoint used by
# SearchWidgetProvider goes through this path.
-keep @dagger.hilt.EntryPoint interface * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep class com.yomitanmobile.widget.WidgetEntryPoint { *; }

# ---------------------------------------------------------------------------
# AnkiDroid API
# ---------------------------------------------------------------------------
# AddContentApi uses ContentResolver under the hood; the field-name and
# model-name constants are passed across the IPC boundary and must not be
# renamed.
-keep class com.ichi2.anki.api.** { *; }
-dontwarn com.ichi2.anki.api.**

# ---------------------------------------------------------------------------
# Hush warnings for optional/runtime-only references we don't use
# ---------------------------------------------------------------------------
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.serialization.internal.**

# ---------------------------------------------------------------------------
# Strip non-essential logging from release builds
# ---------------------------------------------------------------------------
# Errors (Log.e) are still useful for crash reports; verbose/debug/info are
# pure noise in release and may contain file paths or query text we'd
# rather not ship.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
