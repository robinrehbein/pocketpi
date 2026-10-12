import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class ValidateWearReleaseVersionCode : DefaultTask() {
    @get:Input abstract val requested: Property<String>
    @get:Input abstract val phoneCode: Property<Int>
    @TaskAction fun validate() {
        val code = requested.get().toIntOrNull()
        check(code != null && code in 1..2100000000 && code != phoneCode.get()) {
            "Wear release requires a globally unused POCKETPI_WEAR_VERSION_CODE distinct from the phone version code"
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val phoneAndroid = rootProject.extensions.getByType<com.android.build.api.dsl.ApplicationExtension>()
@Suppress("UNCHECKED_CAST")
val firebaseBuildFields = rootProject.extra["pocketpiFirebaseBuildFields"] as Map<String, String>
val requestedWearCode = providers.gradleProperty("POCKETPI_WEAR_VERSION_CODE").orNull
check(requestedWearCode == null || requestedWearCode.toIntOrNull()?.let { it in 1..2100000000 } == true) {
    "POCKETPI_WEAR_VERSION_CODE must be an integer between 1 and 2100000000"
}
val validateWearReleaseVersionCode = tasks.register<ValidateWearReleaseVersionCode>("validateWearReleaseVersionCode") {
    group = "verification"
    requested.set(requestedWearCode ?: "")
    phoneCode.set(phoneAndroid.defaultConfig.versionCode ?: 1)
}

android {
    namespace = "de.joinnoah.pi.remote"
    compileSdk = 37
    defaultConfig {
        applicationId = "de.robinrehbein.pocketpi"
        minSdk = 30
        targetSdk = 36
        versionCode = requestedWearCode?.toInt() ?: phoneAndroid.defaultConfig.versionCode
        versionName = phoneAndroid.defaultConfig.versionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        for (key in listOf("API_KEY", "APP_ID", "PROJECT_ID", "GCM_SENDER_ID")) {
            val name = "PI_REMOTE_FIREBASE_$key"
            buildConfigField("String", name, firebaseBuildFields.getValue(name))
        }
    }
    signingConfigs {
        create("pocketpiUpload") { initWith(phoneAndroid.signingConfigs.getByName("pocketpiUpload")) }
    }
    buildTypes {
        release { signingConfig = signingConfigs.getByName("pocketpiUpload") }
    }
    bundle { language { enableSplit = false } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        getByName("main") {
            // Compile the same transport, persistence and protocol implementation for both devices.
            kotlin.directories.add(rootProject.file("src/main/java").path)
            res.directories.add(rootProject.file("src/main/res").path)
            assets.directories.add(rootProject.file("src/main/assets").path)
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

dependencies {
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.wear.remote.interactions)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(":validateUploadKey", validateWearReleaseVersionCode)
}
