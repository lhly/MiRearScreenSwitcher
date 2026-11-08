# QuickJS runtime JNI harness

`RuntimeHarness` is a host-JVM test of the exact QuickJS C/JNI bridge used by the Android library. It verifies reducer/state flow and runtime bounds; it does **not** claim Android service isolation, Binder behavior, Android PSS, or device latency. Build with the repository JDK 21, Android SDK CMake 3.22.1 and the app's NDK using the parent runtime CMake file, then compile this Java class together with `QuickJsNative.java` and run with `java.library.path` pointing at the host shared-library output.
