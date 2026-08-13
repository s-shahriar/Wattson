# DataTier is resolved by name from a debug launch intent (Capabilities.debugOverride).
# The call site is behind BuildConfig.DEBUG so release should strip it, but keeping the
# constant names costs nothing and avoids a surprise if that ever changes.
-keepclassmembers enum com.syed.wattson.data.DataTier {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Line numbers make any crash report from a released build readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ViewModelProvider constructs these reflectively; without their constructors the
# screen would fail at runtime rather than at build time.
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# FileProvider is referenced only from the manifest.
-keep class androidx.core.content.FileProvider { *; }
