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
        maven("https://maven.mozilla.org/maven2/") { content { includeGroup("org.mozilla.geckoview") } }
    }
}

rootProject.name = "cmux-app"
include(":app")
include(":iroh")
include(":ghostty")
include(":legacy-biometric")
// Opt-in transport research APK; never a dependency of the delivered app.
if (providers.gradleProperty("sshSpike").isPresent) include(":ssh-spike")
