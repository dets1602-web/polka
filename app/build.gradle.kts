plugins { id("com.android.application") }

android {
    signingConfigs {
        getByName("debug") {
            // Постоянный ключ: без него каждая сборка на GitHub подписана по-новому и не ставится поверх старой
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = false
            enableV3Signing = false
            enableV4Signing = false
        }
    }

    namespace = "ru.polka"
    compileSdk = 30
    defaultConfig {
        applicationId = "ru.polka"
        minSdk = 17
        // 22 — чтобы на Android 6 доступ к памяти давался сразу при установке, без запросов
        targetSdk = 22
        versionCode = 19
        versionName = "0.19"
        // Процессор книги — ARMv7; лишние варианты библиотеки шифрования не тащим
        ndk { abiFilters += listOf("armeabi-v7a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    // Современное шифрование (TLS 1.2/1.3, AES-GCM): в Android 4.2 его нет, а GitHub и Яндекс без него не пускают
    implementation("org.conscrypt:conscrypt-android:2.5.2")
    // Движок PDF из Chrome — для новых PDF, которые не понимает читалка ONYX
    implementation("com.github.barteksc:pdfium-android:1.9.0")
}
