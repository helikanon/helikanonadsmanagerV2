plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.helikanon.adsmanager"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    // ---- Google AdMob (legacy GMA SDK) ----
    // Not: 25.3.0, AppLovin google-adapter 25.3.0.0 ile sertifikasyon uyumu için seçildi.
    api("com.google.android.gms:play-services-ads:25.3.0")
    // Google UMP (GDPR consent)
    api("com.google.android.ump:user-messaging-platform:4.0.0")

    // ---- AppLovin MAX ----
    api("com.applovin:applovin-sdk:13.6.3")

    // ---- AppLovin MAX mediation adapters ----
    // google-adapter sürümü GMA sürümüyle eşleşmeli (x.y.z.n -> GMA x.y.z).
    implementation("com.applovin.mediation:google-adapter:25.3.0.0") // AdMob
    // Diğer adaptörlerde AppLovin'in önerdiği gibi en güncel sürüm ("+") kullanılır.
    implementation("com.applovin.mediation:fyber-adapter:+")         // DT Exchange (Fyber)
    implementation("com.applovin.mediation:vungle-adapter:+")        // Liftoff (Vungle)
    implementation("com.applovin.mediation:facebook-adapter:+")      // Meta Audience Network
    implementation("com.applovin.mediation:mintegral-adapter:+")     // Mintegral
    implementation("com.applovin.mediation:bytedance-adapter:+")     // Pangle (ByteDance)
    implementation("com.applovin.mediation:unityads-adapter:+")      // Unity Ads
}
