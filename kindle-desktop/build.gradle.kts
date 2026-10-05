// Kindle Vocabulary Builder → Anki, as a desktop program: plug the Kindle in,
// click "Synchronizuj". The same pipeline the phone runs (it is :core), wrapped
// in a small Swing window, plus a command line for the Linux plug-in watcher.
//
//   ./gradlew :kindle-desktop:fatJar      → build/libs/kindle-sync.jar (needs Java 17)
//   ./gradlew :kindle-desktop:jpackage    → an installer for THIS OS with its own Java
//
// Swing rather than Compose Desktop: it ships with the JDK, so the window costs
// no dependency and no Kotlin upgrade, and jpackage bundles it as-is.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.yomitanmobile.kindle.MainKt")
}

dependencies {
    implementation(project(":core"))
    // The platform's org.json on Android; the real library here.
    implementation("org.json:json:20231013")
    // Kindle's vocab.db is SQLite. This driver carries its native library for
    // Linux, Windows and macOS inside the jar, so nothing has to be installed.
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    // sqlite-jdbc logs through SLF4J; without a binding it prints a warning on every start.
    implementation("org.slf4j:slf4j-nop:1.7.36")
    // A modern, identical look on all three systems; Swing's own themes look
    // dated on Linux and differ everywhere.
    implementation("com.formdev:flatlaf:3.4.1")
    // VOICEVOX's desktop bindings: the JNI library for every OS is inside the
    // jar; the voice models and ONNX Runtime are the user's download.
    implementation("jp.hiroshiba.voicevoxcore:voicevoxcore:0.17.0")

    testImplementation("junit:junit:4.13.2")
}

/**
 * One jar with every dependency in it — what `java -jar` and jpackage want.
 * Signature files of the dependencies are dropped: they describe jars that no
 * longer exist once merged, and the JVM refuses a jar whose signatures do not
 * match its contents.
 */
val fatJar by tasks.registering(Jar::class) {
    group = "build"
    archiveFileName.set("kindle-sync.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = application.mainClass.get() }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC", "META-INF/INDEX.LIST")
    }
}

/**
 * A native installer for the OS this runs on — jpackage cannot cross-build, so
 * a Windows .msi is made on Windows, a macOS .dmg on a Mac (CI does all three).
 * The result carries its own Java runtime: the user installs one thing and
 * never meets Java. `-PinstallerType=app-image` gives a plain folder instead.
 */
val jpackage by tasks.registering(Exec::class) {
    group = "distribution"
    dependsOn(fatJar)
    val os = System.getProperty("os.name").lowercase()
    val type = (project.findProperty("installerType") as String?) ?: when {
        "win" in os -> "msi"
        "mac" in os -> "dmg"
        else -> "deb"
    }
    val input = layout.buildDirectory.dir("libs")
    val dest = layout.buildDirectory.dir("installer")
    doFirst { delete(dest) }
    val args = mutableListOf(
        "jpackage",
        "--type", type,
        "--name", "Kindle-Anki",
        "--app-version", "1.0.0",
        "--vendor", "YomitanMobile",
        "--description", "Kindle Vocabulary Builder to Anki flashcards",
        "--input", input.get().asFile.path,
        "--main-jar", "kindle-sync.jar",
        "--main-class", application.mainClass.get(),
        "--dest", dest.get().asFile.path,
        "--java-options", "-Xmx3g"
    )
    when {
        "win" in os -> args += listOf("--win-menu", "--win-shortcut", "--win-dir-chooser")
        "mac" in os -> args += listOf("--mac-package-name", "Kindle-Anki")
        type != "app-image" -> args += listOf("--linux-shortcut", "--linux-menu-group", "Education")
    }
    commandLine(args)
}
