plugins {
    id("com.android.library")
}

// AGP 9 Kotlin desteğini kendi içinde barındırır (kotlin eklentisi otomatik gelir).
// Bu modül AGP 8 kullanan bir uygulama projesine kaynak modül olarak dahil
// edildiğinde ise Kotlin plugin'i ayrıca uygulanmalıdır.
if (extensions.findByName("kotlin") == null) {
    pluginManager.apply("org.jetbrains.kotlin.android")
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

// kotlin { compilerOptions { ... } } yerine task bazlı ayar: hem AGP 9'un yerleşik
// Kotlin desteğiyle hem de ayrıca uygulanan kotlin-android plugin'iyle çalışır.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Not: 1.19.0 / 2.11.0 sürümleri compileSdk 37 + AGP 9.1 zorunlu kılıyor.
    // Tüketici uygulamalar halen AGP 8 / compileSdk 36'da olduğu için bir alt seri kullanılıyor.
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")

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
