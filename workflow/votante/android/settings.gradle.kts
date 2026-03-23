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
        maven(url = "https://jitpack.io")
        maven {
            url = uri("../../../.mvn/local-repo")
        }
    }
}

rootProject.name = "votante-android"
include(":app")
include(":cifradorlib")
project(":cifradorlib").projectDir = file("../../../android/app")
