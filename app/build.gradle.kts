plugins { id("com.android.application") }

android {
    signingConfigs {
        getByName("debug") {
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
        versionCode = 4
        versionName = "0.4"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
