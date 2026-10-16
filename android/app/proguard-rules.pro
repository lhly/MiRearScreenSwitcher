# Shizuku
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class android.content.pm.** { *; }

# AIDL
-keep class top.zainzh.rearscreen.ITaskService { *; }
-keep class top.zainzh.rearscreen.ITaskService$Stub { *; }
-keep class top.zainzh.rearscreen.ITaskService$Stub$Proxy { *; }

# TaskService
-keep class top.zainzh.rearscreen.TaskService { *; }
-keepclassmembers class top.zainzh.rearscreen.TaskService {
    public <init>();
}

# Services and Activities
-keep class top.zainzh.rearscreen.MainActivity { *; }
-keep class top.zainzh.rearscreen.MyApplication { *; }
-keep class top.zainzh.rearscreen.**Service { *; }
-keep class top.zainzh.rearscreen.**Activity { *; }
-keep class top.zainzh.rearscreen.**Receiver { *; }

