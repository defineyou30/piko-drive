pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Playground SDK (공개 저장소)
        maven("https://nexus-playground.pleos.ai/repository/maven-releases") {
            content { includeGroupByRegex("ai\\.pleos.*") }
        }
    }
}

rootProject.name = "piko-drive"
include(":core")
include(":app")
include(":probe")
