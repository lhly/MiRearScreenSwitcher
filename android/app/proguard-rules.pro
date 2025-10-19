# Shizuku
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class android.content.pm.** { *; }

# AIDL
-keep class com.tgwgroup.MiRearScreenSwitcher.ITaskService { *; }
-keep class com.tgwgroup.MiRearScreenSwitcher.ITaskService$Stub { *; }
-keep class com.tgwgroup.MiRearScreenSwitcher.ITaskService$Stub$Proxy { *; }

# TaskService
-keep class com.tgwgroup.MiRearScreenSwitcher.TaskService { *; }
-keepclassmembers class com.tgwgroup.MiRearScreenSwitcher.TaskService {
    public <init>();
}

# Flutter
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.** { *; }
-keep class io.flutter.util.** { *; }
-keep class io.flutter.view.** { *; }
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }

# Play Core (不使用但需要忽略警告)
-dontwarn com.google.android.play.core.**

# Services and Activities
-keep class com.tgwgroup.MiRearScreenSwitcher.MainActivity { *; }
-keep class com.tgwgroup.MiRearScreenSwitcher.MyApplication { *; }
-keep class com.tgwgroup.MiRearScreenSwitcher.**Service { *; }
-keep class com.tgwgroup.MiRearScreenSwitcher.**Activity { *; }
-keep class com.tgwgroup.MiRearScreenSwitcher.**Receiver { *; }

