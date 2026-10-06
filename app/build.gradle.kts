import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keep official Java/resources, with the pinned JNI library rebuilt for RELRO.
val graphicsRoot = rootProject.layout.projectDirectory.dir("build/graphics-path-android")
val verifyGraphicsNative by tasks.registering {
    inputs.files(fileTree(graphicsRoot))
    doLast {
        val root = graphicsRoot.asFile
        val manifest = root.resolve("manifest.json")
        check(manifest.isFile) { "Missing graphics-path native checkpoint. See third_party/androidx-graphics-path/README.md" }
        val receipt = JsonSlurper().parse(manifest) as Map<*, *>
        check(receipt["sourceRevision"] == "7b1104d5e67bd061e736e8d576b539498b498be4")
        check(receipt["elfPageSize"] == 16384 && receipt["abi"] == "arm64-v8a")
        val files = receipt["files"] as Map<*, *>
        check(files.containsKey("graphics-path-1.1.0-relro.aar"))
        files.forEach { (relative, expected) ->
            val file = root.resolve(relative as String).canonicalFile
            check(file.toPath().startsWith(root.canonicalFile.toPath()) && file.isFile)
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            check(digest == expected) { "Graphics checkpoint hash mismatch: $relative" }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyGraphicsNative) }
val simulatorNativeRoot = rootProject.layout.projectDirectory.dir("build/simulator-codecs-android")
val verifySimulatorNative by tasks.registering {
    inputs.file(simulatorNativeRoot.file("jni-manifest.json"))
    inputs.dir(simulatorNativeRoot.dir("jniLibs"))
    inputs.dir(simulatorNativeRoot.dir("notices"))
    inputs.file("src/main/c/simulator_video_jni.c")
    doLast {
        val root = simulatorNativeRoot.asFile
        val manifest = root.resolve("jni-manifest.json")
        check(manifest.isFile) { "Missing simulator video native checkpoint. See docs/SIMULATOR_STREAMING.md" }
        val receipt = JsonSlurper().parse(manifest) as Map<*, *>
        check(receipt["sourceRevision"] == "946fcce07b6dcd0331c8cc609192aeff5e1924f8")
        check(receipt["ndk"] == "28.2.13676358" && receipt["abi"] == "arm64-v8a" && receipt["androidApi"] == 26 && receipt["elfPageSize"] == 16384)
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest(file("src/main/c/simulator_video_jni.c")) == receipt["bindingSourceSha256"])
        check(digest(root.resolve("manifest.json")) == receipt["coreManifestSha256"])
        val files = receipt["files"] as Map<*, *>
        check(files.containsKey("jniLibs/arm64-v8a/libcmux_video.so"))
        files.forEach { (path, hash) ->
            val artifact = root.resolve(path as String).canonicalFile
            check(artifact.toPath().startsWith(root.canonicalFile.toPath()) && artifact.isFile)
            check(digest(artifact) == hash) { "Simulator native checkpoint hash mismatch: $path" }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifySimulatorNative) }
val cloudNativeRoot = rootProject.layout.projectDirectory.dir("build/cloud-terminal-android")
val cloudNativeSources = mapOf(
    "builderSha256" to "scripts/build-cloud-terminal-android.py",
    "ghosttyBuildAdapterSha256" to "scripts/native/cloud-ghostty-build.rs",
    "jniSourceSha256" to "app/src/main/c/cloud_terminal_jni.c",
    "androidRuntimeSha256" to "scripts/native/cloud-android-runtime.rs",
    "androidTlsSha256" to "scripts/native/cloud-android-tls.rs",
    "noticeCollectorSha256" to "scripts/collect-cloud-notices.py",
    "noticeSourcesSha256" to "third_party/cloud-notices/sources.json"
)
val verifyCloudNative by tasks.registering {
    inputs.files(fileTree(cloudNativeRoot))
    inputs.files(cloudNativeSources.values.map { rootProject.file(it) })
    doLast {
        val root = cloudNativeRoot.asFile.canonicalFile
        val manifest = root.resolve("manifest.json")
        check(manifest.isFile) { "Missing Cloud native checkpoint. See docs/CLOUD_COMPANION.md" }
        val receipt = JsonSlurper().parse(manifest) as Map<*, *>
        check(receipt["sourceRevision"] == "c2715faa02c260b07012bc0b386597cfb333021d")
        check(receipt["ghosttyRevision"] == "324c0273815ddc383d8d2fbf8d59d590cb65dfdb")
        check(receipt["ndk"] == "28.2.13676358" && receipt["androidApi"] == 26 && receipt["abi"] == "arm64-v8a" && receipt["elfPageSize"] == 16384)
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        cloudNativeSources.forEach { (field, source) ->
            check(receipt[field] == digest(rootProject.file(source))) { "Rebuild Cloud checkpoint after changing $source" }
        }
        val files = receipt["files"] as Map<*, *>
        check(files.keys.containsAll(listOf("jniLibs/arm64-v8a/libcmux_terminal_client.so", "jniLibs/arm64-v8a/libcmux_cloud_jni.so",
            "rustls-platform-verifier-0.1.1.aar", "notices/licenses/CloudTerminal.txt", "notices/licenses/cloud-terminal/inventory.json")))
        files.forEach { (path, hash) ->
            val artifact = root.resolve(path as String).canonicalFile
            check(artifact.toPath().startsWith(root.toPath()) && artifact.isFile && digest(artifact) == hash) { "Cloud checkpoint mismatch: $path" }
        }
        root.resolve("jniLibs").walkTopDown().filter { it.isFile }.forEach {
            check(files.containsKey(it.relativeTo(root).invariantSeparatorsPath)) { "Unverified Cloud native library: ${it.name}" }
        }
        val inventory = root.resolve("notices/licenses/cloud-terminal/inventory.json")
        val noticeReceipt = receipt["notices"] as Map<*, *>
        check(digest(inventory) == noticeReceipt["inventorySha256"])
        val notices = JsonSlurper().parse(inventory) as Map<*, *>
        check((notices["missingLicenseTexts"] as List<*>).isEmpty()) { "Cloud license texts missing: ${notices["missingLicenseTexts"]}" }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyCloudNative) }
configurations.configureEach { exclude(group = "androidx.graphics", module = "graphics-path") }

// About/support identifies development reloads without creating installation identifiers.
val supportSourceRevision = providers.environmentVariable("GITHUB_SHA").orNull
    ?.takeIf { it.matches(Regex("[0-9a-fA-F]{7,40}")) }?.take(12)
    ?: runCatching {
        val sha = providers.exec { commandLine("git", "rev-parse", "--short=12", "HEAD"); workingDir(rootDir) }
            .standardOutput.asText.get().trim()
        val dirty = providers.exec { commandLine("git", "status", "--porcelain", "--untracked-files=normal"); workingDir(rootDir) }
            .standardOutput.asText.get().isNotBlank()
        if (sha.matches(Regex("[0-9a-fA-F]{7,40}"))) sha + if (dirty) "+" else "" else ""
    }.getOrDefault("")

android {
    namespace = "io.github.docmorphic.cmuxapp"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    val releaseKeystorePath = System.getenv("CMUX_APP_RELEASE_KEYSTORE")
    val releasePassword = System.getenv("CMUX_APP_RELEASE_PASSWORD")

    defaultConfig {
        applicationId = "io.github.docmorphic.cmuxapp"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 2
        versionName = "0.2.0"
        buildConfigField("String", "NOTICE_VERSION", "\"0.2.0\"")
        buildConfigField("String", "SOURCE_REVISION", "\"$supportSourceRevision\"")
        // Native milestone targets the Pixel 6a; never install a partial JNA-only ABI.
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (!releaseKeystorePath.isNullOrBlank() && !releasePassword.isNullOrBlank()) {
            create("cmuxAppRelease") {
                storeFile = file(releaseKeystorePath)
                storePassword = releasePassword
                keyAlias = "cmux-app"
                keyPassword = releasePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "NOTICE_CHANNEL", "\"dev\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles("cloud-rules.pro")
            // Signed Actions APKs are the development distribution, not an official store release.
            buildConfigField("String", "NOTICE_CHANNEL", "\"beta\"")
            signingConfig = signingConfigs.findByName("cmuxAppRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").java.directories.add("../third_party/termux/terminal-emulator/src/main/java")
    sourceSets.getByName("main").jniLibs.directories.add(cloudNativeRoot.dir("jniLibs").asFile.path)
    sourceSets.getByName("main").assets.directories.add(cloudNativeRoot.dir("notices").asFile.path)
    sourceSets.getByName("main").jniLibs.directories.add(simulatorNativeRoot.dir("jniLibs").asFile.path)
    sourceSets.getByName("main").assets.directories.add(simulatorNativeRoot.dir("notices").asFile.path)
    sourceSets.getByName("androidTest").assets.directories.add("src/test/resources/terminal")
    sourceSets.getByName("androidTest").assets.directories.add("src/test/resources/browser")

    packaging.resources {
        merges += "META-INF/LICENSE.md"
        // OSGi module descriptors are not used by Android; keep notices in the APK.
        excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
    // Cloud uses the userspace Go backend; do not ship unused root/kernel executables.
    packaging.jniLibs.excludes += setOf("**/libwg.so", "**/libwg-quick.so")

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    implementation("org.mozilla.geckoview:geckoview:157.0.20260924084938")
    implementation(project(":legacy-biometric"))
    implementation(project(":iroh"))
    implementation(project(":ghostty"))
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation(files(graphicsRoot.file("graphics-path-1.1.0-relro.aar")))
    implementation(files(cloudNativeRoot.file("rustls-platform-verifier-0.1.1.aar")))
    // Local AARs do not carry their Maven transitive dependencies.
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.collection:collection:1.5.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.google.firebase:firebase-messaging:25.0.1")
    // FCM resolves 1.1.7, whose arm64 counter library has a 4 KiB RELRO end.
    // 1.2.1's published library passes our 16 KiB LOAD/RELRO gate unchanged.
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.github.mwiede:jsch:2.28.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    implementation("org.bouncycastle:bcutil-jdk18on:1.86")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        // Use the same maintained BC family as the app; do not package duplicate 15to18 classes.
        exclude(group = "org.bouncycastle")
    }
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.webkit:webkit:1.17.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.work:work-testing:2.11.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20240303")
    // JVM counterpart of Android's built-in ICU; never packaged in the APK.
    testImplementation("com.ibm.icu:icu4j:77.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}

// JVM interoperability cases run the real Node helper; helper changes must invalidate their cached result.
tasks.withType<Test>().configureEach {
    inputs.files(rootProject.fileTree("push") { include("*.mjs") })
    inputs.file(rootProject.file("scripts/push-enrollment-fixture-server.mjs"))
    inputs.files(rootProject.fileTree("build/push") { include("cmux-push-seal") })
}
