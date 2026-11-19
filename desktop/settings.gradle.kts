// Десктопная версия Phoenix (Linux) собирается отдельно от Android: из папки desktop командой  ../gradlew run
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
rootProject.name = "phoenix-desktop"
