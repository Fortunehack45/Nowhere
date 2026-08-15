# ProGuard rules for MockLocationApp
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-keep class com.fakegps.mocklocation.data.db.** { *; }
-dontwarn org.osmdroid.**
