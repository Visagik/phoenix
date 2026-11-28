import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.phoenix.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phoenix.app"
        // minSdk и ABI задаются в вариантах full / lite (productFlavors ниже)
        targetSdk = 35
        // Релизная версия. Для проверки самообновления можно собрать «старую» версию, не меняя исходники:
        // ./gradlew assembleDebug -PphoenixVersionName=3.9.65 -PphoenixVersionCode=79
        versionCode = (project.findProperty("phoenixVersionCode") as String?)?.toIntOrNull() ?: 82
        versionName = "3.9.68"
        (project.findProperty("phoenixVersionName") as String?)?.let { versionName = it }
    }

    // Два варианта из одного кода. full — телефон, как раньше. lite — планшет Acer B3-A32 (Android 6): отдельный пакет, обе архитектуры ядра.
    // Все числа и флаги Lite живут в LiteProfile.kt, здесь только то, что нельзя задать в коде.
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            minSdk = 29
            // libv2ray.aar содержит нативные библиотеки; оставляем только arm64 (все современные телефоны)
            ndk { abiFilters += listOf("arm64-v8a") }
            buildConfigField("boolean", "LITE", "false")
        }
        create("lite") {
            dimension = "edition"
            minSdk = 23
            applicationIdSuffix = ".lite"
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
            buildConfigField("boolean", "LITE", "true")
            // libhev-socks5-tunnel.so: у full — src/full/jniLibs (arm64), у Lite — src/lite/jniLibs/armeabi-v7a (из v2rayNG). Не в репозитории (.gitignore), пока hev в Lite выключен
        }
    }

    // Настоящие классы-обработчики ядра (вместо Proxy, который роняет CheckJNI на Android 6): компилируются только если есть libs/libv2ray.aar.
    // Без aar проект собирается как раньше, XrayCore берёт Proxy.
    if (file("libs/libv2ray.aar").exists()) {
        sourceSets["main"].java.srcDir("src/xray/java")
    }

    // Постоянная подпись выпусков: ключ и пароли — в keystore.properties (вне git) или в переменных окружения (CI).
    // Нет ключа — release собирается без подписи, debug-сборка не затрагивается.
    val keyProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun signValue(prop: String, env: String): String? = keyProps.getProperty(prop) ?: System.getenv(env)
    val releaseKeyPath = signValue("storeFile", "PHOENIX_KEYSTORE")

    signingConfigs {
        if (releaseKeyPath != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeyPath)
                storePassword = signValue("storePassword", "PHOENIX_STORE_PASSWORD")
                keyAlias = signValue("keyAlias", "PHOENIX_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = signValue("keyPassword", "PHOENIX_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // сжатие кода и ресурсов без переименования (см. proguard-rules.pro): журнал и отчёты остаются читаемыми
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeyPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        // java.time и другое из Java 8+ на Android 6 (API 23): нужно Lite, на full безвредно
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Ядро Xray (libv2ray.aar из AndroidLibXrayLite). Файла нет — приложение всё равно собирается, VPN просто недоступен.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core") // базовые значки: дом, список, настройки, назад и др.
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // версия 2.1.x подходит к AGP 8.7.3 (нужен AGP ≥ 8.3); последнюю 2.1.x поднять при случае
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    testImplementation("junit:junit:4.13.2")
}
