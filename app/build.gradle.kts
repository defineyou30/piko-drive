import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 제출용 release 서명. 키 정보는 git에 올라가지 않는 local.properties 에만 둔다.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.pikodrive"
    compileSdk = 36
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.pikodrive"
        minSdk = 28
        targetSdk = 34
        versionCode = 4
        versionName = "0.3.0"
    }

    signingConfigs {
        create("release") {
            localProps.getProperty("piko.storeFile")?.let { storeFile = file(it) }
            storePassword = localProps.getProperty("piko.storePassword")
            keyAlias = localProps.getProperty("piko.keyAlias")
            keyPassword = localProps.getProperty("piko.keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (localProps.getProperty("piko.storeFile") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug") // 키가 없는 환경(심사 빌드 재현)에서도 빌드되게
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)
}
