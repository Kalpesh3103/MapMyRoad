import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Firebase (M0-5). google-services.json is per developer and git-ignored (SECURITY.md section 10), so the
// Google Services and Crashlytics plugins and the Firebase libraries are only added when the file exists.
// No app code references Firebase classes yet (analytics events are M2-17), so the app compiles either way.
// Note: the debug build uses the applicationId "com.mapmyroad.debug", so google-services.json must contain
// a client for that package as well as "com.mapmyroad", otherwise processDebugGoogleServices fails.
val hasGoogleServicesJson = file("google-services.json").exists()
if (hasGoogleServicesJson) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

// Release signing material comes from android/keystore.properties (git-ignored, see keystore.properties.example)
// or from MMR_* environment variables (CI). Without either, the release build is produced unsigned.
// A plain class (not a data class) so that passwords can never leak through an auto-generated toString().
class ReleaseSigning(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun loadReleaseSigning(propertiesFile: File): ReleaseSigning? {
    val properties = Properties()
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use { properties.load(it) }
    }

    fun value(
        propertyKey: String,
        envKey: String,
    ): String? =
        properties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }
            ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

    val storeFile = value("storeFile", "MMR_KEYSTORE_FILE")
    val storePassword = value("storePassword", "MMR_KEYSTORE_PASSWORD")
    val keyAlias = value("keyAlias", "MMR_KEY_ALIAS")
    val keyPassword = value("keyPassword", "MMR_KEY_PASSWORD")
    if (storeFile == null || storePassword == null || keyAlias == null || keyPassword == null) {
        return null
    }
    return ReleaseSigning(storeFile, storePassword, keyAlias, keyPassword)
}

val releaseSigning: ReleaseSigning? = loadReleaseSigning(rootProject.file("keystore.properties"))

android {
    namespace = "com.mapmyroad"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mapmyroad"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        releaseSigning?.let { signing ->
            create("release") {
                // Relative paths resolve against the android/ directory, absolute paths are used as is.
                storeFile = rootProject.file(signing.storeFile)
                storePassword = signing.storePassword
                keyAlias = signing.keyAlias
                keyPassword = signing.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Lets debug and release builds be installed side by side on one phone.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
}

kotlin {
    // jvmTarget instead of jvmToolchain: a toolchain may need a JDK download, the Android Studio JDK is enough.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

room {
    // Schema export is committed for migration checks (CODING_STANDARDS.md section 3.10).
    // There is no @Database yet: the Room schema is task M2-7, which is gated behind M1.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maplibre.android.sdk)
    implementation(libs.timber)

    if (hasGoogleServicesJson) {
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.crashlytics)
        implementation(libs.firebase.analytics)
    }

    debugImplementation(libs.leakcanary.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
