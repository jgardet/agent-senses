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
    }
}

rootProject.name = "agent-senses"
include(":core")
include(":halo")
include(":simulator")
include(":routes")
include(":orchestration")

val haloEngineDir = providers.gradleProperty("haloEngineDir").orElse("../graphic-engine-halo").get()
includeBuild(haloEngineDir) {
    dependencySubstitution {
        substitute(module("halo.engine:kotlin")).using(project(":kotlin"))
        substitute(module("halo.engine:android")).using(project(":android"))
    }
}
