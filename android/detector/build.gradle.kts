import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-Kotlin detection pipeline (ARCHITECTURE.md section 5). No Android dependencies, so it runs in fast JVM
// tests against recorded traces and can be reused by analysis tools (CODING_STANDARDS.md section 3.6).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
