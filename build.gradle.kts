// Top-level build file

// AGP 9 Kotlin desteğini kendi içinde barındırır; 'org.jetbrains.kotlin.android'
// plugin'i artık uygulanmaz. AGP'nin getirdiği KGP sürümünü yükseltmek için
// tek yol buildscript classpath'idir.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
}
