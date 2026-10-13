package dev.trueseal.sync

import java.io.File

/**
 * Where Session State lives on disk (ADR-0028: the SDK takes an explicit
 * storage location).
 *
 * Use [Storage.android] on Android; the plain [directory] factories keep
 * every `android.*` import behind the Android helper, so the JVM test
 * build (KOTLIN-2) compiles the same sources.
 *
 * Data at rest is protected by the OS sandbox and the store is EXCLUDED
 * FROM BACKUPS (sync ADR-0032, threat model): [android] resolves under
 * `Context.getNoBackupFilesDir()`, which Android's Auto Backup and
 * device-to-device transfer skip by construction. (A library AAR cannot
 * ship `android:dataExtractionRules` — those are application manifest
 * attributes — so `noBackupFilesDir` is the mechanism, and it is the one
 * the threat model names.)
 */
class Storage private constructor(internal val path: String) {

    companion object {
        /**
         * Session State under [context]'s no-backup files directory,
         * namespaced per app (`<noBackupFilesDir>/TruesealSync`).
         * Excluded from Android Auto Backup by construction (ADR-0032).
         */
        @JvmStatic
        fun android(context: android.content.Context): Storage =
            Storage(File(context.noBackupFilesDir, "TruesealSync").absolutePath)

        /** Session State under an explicit directory path (JVM/tests). */
        @JvmStatic
        fun directory(path: String): Storage = Storage(path)

        /** Session State under an explicit directory (JVM/tests). */
        @JvmStatic
        fun directory(file: File): Storage = Storage(file.absolutePath)
    }
}
