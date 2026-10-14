// =====================================================================================
// MMR Sensor Logger (task M1-1): DEBUG-ONLY FIELD TOOL FOR CALIBRATION DRIVES.
// NEVER PUBLISH THIS APP ON GOOGLE PLAY OR ANY STORE (SECURITY.md section 8).
// It records raw sensor streams and precise GPS traces to local files. It is installed
// from Android Studio or with `gradlew :sensorlogger:installDebug` on test phones only.
// The release build type exists only so the module configures cleanly; it has no signing.
// =====================================================================================
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mapmyroad.sensorlogger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mapmyroad.sensorlogger"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Not distributed. No signing config on purpose.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
