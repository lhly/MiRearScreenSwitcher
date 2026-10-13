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

# Services and Activities
-keep class com.tgwgroup.MiRearScreenSwitcher.MainActivity { *; }
-keep class com.tgwgroup.MiRearScreenSwitcher.MyApplication { *; }
-keep class com.tgwgroup.MiRearScreenSwitcher.**Service { *; }
-keep class com.tgwgroup.MiRearScreenSwitcher.**Activity { *; }
-keep class com.tgwgroup.MiRearScreenSwitcher.**Receiver { *; }

