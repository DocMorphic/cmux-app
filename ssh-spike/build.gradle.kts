plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.docmorphic.cmuxapp.sshspike"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "io.github.docmorphic.cmuxapp.sshspike"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("androidTest").assets.directories.add(layout.buildDirectory.dir("fixture-assets").get().asFile.path)
}

dependencies {
    androidTestImplementation("com.github.mwiede:jsch:2.28.0")
    androidTestImplementation("org.bouncycastle:bcprov-jdk18on:1.86")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
