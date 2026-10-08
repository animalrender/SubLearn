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

rootProject.name = "SubLearn"


include(":app")
include(":core:common")
include(":core:subtitles")
include(":core:designsystem")
include(":core:settings")
include(":core:data")
include(":core:player")
include(":core:translate")
include(":core:ai")
include(":core:security")
include(":core:lexicon")
include(":feature:home")
include(":feature:player")
include(":feature:settings")
include(":feature:words")
include(":feature:learn")
