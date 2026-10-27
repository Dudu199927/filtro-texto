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
        // Aquí se descarga a7zip (lector de RAR / 7z)
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}
rootProject.name = "FiltroTexto"
include(":app")
