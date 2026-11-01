-optimizationpasses 5
-keepattributes JavascriptInterface
-keepattributes *Annotation*
-keep class com.tiltbox.launcher.MainActivity { *; }
-keep class com.tiltbox.launcher.WeatherService { *; }
-keepclassmembers class com.tiltbox.launcher.MainActivity$TiltboxBridge {
    @android.webkit.JavascriptInterface <methods>;
}
