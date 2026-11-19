import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.phoenix"
version = "3.9.66"

// Общая логика Phoenix (сбор, проверки, конфиги Xray) лежит в Android-модуле. Берём из него только «чистые» файлы без Android-классов:
// тот же список, который проверяет тестовый стенд tools/kt-harness. Копии лежат в build/ и в git не попадают.
val pureList = file("../tools/kt-harness/pure.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
val syncShared by tasks.registering(Sync::class) {
    // Theme.kt — палитра на чистом Compose Material 3, без Android-классов: окно использует те же цвета, что и телефон
    from("../app/src/main/java/com/phoenix/app") { include(pureList + "Theme.kt") }
    into(layout.buildDirectory.dir("shared/kotlin/com/phoenix/app"))
}
val syncAssets by tasks.registering(Sync::class) {
    from("../app/src/main/assets") { include("i18n/*.tsv", "geoip4.bin", "baseline_sources.tsv") }
    into(layout.buildDirectory.dir("shared/resources"))
}
kotlin.sourceSets.getByName("main").kotlin.srcDir(layout.buildDirectory.dir("shared/kotlin"))
sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("shared/resources"))
tasks.named("compileKotlin") { dependsOn(syncShared) }
tasks.named("processResources") { dependsOn(syncAssets) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test { useJUnit() }

compose.desktop {
    application {
        mainClass = "com.phoenix.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "phoenix"
            packageVersion = "3.9.66"
            description = "Phoenix: сбор и проверка VPN-конфигов"
            linux {
                menuGroup = "Network"
                appCategory = "Network"
            }
        }
    }
}
