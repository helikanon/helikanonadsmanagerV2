plugins {
    id("com.android.library")
}

android {
    namespace = "com.helikanon.adsmanager"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")

        // AGP 9 varsayılan olarak tüketiciyi kütüphanenin compileSdk'sine (37) zorlar.
        // Uygulamalarımız halen compileSdk 36'da olduğu için tabanı açıkça 36'ya sabitliyoruz.
        aarMetadata {
            minCompileSdk = 36
        }
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
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")

    // ---- Google AdMob (legacy GMA SDK) ----
    // Not: 25.4.0, AppLovin google-adapter 25.4.0.0 ile sertifikasyon uyumu için seçildi.
    api("com.google.android.gms:play-services-ads:25.4.0")
    // Google UMP (GDPR consent)
    api("com.google.android.ump:user-messaging-platform:4.0.0")

    // ---- AppLovin MAX ----
    api("com.applovin:applovin-sdk:13.6.4")

    // ---- AppLovin MAX mediation adapters ----
    // google-adapter sürümü GMA sürümüyle eşleşmeli (x.y.z.n -> GMA x.y.z).
    implementation("com.applovin.mediation:google-adapter:25.4.0.0") // AdMob
    // Diğer adaptörlerde AppLovin'in önerdiği gibi en güncel sürüm ("+") kullanılır.
    implementation("com.applovin.mediation:fyber-adapter:+")         // DT Exchange (Fyber)
    implementation("com.applovin.mediation:vungle-adapter:+")        // Liftoff (Vungle)
    implementation("com.applovin.mediation:facebook-adapter:+")      // Meta Audience Network
    implementation("com.applovin.mediation:mintegral-adapter:+")     // Mintegral
    implementation("com.applovin.mediation:bytedance-adapter:+")     // Pangle (ByteDance)
    implementation("com.applovin.mediation:unityads-adapter:+")      // Unity Ads
}
