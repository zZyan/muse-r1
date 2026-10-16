package com.zzyan.r1.muse.core

import android.content.Context
import java.io.File

/** Pull assets the official Muse app (com.facebook.aura) keeps on-device, via Magisk root. */
internal object RootAssets {
    private const val AURA = "com.facebook.aura"

    fun avatarCacheDir(): String = "/data/data/$AURA/files/avatar_cache"

    /** Copy the newest Aura avatar into app-private storage; return it or null when root fails. */
    fun syncAvatar(context: Context): File? {
        val out = File(context.noBackupFilesDir, "avatar.webp")
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "src=\$(ls -t ${avatarCacheDir()}/avatar-*.webp 2>/dev/null | head -1); " +
                    "test -n \"\$src\" && cp -f \"\$src\" ${out.absolutePath} && chmod 644 ${out.absolutePath}"))
            if (proc.waitFor() == 0 && out.length() > 0) out else null
        } catch (_: Exception) { null }
    }
}