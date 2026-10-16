package com.zzyan.r1.muse.core

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Side-chat registry: sessions, per-session history, unread counters, persisted locally. */
internal class SideChats(context: Context) {
    internal class Chat(val id: String, val name: String, val unread: Int)

    private val file = AtomicFile(File(context.noBackupFilesDir, "side-chats.json"))
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private var mainUnread = 0

    private class Entry(var name: String, var unread: Int, var history: List<ConversationTurn>)

    init { load() }

    /** Record activity in a side chat; bump its unread unless the r1 currently shows it. */
    fun observe(sessionId: String, visibleSession: String?) {
        synchronized(lock) {
            val entry = entries.getOrPut(sessionId) { Entry(nameOf(entries.size + 1), 0, emptyList()) }
            if (sessionId != visibleSession) entry.unread++
            persistLocked()
        }
    }

    fun markRead(sessionId: String) {
        synchronized(lock) {
            entries[sessionId]?.let { if (it.unread != 0) { it.unread = 0; persistLocked() } }
        }
    }

    fun list(): List<Chat> = synchronized(lock) {
        entries.entries.reversed().map { Chat(it.key, it.value.name, it.value.unread) }
    }

    /** A session discovered to be the main chat leaves the side list; its unread merges into main. */
    fun promoteMain(sessionId: String) {
        synchronized(lock) {
            entries.remove(sessionId)?.let { mainUnread += it.unread; persistLocked() }
        }
    }

    fun observeMain() {
        synchronized(lock) { mainUnread++; persistLocked() }
    }

    fun markMainRead() {
        synchronized(lock) { if (mainUnread != 0) { mainUnread = 0; persistLocked() } }
    }

    fun unreadMain(): Int = synchronized(lock) { mainUnread }

    fun totalUnread(): Int = synchronized(lock) { mainUnread + entries.values.sumOf { it.unread } }

    fun create(): String {
        val id = UUID.randomUUID().toString()
        synchronized(lock) {
            entries.getOrPut(id) { Entry(nameOf(entries.size + 1), 0, emptyList()) }
            persistLocked()
        }
        return id
    }

    fun rename(sessionId: String, name: String) {
        synchronized(lock) {
            val entry = entries.getOrPut(sessionId) { Entry(nameOf(entries.size + 1), 0, emptyList()) }
            if (entry.name != name) { entry.name = name; persistLocked() }
        }
    }

    fun load(sessionId: String): List<ConversationTurn> = synchronized(lock) { entries[sessionId]?.history ?: emptyList() }

    fun save(sessionId: String, turns: List<ConversationTurn>) {
        synchronized(lock) {
            entries.getOrPut(sessionId) { Entry(nameOf(entries.size + 1), 0, emptyList()) }.history = turns
            persistLocked()
        }
    }

    private fun nameOf(index: Int) = "Side chat $index"

    private fun persistLocked() {
        try {
            val json = JSONObject().put("main_unread", mainUnread).put("sessions", JSONObject().apply {
                entries.forEach { (id, entry) ->
                    put(id, JSONObject().put("name", entry.name).put("unread", entry.unread)
                        .put("history", encodeHistory(entry.history)))
                }
            })
            val out = file.startWrite()
            try {
                out.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out)
            } catch (_: Exception) { file.failWrite(out) }
        } catch (_: Exception) { /* Storage errors must not break chat switching. */ }
    }

    private fun load() {
        try {
            if (!file.baseFile.exists()) return
            val root = JSONObject(file.readFully().toString(Charsets.UTF_8))
            val sessions = root.optJSONObject("sessions") ?: return
            sessions.keys().forEach { id ->
                val entry = sessions.getJSONObject(id)
                entries[id] = Entry(entry.optString("name").ifBlank { nameOf(entries.size + 1) },
                    entry.optInt("unread"), decodeHistory(entry.optString("history", "[]")))
                mainUnread = root.optInt("main_unread")
            }
        } catch (_: Exception) { entries.clear() }
    }
}