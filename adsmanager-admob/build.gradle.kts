plugins {
    id("com.android.library")
    id("maven-publish")
}

// Core (adsmanager) + Google AdMob mediation adaptörleri. Manifest'teki işaret core'a AdMob
// yüklemelerini init callback'inden sonra başlatmasını söyler; AdMobMediationPrivacy doNotSell'i
// ağlara iletir (core onu MediationPrivacy.IMPLEMENTATION adıyla bulur).
// AdMob mediation kullanan uygulamalar yalnızca bu modülü ekler; adsmanager-max ile birlikte kullanılmaz.
android {
    namespace = "com.helikanon.adsmanager.admob"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        // core ile aynı: AGP 9 tüketiciyi compileSdk 37'ye zorlamasın.
        aarMetadata {
            minCompileSdk = 36
        }
    }

    publishing {
        singleVariant("release")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":adsmanager"))
    // AdMob mediation modu GMA'nın en son sürümünü kullanır (en son Google adaptörleri onunla
    // derleniyor). Core 25.4.0'da kalır: MAX modundaki AppLovin google-adapter o sürüme sertifikalı.
    api("com.google.android.gms:play-services-ads:25.5.0")

    // Google adaptör sürümü x.y.z.n -> ağ SDK'sı x.y.z. Sürümler Google'ın ağ bazlı entegrasyon
    // sayfalarındaki en son sürümlerdir; adsmanager-max'teki ağ SDK sürümleriyle aynı olmaları
    // gerekmez, çünkü iki modül aynı uygulamada bulunmaz.
    implementation("com.google.ads.mediation:applovin:13.7.0.0")   // AppLovin (SDK 13.7.0)
    implementation("com.google.ads.mediation:fyber:8.5.1.0")       // DT Exchange (Fyber)
    implementation("com.google.ads.mediation:vungle:7.7.9.0")      // Liftoff (Vungle)
    implementation("com.google.ads.mediation:facebook:6.22.0.1")   // Meta Audience Network
    implementation("com.google.ads.mediation:mintegral:17.2.21.0") // Mintegral
    implementation("com.google.ads.mediation:pangle:8.4.0.5.0")    // Pangle (ByteDance)
    implementation("com.google.ads.mediation:unity:4.21.0.0")      // Unity Ads
    // Google'ın Unity adaptörü Unity Ads SDK'sını kendisi getirmez; ayrıca eklenmeli.
    implementation("com.unity3d.ads:unity-ads:4.21.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = System.getenv("GROUP") ?: "com.github.helikanon"
                artifactId = "adsmanager-admob"
                version = System.getenv("VERSION") ?: "1.0.0-local"
            }
        }
    }
}
