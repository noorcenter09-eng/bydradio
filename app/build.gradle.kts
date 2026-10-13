plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val verCode = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
val ghRepo = (project.findProperty("ghRepo") as String?) ?: ""
val ksPath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.kurdradio.byd"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kurdradio.byd"
        minSdk = 26
        targetSdk = 33
        versionCode = verCode
        versionName = "1.0.$verCode"
        buildConfigField("String", "GH_REPO", "\"$ghRepo\"")
    }

    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksPath != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.guava:guava:32.1.3-android")
}
