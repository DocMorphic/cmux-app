plugins { id("com.android.application") version "9.1.1" }

android {
    namespace = "io.github.docmorphic.cmuxapp.detectorspike"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "io.github.docmorphic.cmuxapp.detectorspike"
        minSdk = 26
        targetSdk = 36
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("tests/chief-data-detection"))
}

dependencies {
    implementation("com.google.mlkit:entity-extraction:16.0.0-beta6")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
