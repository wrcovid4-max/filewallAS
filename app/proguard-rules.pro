# Room generates implementations reflectively at build time; nothing extra needed.
# Keep the Wear Data Layer service entry points reachable from the framework.
-keep class com.filewall.data.wear.PhoneWearListenerService { *; }
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
