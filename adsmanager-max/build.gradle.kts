plugins {
    id("com.android.library")
    id("maven-publish")
}

// Kod içermez: core (adsmanager) + AppLovin MAX mediation adaptörleri.
// MAX kullanan uygulamalar yalnızca bu modülü ekler; adsmanager-admob ile birlikte kullanılmaz.
android {
    namespace = "com.helikanon.adsmanager.max"
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

    // google-adapter sürümü core'daki GMA sürümüyle eşleşmeli (x.y.z.n -> GMA x.y.z).
    implementation("com.applovin.mediation:google-adapter:25.4.0.0") // AdMob
    // AppLovin "+" (en guncel) onerir; ancak bu kutuphane JitPack uzerinden
    // yayinlandigi icin surumler sabitlenir: aksi halde tuketici her build'de
    // farkli bir adaptor surumu cekebilir ve build tekrarlanabilir olmaz.
    // Yeni surum cikarirken bu bloku elden gecir (bkz. AppLovin MAX changelog).
    implementation("com.applovin.mediation:fyber-adapter:8.4.7.0")       // DT Exchange (Fyber)
    implementation("com.applovin.mediation:vungle-adapter:7.7.7.1")      // Liftoff (Vungle)
    implementation("com.applovin.mediation:facebook-adapter:6.22.0.0")   // Meta Audience Network
    implementation("com.applovin.mediation:mintegral-adapter:17.1.81.0") // Mintegral
    implementation("com.applovin.mediation:bytedance-adapter:8.2.0.4.0") // Pangle (ByteDance)
    implementation("com.applovin.mediation:unityads-adapter:4.20.0.0")   // Unity Ads
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = System.getenv("GROUP") ?: "com.github.helikanon"
                artifactId = "adsmanager-max"
                version = System.getenv("VERSION") ?: "1.0.0-local"
            }
        }
    }
}
