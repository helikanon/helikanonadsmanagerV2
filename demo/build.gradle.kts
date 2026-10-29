plugins {
    id("com.android.application")
}

android {
    namespace = "com.helikanon.adsmanager.demo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.helikanon.adsmanager.demo"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        // MAX'i denemek için SDK key repoya yazılmadan verilir:
        // ./gradlew :demo:installDebug -PapplovinSdkKey=...
        buildConfigField("String", "APPLOVIN_SDK_KEY", "\"${providers.gradleProperty("applovinSdkKey").getOrElse("")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Örnek kodda testMode = BuildConfig.DEBUG kullanılıyor (AGP 8'den beri opt-in).
    buildFeatures {
        buildConfig = true
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
    implementation(project(":adsmanager"))
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
}
