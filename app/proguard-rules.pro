# ProGuard & R8 Optimization Rules for Nowhere (Mock Location & GPS Engine)

# Preserve generic signatures and annotations for reflection and serialization
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# Room Database
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-keep class com.fakegps.mocklocation.data.db.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }

# ViewBinding
-keep class com.fakegps.mocklocation.databinding.** { *; }
-keepclassmembers class **.R$* {
    public static <fields>;
}

# OpenStreetMap (osmdroid)
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Google Mobile Ads (AdMob)
-keep public class com.google.android.gms.ads.** {
    public *;
}
-keep public class com.google.ads.** {
    public *;
}
-dontwarn com.google.android.gms.ads.**

# ZXing QR Code Engine
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Kotlin Coroutines & Flow
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# App Data Models & Preferences
-keep class com.fakegps.mocklocation.data.model.** { *; }
-keep class com.fakegps.mocklocation.simulator.** { *; }
-keep class com.fakegps.mocklocation.weather.** { *; }
-keep class com.fakegps.mocklocation.util.AppUpdateManager$** { *; }

