plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "de.robinrehbein.pocketpi.benchmarks"
    compileSdk = 37
    targetProjectPath = ":"
    defaultConfig {
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmark") {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
            matchingFallbacks += "release"
        }
        // Runs the same tests against the app's non-minified benchmarkProfile build; only
        // BaselineProfileGenerator belongs there (docs/runbooks/macrobenchmarks.md).
        create("benchmarkProfile") {
            initWith(getByName("benchmark"))
            matchingFallbacks += listOf("benchmark", "release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    experimentalProperties["android.experimental.self-instrumenting"] = true
    testOptions {
        managedDevices {
            localDevices {
                // For BaselineProfileGenerator and MinifiedSmokeTest without a phone; never for
                // timing claims. The same device as the app's emulator tests, so CI shares its
                // cached AVD and boot snapshot.
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
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark" || variant.buildType == "benchmarkProfile"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
