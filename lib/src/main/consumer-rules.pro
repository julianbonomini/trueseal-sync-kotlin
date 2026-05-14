# hush-sync-kotlin — consumer ProGuard rules
#
# These rules are merged into the consuming app's R8/ProGuard config automatically
# when the library is added as a dependency.

# ── JNA ──────────────────────────────────────────────────────────────────────
# JNA uses reflection to locate and call native methods. R8 full-mode will strip
# these unless explicitly kept.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }

# ── UniFFI generated code ─────────────────────────────────────────────────────
# UniFFI Kotlin glue implements JNA callback interfaces via anonymous inner classes.
-keep class uniffi.hush_sync.** { *; }
-keep class uniffi.hush_noise.** { *; }
