-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.** { *; }
-dontwarn com.arthenica.**

# AppFunctions serializes these types across the system agent boundary.
-keep class com.androidcompress.app.agent.** { *; }
-keep class androidx.appfunctions.** { *; }
