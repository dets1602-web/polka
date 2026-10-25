plugins { id("com.android.application") }

android {
    namespace = "ru.polka"
    compileSdk = 34
    defaultConfig {
        applicationId = "ru.polka.razvedchik"
        minSdk = 21
        // 22 — чтобы на Android 6 доступ к памяти давался сразу при установке, без запросов
        targetSdk = 22
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
