# AdsManager kütüphanesini kullanan uygulamalara otomatik uygulanan R8/ProGuard kuralları

# Kütüphanenin public API'si
-keep class com.helikanon.adsmanager.** { *; }

# Google Mobile Ads
-keep class com.google.android.gms.ads.** { *; }
-dontwarn com.google.android.gms.ads.**

# AppLovin
-keep class com.applovin.** { *; }
-dontwarn com.applovin.**
-keep class com.google.android.gms.appset.** { *; }

# Meta Audience Network
-keep class com.facebook.ads.** { *; }
-dontwarn com.facebook.ads.**

# Mintegral
-keep class com.mbridge.** { *; }
-dontwarn com.mbridge.**

# Pangle
-keep class com.bytedance.sdk.** { *; }
-dontwarn com.bytedance.sdk.**

# Vungle / Liftoff
-keep class com.vungle.** { *; }
-dontwarn com.vungle.**

# Unity Ads
-keep class com.unity3d.ads.** { *; }
-dontwarn com.unity3d.ads.**

# DT Exchange (Fyber)
-keep class com.fyber.** { *; }
-dontwarn com.fyber.**
