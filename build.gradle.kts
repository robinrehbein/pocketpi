import java.util.Properties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class ValidateReleasePushConfiguration : DefaultTask() {
    @get:Input abstract val missingProperties: ListProperty<String>

    @TaskAction
    fun validate() {
        check(missingProperties.get().isEmpty()) {
            "PocketPi release requires these Firebase properties: " +
                missingProperties.get().joinToString(", ")
        }
    }
}

abstract class ValidateUploadKey : DefaultTask() {
    @get:Input abstract val storePath: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.Internal abstract val storePassword:
        org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.Internal abstract val keyAlias: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.Internal abstract val keyPassword:
        org.gradle.api.provider.Property<String>

    @TaskAction
    fun validate() {
        val entries = mapOf(
            "POCKETPI_UPLOAD_KEYSTORE" to storePath.get(),
            "POCKETPI_UPLOAD_STORE_PASSWORD" to storePassword.get(),
            "POCKETPI_UPLOAD_KEY_ALIAS" to keyAlias.get(),
            "POCKETPI_UPLOAD_KEY_PASSWORD" to keyPassword.get(),
        )
        val missing = entries.filterValues { it.isBlank() }.keys
        check(missing.isEmpty()) { "PocketPi release signing is missing: ${missing.joinToString(", ")}" }
        val file = File(storePath.get())
        check(file.isFile) { "PocketPi upload keystore file does not exist" }
        val store = KeyStore.getInstance(file, storePassword.get().toCharArray())
        file.inputStream().use { store.load(it, storePassword.get().toCharArray()) }
        check(store.isKeyEntry(keyAlias.get())) { "PocketPi upload key alias is not a private key entry" }
        check(store.getKey(keyAlias.get(), keyPassword.get().toCharArray()) != null) {
            "PocketPi upload key cannot be opened"
        }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(store.getCertificate(keyAlias.get()).encoded)
            .joinToString(":") { "%02X".format(it) }
        check(fingerprint == "E5:0A:EE:1E:63:41:FF:AE:CD:1E:FE:AE:0B:25:B3:64:B5:80:B2:01:1B:E4:3C:06:2F:0A:23:54:1D:72:83:B5") {
            "PocketPi upload certificate does not match the new Play upload key"
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

fun remoteProperty(key: String): String =
    providers.gradleProperty(key).orNull
        ?: providers.environmentVariable(key).orNull
        ?: localProperties.getProperty(key, "")

fun quoted(value: String) =
    "\"" +
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") +
        "\""

val requiredPushProperties =
    listOf("API_KEY", "APP_ID", "PROJECT_ID", "GCM_SENDER_ID")
        .map { "PI_REMOTE_FIREBASE_$it" }
val missingReleasePushProperties = requiredPushProperties.filter { remoteProperty(it).isBlank() }
val validateReleasePushConfiguration = tasks.register<ValidateReleasePushConfiguration>(
    "validateReleasePushConfiguration"
) {
    group = "verification"
    description = "Checks that a PocketPi release can register push notifications."
    missingProperties.set(missingReleasePushProperties)
}

val uploadStorePath = remoteProperty("POCKETPI_UPLOAD_KEYSTORE")
val uploadStorePassword =
    remoteProperty("POCKETPI_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = remoteProperty("POCKETPI_UPLOAD_KEY_ALIAS")
val uploadKeyPassword =
    remoteProperty("POCKETPI_UPLOAD_KEY_PASSWORD")
val validateUploadKey = tasks.register<ValidateUploadKey>("validateUploadKey") {
    group = "verification"
    description = "Checks the release key and its new Play upload certificate."
    storePath.set(uploadStorePath)
    storePassword.set(uploadStorePassword)
    keyAlias.set(uploadKeyAlias)
    keyPassword.set(uploadKeyPassword)
}

val requestedVersionCode = providers.gradleProperty("POCKETPI_VERSION_CODE").orNull
val requestedVersionName = providers.gradleProperty("POCKETPI_VERSION_NAME").orNull
check((requestedVersionCode == null) == (requestedVersionName == null)) {
    "POCKETPI_VERSION_CODE and POCKETPI_VERSION_NAME must be supplied together"
}
val releaseVersionCode = requestedVersionCode?.toIntOrNull()
check(requestedVersionCode == null || (releaseVersionCode != null && releaseVersionCode in 1..2100000000)) {
    "POCKETPI_VERSION_CODE must be an integer between 1 and 2100000000"
}
check(requestedVersionName == null || requestedVersionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+-ci\\.[0-9]+"))) {
    "POCKETPI_VERSION_NAME must use the form 0.3.19-ci.1"
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleasePushConfiguration, validateUploadKey)
}

android {
    namespace = "de.joinnoah.pi.remote"
    compileSdk = 37
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    signingConfigs {
        create("pocketpiUpload") {
            if (uploadStorePath.isNotBlank()) storeFile = rootProject.file(uploadStorePath)
            if (uploadStorePassword.isNotBlank()) {
                storePassword =
                    uploadStorePassword
            }
            if (uploadKeyAlias.isNotBlank()) keyAlias = uploadKeyAlias
            if (uploadKeyPassword.isNotBlank()) {
                keyPassword =
                    uploadKeyPassword
            }
        }
    }
    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isDebuggable = false
            // Isolated app data and no production push registration during measurements.
            for (key in listOf("API_KEY", "APP_ID", "PROJECT_ID", "GCM_SENDER_ID")) {
                buildConfigField("String", "PI_REMOTE_FIREBASE_$key", "\"\"")
            }
        }
        release {
            isMinifyEnabled = false
            if (uploadStorePath.isNotBlank() && uploadStorePassword.isNotBlank() &&
                uploadKeyAlias.isNotBlank() && uploadKeyPassword.isNotBlank()) {
                signingConfig = signingConfigs.getByName("pocketpiUpload")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    bundle { language { enableSplit = false } }
    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "de.robinrehbein.pocketpi"
        versionCode = releaseVersionCode ?: 1
        versionName = requestedVersionName ?: "0.3.19"
        for (key in listOf("API_KEY", "APP_ID", "PROJECT_ID", "GCM_SENDER_ID")) {
            buildConfigField(
                "String",
                "PI_REMOTE_FIREBASE_$key",
                quoted(remoteProperty("PI_REMOTE_FIREBASE_$key")),
            )
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        managedDevices {
            localDevices {
                create("pixel2Api36") {
                    device = "Pixel 2"
                    apiLevel = 36
                    systemImageSource = "aosp"
                }
            }
        }
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("benchmark")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
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

if (providers.gradleProperty("composeMetrics").isPresent) {
    composeCompiler {
        metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
        reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
    }
}

dependencies {
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.browser)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.androidsvg)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime.ktx)
}
