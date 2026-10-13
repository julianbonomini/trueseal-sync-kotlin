@file:Suppress("PackageDirectoryMismatch")

// KOTLIN-2 compile stub — TEST-ONLY, never shipped.
//
// The shared sources keep every android.* reference behind one seam
// (Storage.android). On a real device that seam binds against the Android
// SDK; this lane has no Android SDK, so it compiles the same sources
// against this minimal stub instead. No code on the JVM lane ever calls
// Storage.android (the driver uses Storage.directory) — the stub exists
// purely so the SAME source files compile unmodified.
package android.content

import java.io.File

abstract class Context {
    abstract val noBackupFilesDir: File
}
