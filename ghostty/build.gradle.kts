import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val nativeRoot = rootProject.layout.projectDirectory.dir("build/ghostty-vt-android")
val verifyNative by tasks.registering {
    inputs.file(nativeRoot.file("jni-manifest.json"))
    inputs.dir(nativeRoot.dir("jniLibs"))
    inputs.file("src/main/c/ghostty_jni.c")
    inputs.file("src/main/c/virtual_placements.h")
    inputs.file("src/main/zig/virtual_placements.zig")
    doLast {
        val root = nativeRoot.asFile
        val receipt = JsonSlurper().parse(root.resolve("jni-manifest.json")) as Map<*, *>
        check(receipt["sourceRevision"] == "edefce7785c9f439966c68588db1edbd6b435203")
        check(receipt["abi"] == "arm64-v8a" && receipt["androidApi"] == 26 && receipt["elfPageSize"] == 16384)
        check(receipt["ndk"] == "28.2.13676358" && receipt["snapshotVersion"] == 1)
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest(file("src/main/c/ghostty_jni.c")) == receipt["bindingSourceSha256"]) { "Rebuild Ghostty JNI after editing the binding" }
        check(digest(file("src/main/c/virtual_placements.h")) == receipt["virtualPlacementHeaderSha256"])
        check(digest(file("src/main/zig/virtual_placements.zig")) == receipt["virtualPlacementBridgeSha256"])
        val files = receipt["files"] as Map<*, *>
        check(files.keys.contains("jniLibs/arm64-v8a/libcmux_ghostty.so"))
        files.forEach { (path, hash) ->
            val artifact = root.resolve(path as String).canonicalFile
            check(artifact.toPath().startsWith(root.canonicalFile.toPath()))
            check(digest(artifact) == hash) { "Ghostty JNI checkpoint changed: $path" }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyNative) }

android {
    namespace = "io.github.docmorphic.cmuxapp.ghostty"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").jniLibs.srcDir(nativeRoot.dir("jniLibs"))
    sourceSets.getByName("main").assets.srcDir(nativeRoot.dir("notices"))
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
}
