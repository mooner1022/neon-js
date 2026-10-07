# Found through java.util.ServiceLoader (META-INF/services/io.neonjs.android.DexConverter).
-keep class io.neonjs.android.d8.D8Converter { public <init>(); }
# The r8 library ships shrunk and obfuscated, finds parts of itself through ServiceLoader and reads its resources/
# files; keep it as it is. It refers to JDK classes Android lacks, on paths the in-memory conversion does not take.
-keep class com.android.tools.r8.** { *; }
-dontwarn com.android.tools.r8.**
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.ProcessHandle$Info
-dontwarn javax.xml.stream.**
-dontwarn java.lang.management.**
-dontwarn sun.misc.Unsafe
