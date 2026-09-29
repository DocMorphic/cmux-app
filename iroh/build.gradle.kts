import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Built by scripts/build-iroh-android.py; no unpinned Maven native dependency.
val nativeRoot = rootProject.layout.projectDirectory.dir("build/iroh-android")
val verifyNative by tasks.registering {
    inputs.files(fileTree(nativeRoot))
    doLast {
        val root = nativeRoot.asFile
        val manifest = root.resolve("manifest.json")
        check(manifest.isFile) { "Missing Iroh checkpoint. See docs/IROH_V2.md (Android native module)." }
        val receipt = JsonSlurper().parse(manifest) as Map<*, *>
        check(receipt["revision"] == "ee19f156667ca640b912108a45f8b5bb8d156fec")
        check(receipt["abi"] == "arm64-v8a" && receipt["androidApi"] == 26)
        check(receipt["ndk"] == "28.2.13676358")
        check(receipt["elfPageSize"] == 16384) { "Rebuild the native checkpoint with 16 KiB RELRO alignment." }
        val files = receipt["files"] as Map<*, *>
        val required = setOf("jniLibs/arm64-v8a/libiroh_ffi.so", "kotlin/computer/iroh/iroh_ffi.kt",
            "kotlin/computer/iroh/IrohAndroid.kt", "LICENSE-MIT", "LICENSE-APACHE", "Cargo.lock", "uniffi.toml")
        check(files.keys.containsAll(required)) { "Incomplete native receipt" }
        files.forEach { (relative, expected) ->
            val file = root.resolve(relative as String).canonicalFile
            check(file.toPath().startsWith(root.canonicalFile.toPath()) && file.isFile)
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            check(digest == expected) { "Native checkpoint hash mismatch: $relative" }
        }
    }
}

val nativeNotices by tasks.registering(Sync::class) {
    dependsOn(verifyNative)
    from(nativeRoot) { include("LICENSE-MIT", "LICENSE-APACHE", "manifest.json") }
    into(layout.buildDirectory.dir("generated/irohNotices/licenses/iroh"))
}

android {
    namespace = "io.github.docmorphic.cmuxapp.iroh"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").apply {
        java.srcDir(nativeRoot.dir("kotlin"))
        jniLibs.srcDir(nativeRoot.dir("jniLibs"))
        assets.srcDir(layout.buildDirectory.dir("generated/irohNotices"))
    }
    sourceSets.getByName("androidTest").assets.srcDir("../app/src/test/resources/iroh-v2")
}

tasks.named("preBuild").configure { dependsOn(nativeNotices) }

dependencies {
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
}
