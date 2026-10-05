// The part of the app that is plain Kotlin: the dictionary parser, the
// tokenizer and the scan rules, the duplicate check's matching rule, the card
// HTML and the audio lookups. The Android app depends on it, and so does the
// desktop Kindle tool — which is the reason it exists: before it, the only way
// to run this code off a phone was as a Robolectric unit test of the app,
// which needed the Android SDK and the whole repository on the machine.
//
// Nothing here may import android.*. The compiler enforces that now: this
// module has no Android on its classpath.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    api("javax.inject:javax.inject:1")
    // The entities keep their Room annotations: the app's Room reads them
    // from here. room-common is the annotations alone, no Android.
    api("androidx.room:room-common:2.6.1")
    // Card style is stored as DataStore preferences on the phone and read back
    // from a backup's settings.json on the desktop; the type is the same.
    api("androidx.datastore:datastore-preferences-core:1.0.0")
    // Android ships org.json in the platform, so the app must not bundle it
    // (duplicate platform classes); the desktop tool adds the real jar.
    compileOnly("org.json:json:20231013")
    // VOICEVOX's Java API. Same classes in the Android AAR the app uses and
    // the desktop jar the Kindle tool uses, so each supplies its own.
    compileOnly("jp.hiroshiba.voicevoxcore:voicevoxcore:0.17.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
