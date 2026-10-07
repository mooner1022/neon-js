# Code generated at run time (JIT-compiled functions, Java.extend adapters) calls engine classes by name, and service
# providers are found through java.util.ServiceLoader: keep the engine as is.
-keep class dev.mooner.neonjs.** { *; }
# Used only on standard JVMs, behind checks (allocation limits, hidden classes).
-dontwarn java.lang.management.**
-dontwarn com.sun.management.**
