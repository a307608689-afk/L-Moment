# Keep the native engine bridge classes (vendor .so files bind JNI symbols to these
# package/class names; the bridge in librawdecoder.so forwards to them).
-keep class com.codex.lmapp.** { *; }
