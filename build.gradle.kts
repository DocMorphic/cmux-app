buildscript {
    dependencies {
        // AGP's built-in Kotlin uses this version; keep Compose on the same compiler.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.google.gms.google-services") version "4.5.0" apply false
    id("com.android.application") version "9.1.1" apply false
    id("com.android.library") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
