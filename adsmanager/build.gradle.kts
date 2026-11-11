plugins {
    id("com.android.library")
    id("maven-publish")
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

    // maven-publish'in components["release"] bileşenini görebilmesi için gerekli.
    // Sadece release varyanti yayinlanir; kaynak kodu da sources jar olarak eklenir.
    publishing {
        singleVariant("release") {
            withSourcesJar()
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
    // Not: 25.4.0, hem AppLovin google-adapter 25.4.0.0 hem de Google mediation
    // adaptörleri (adsmanager-admob) ile sertifikasyon uyumu için seçildi.
    api("com.google.android.gms:play-services-ads:25.4.0")
    // Google UMP (GDPR consent)
    api("com.google.android.ump:user-messaging-platform:4.0.0")

    // ---- AppLovin MAX ----
    api("com.applovin:applovin-sdk:13.6.4")

    // Mediation adaptörleri burada değil: MAX için adsmanager-max, AdMob mediation için
    // adsmanager-admob. Bir uygulamada iki aile birden olmamalı (aynı ağ SDK'sı çakışır).

    // ---- JVM unit test (./gradlew :adsmanager:testDebugUnitTest) ----
    testImplementation("junit:junit:4.13.2")
    // android.jar'daki org.json stub'dır; JSON parser testleri gerçek implementasyonu kullanır.
    testImplementation("org.json:json:20260814")
}

// ---- Yayinlama (JitPack) ----
// JitPack, maven-publish eklentisini gorunce `./gradlew build publishToMavenLocal`
// calistirir ve build sirasinda GROUP / ARTIFACT / VERSION ortam degiskenlerini set eder.
// Yerelde denerken (publishToMavenLocal) asagidaki varsayilanlar kullanilir.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = System.getenv("GROUP") ?: "com.github.helikanon"
                artifactId = "adsmanager"
                version = System.getenv("VERSION") ?: "1.0.0-local"
            }
        }
    }
}
