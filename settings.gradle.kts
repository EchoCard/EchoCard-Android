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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

val localAndroidBleGradle = settings.rootDir.resolve("../android-ble/build.gradle.kts")
if (localAndroidBleGradle.exists()) {
    println("[EchoCard] callmate-ble: 📎 使用本地源码 " + localAndroidBleGradle.parentFile?.absolutePath)
    includeBuild("../android-ble") {
        dependencySubstitution {
            substitute(module("com.vaca.callmate:callmate-ble")).using(project(":"))
        }
    }
} else {
    println("[EchoCard] callmate-ble: 📦 使用 app/libs/callmate-ble-0.1.0.aar")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // callmate-ble is now bundled as a local AAR in app/libs/
        // No GitHub Packages authentication required.
    }
}

rootProject.name = "EchoCard"
include(":app")
 
