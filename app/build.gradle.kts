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
        minSdk = 29
        targetSdk = 35
        // Релизная версия. Для проверки самообновления можно собрать «старую» версию, не меняя исходники:
        // ./gradlew assembleDebug -PphoenixVersionName=3.9.65 -PphoenixVersionCode=79
        versionCode = (project.findProperty("phoenixVersionCode") as String?)?.toIntOrNull() ?: 80
        versionName = "3.9.66"
        (project.findProperty("phoenixVersionName") as String?)?.let { versionName = it }

        ndk {
            // libv2ray.aar содержит нативные библиотеки; оставляем только arm64 (все современные телефоны)
            abiFilters += listOf("arm64-v8a")
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
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

    testImplementation("junit:junit:4.13.2")
}
