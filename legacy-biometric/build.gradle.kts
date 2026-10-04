plugins { id("com.android.library") }

android {
    namespace = "io.github.docmorphic.cmuxapp.legacybiometric"
    // SDK 37 removed FingerprintManager from its public stubs. This adapter is
    // invoked only on API 26–27, where the original platform API still exists.
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
