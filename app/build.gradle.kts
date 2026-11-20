plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.dsnews"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.dsnews"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Edge TTS (BingTts.kt) က OkHttp WebSocket သုံးတယ် — DS Recap Tool / Myanmar TTS နဲ့ အတူတူ
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Video ပေါင်းတာ (NewsRenderer.kt) — DS Recap Tool ရဲ့ VideoMerger.kt နဲ့ အတူတူ
    // 2026-11-20 ပြင်: com.arthenica:ffmpeg-kit-full:6.0 ကို Maven Central ကနေ
    // 2025 April မှာ ဖျက်လိုက်ပြီ (arthenica archived) — clean build မှာ ရှာမတွေ့တော့ဘူး။
    // အစားထိုး: dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7 — package/class အတူတူ
    // (com.arthenica.ffmpegkit.FFmpegKit), code ဘာမှ ပြင်စရာ မလို။
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")
}
