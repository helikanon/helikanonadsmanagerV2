plugins {
    id("com.android.library")
    id("maven-publish")
}

// Kod içermez: core (adsmanager) + Google AdMob mediation adaptörleri. Manifest'teki işaret
// core'a AdMob yüklemelerini init callback'inden sonra başlatmasını söyler.
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
}

dependencies {
    api(project(":adsmanager"))

    // Google adaptör sürümü x.y.z.n -> ağ SDK'sı x.y.z. Seçilen sürümlerin hepsi core'daki
    // GMA 25.4.0'ı ister ve adsmanager-max ile aynı ağ SDK sürümlerini kullanır.
    // Yükseltirken her adaptörün POM'undaki play-services-ads sürümünün core ile aynı kaldığını kontrol et.
    implementation("com.google.ads.mediation:applovin:13.6.4.2")   // AppLovin (SDK core'daki 13.6.4)
    implementation("com.google.ads.mediation:fyber:8.4.7.1")       // DT Exchange (Fyber)
    implementation("com.google.ads.mediation:vungle:7.7.7.0")      // Liftoff (Vungle)
    implementation("com.google.ads.mediation:facebook:6.22.0.1")   // Meta Audience Network
    implementation("com.google.ads.mediation:mintegral:17.1.81.1") // Mintegral
    implementation("com.google.ads.mediation:pangle:8.2.0.4.0")    // Pangle (ByteDance)
    implementation("com.google.ads.mediation:unity:4.20.0.2")      // Unity Ads
    // Google'ın Unity adaptörü Unity Ads SDK'sını kendisi getirmez; ayrıca eklenmeli.
    implementation("com.unity3d.ads:unity-ads:4.20.0")
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
