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
        // gkonovalov/android-vad publishes via JitPack — needed for the
        // Silero VAD wrapper used by SpeechActivityDetector.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Kiwi"
include(":app")
