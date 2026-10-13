@file:Suppress("PackageDirectoryMismatch", "unused")

// KOTLIN-2 compile stub — TEST-ONLY, never shipped.
//
// Bridges.kt reports contained listener failures through android.util.Log,
// falling back to System.err when the class is absent. This stub makes the
// same sources compile on the JVM lane and routes warnings to stderr, so
// the fallback path is never needed here.
package android.util

class Log {
    companion object {
        @JvmStatic
        fun w(tag: String, msg: String): Int {
            System.err.println("[$tag] $msg")
            return msg.length
        }
    }
}
