pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

enableFeaturePreview("VERSION_CATALOGS")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "yoki"
include("yoki-core")
include("yoki-engine-docker")
include("yoki-protocol")
include("yoki-logging")
