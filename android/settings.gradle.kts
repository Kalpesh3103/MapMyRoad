pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MapMyRoad"

include(":app")
// Pure-Kotlin detection pipeline, no Android dependencies (ARCHITECTURE.md section 5, AGENTS.md section 8).
include(":detector")
// Debug-only field tool for M1 data collection. Never published (SECURITY.md section 8).
include(":sensorlogger")
