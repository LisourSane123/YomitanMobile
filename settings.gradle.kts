import java.net.URI
import java.security.MessageDigest

/*
 * VOICEVOX (neural Japanese TTS, used for card audio) is not on Maven Central:
 * its Java/Android bindings ship as a local-Maven zip on the project's GitHub
 * release, and its ONNX Runtime as a tarball beside it. Both are fetched once
 * into .voicevox/ and checked against a pinned SHA-256 — a changed file fails
 * the build rather than being linked into the app.
 */
val voicevoxDir = file(".voicevox")

fun fetchVerified(url: String, sha256: String, target: File) {
    if (target.isFile && sha256Of(target) == sha256) return
    target.parentFile.mkdirs()
    val partial = File(target.path + ".part")
    URI(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
    val actual = sha256Of(partial)
    if (actual != sha256) {
        partial.delete()
        throw GradleException("VOICEVOX download $url: SHA-256 $actual, expected $sha256")
    }
    partial.renameTo(target)
}

fun sha256Of(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

fun unpack(archive: File, into: File, command: List<String>) {
    into.mkdirs()
    val process = ProcessBuilder(command + archive.absolutePath).directory(into).inheritIO().start()
    if (process.waitFor() != 0) throw GradleException("could not unpack $archive")
}

val voicevoxRelease = "https://github.com/VOICEVOX"
val voicevoxMaven = File(voicevoxDir, "maven")
if (!File(voicevoxMaven, "jp/hiroshiba/voicevoxcore/voicevoxcore-android/0.17.0").isDirectory) {
    val zip = File(voicevoxDir, "java_packages-0.17.0.zip")
    fetchVerified(
        "$voicevoxRelease/voicevox_core/releases/download/0.17.0/java_packages.zip",
        "e22f120adfb1a680bafd01287bc7aa3f104aa7e437d5d710107a16036c38b017",
        zip
    )
    unpack(zip, voicevoxMaven, listOf("unzip", "-q", "-o"))
}
// ONNX Runtime for the two ABIs the AAR ships natives for. Loaded by name at
// run time, so it only has to sit in the APK's lib/ folder.
for ((abi, archName, sha256) in listOf(
    Triple("arm64-v8a", "arm64", "43e6fc2a89ea6412d4cc20215042a3b34da22fdf05c1bb76256a686f82378d46"),
    Triple("x86_64", "x64", "807e2ea50dfb2a309416adb1cee6edc53d3d8eae1d702ad4e6f199a0f91f2d24")
)) {
    val so = File(voicevoxDir, "jniLibs/$abi/libvoicevox_onnxruntime.so")
    if (so.isFile) continue
    val name = "voicevox_onnxruntime-android-$archName-1.23.2"
    val tgz = File(voicevoxDir, "$name.tgz")
    fetchVerified(
        "$voicevoxRelease/onnxruntime-builder/releases/download/voicevox_onnxruntime-1.23.2/$name.tgz",
        sha256,
        tgz
    )
    val extracted = File(voicevoxDir, "onnxruntime")
    unpack(tgz, extracted, listOf("tar", "xzf"))
    so.parentFile.mkdirs()
    File(extracted, "$name/lib/libvoicevox_onnxruntime.so").copyTo(so, overwrite = true)
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri(voicevoxMaven) }
    }
}

rootProject.name = "YomitanMobile"
include(":app")
