-optimizationpasses 5
-keepattributes JavascriptInterface
-keepattributes *Annotation*
-keep class com.tiltbox.launcher.** { *; }
-keepclassmembers class com.tiltbox.launcher.MainActivity$TiltboxBridge {
    @android.webkit.JavascriptInterface <methods>;
}
