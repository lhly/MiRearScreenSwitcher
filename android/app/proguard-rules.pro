# Shizuku SDK supplies its BinderContainer parcelable rules through its AAR.
# Retain the application's remote contract below; unused SDK APIs may be shrunk.

# AIDL is the cross-process contract shared with Shizuku's user service.
# Keep its descriptor/API names while allowing optimization inside implementations.
-keep,allowoptimization class top.zainzh.rearscreen.ITaskService { *; }
-keep,allowoptimization class top.zainzh.rearscreen.ITaskService$Stub { *; }
-keep,allowoptimization class top.zainzh.rearscreen.ITaskService$Stub$Proxy { *; }
-keep,allowoptimization class top.zainzh.rearscreen.IRearGestureListener { *; }
-keep,allowoptimization class top.zainzh.rearscreen.IRearGestureListener$Stub { *; }
-keep,allowoptimization class top.zainzh.rearscreen.IRearGestureListener$Stub$Proxy { *; }

# Shizuku instantiates this Binder entry point reflectively via its public no-arg constructor.
-keep,allowoptimization class top.zainzh.rearscreen.TaskService { public <init>(); }

# Manifest components and XML views/constructors are retained by AAPT-generated rules.
# Do not keep every private field/method of every Activity/Service/Receiver.
