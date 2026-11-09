plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// S0 프로브: Pleos 에뮬레이터에서 차량 데이터·TTS 수신 여부를 검증하는 일회성 앱
android {
    namespace = "com.pikodrive.probe"
    compileSdk = 36
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.pikodrive.probe"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)
}
