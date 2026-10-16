package com.zzyan.r1.muse.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Read the official Muse app's (com.facebook.aura) thread inventory via Magisk root. */
internal object AuraBridge {
    private const val AURA = "com.facebook.aura"

    internal class ThreadRow(val sessionId: String, val updatedAt: Long)

    /**
     * Copy the official app's offline thread list and read ids + recency.
     * Titles are encrypted by the official app's Keystore; ids/order/timestamps are plaintext.
     */
    fun syncThreadIds(context: Context): List<ThreadRow> {
        val ext = context.getExternalFilesDir(null) ?: File(context.cacheDir, "aura")
        val out = File(ext, "aura-threads.db")
        val wal = File(ext, "aura-threads.db-wal")
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c",
                "cp -f /data/data/$AURA/databases/hatch_offline_conversation.db ${out.absolutePath} && " +
                    "cp -f /data/data/$AURA/databases/hatch_offline_conversation.db-wal ${wal.absolutePath} 2>/dev/null; " +
                    "chmod 666 ${out.absolutePath} ${wal.absolutePath} 2>/dev/null; true"))
            val code = proc.waitFor()
            if (code != 0 && !out.exists()) return emptyList()
            val db = try { SQLiteDatabase.openDatabase(out.absolutePath, null, SQLiteDatabase.OPEN_READWRITE) } catch (_: Exception) { return emptyList() } ?: return emptyList()
            val rows = mutableListOf<ThreadRow>()
            try {
                db.rawQuery("SELECT session_id, updated_at FROM offline_threads WHERE is_thread=1 ORDER BY updated_at DESC", null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0) ?: continue
                        if (id.isNotBlank()) rows.add(ThreadRow(id, cursor.getLong(1)))
                    }
                }
            } finally { db.close() }
            if (out.length() > 0) out.delete(); if (wal.length() > 0) wal.delete()
            rows
        } catch (_: Exception) {
            try { out.delete(); wal.delete() } catch (_: Exception) { }
            emptyList()
        }
    }
}