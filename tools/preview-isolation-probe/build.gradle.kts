plugins {
    id("com.android.application") version "9.4.1"
    // Match PocketPi's Kotlin plugin classpath so AGP's bundled default does not
    // select the older, uncached Kotlin artifacts in offline builds.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}
android {
    namespace = "de.pocketpi.previewprobe"
    compileSdk = 37
    defaultConfig {
        applicationId = "de.pocketpi.previewprobe"
        minSdk = 26
        targetSdk = 36
    }
}
