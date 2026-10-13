pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // Telar is built from its main branch until the release after 0.1.0-alpha09 is on Maven
        // Central: run `./gradlew publishToMavenLocal` in a checkout of deeptelar/telar first.
        mavenLocal {
            content { includeGroup("dev.deeptelar") }
        }
        mavenCentral()
    }
}

rootProject.name = "telar-foodie"
