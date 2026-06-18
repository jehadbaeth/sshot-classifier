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
        // Vendored Tesseract4Android AAR (Arabic OCR). Committed under app/libs to keep it off
        // the release critical path (JitPack-only artifact; we don't want JitPack in CI).
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "ScreenshotClassifier"
include(":app")
