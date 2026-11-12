package com.arin.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Something Arin has learned about the person, kept only on this phone. */
@Serializable
data class Memory(val id: String = UUID.randomUUID().toString(), val text: String, val added: Long = System.currentTimeMillis())

class MemoryStore(context: Context) {
    private val file = File(context.filesDir, "memory.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Kept small so it doesn't crowd out the conversation. */
    private val maxItems = 200

    @Synchronized
    fun list(): List<Memory> = runCatching { json.decodeFromString<List<Memory>>(file.readText()) }.getOrDefault(emptyList())

    /** Adds [text] unless an item with the same wording is already there. Returns true if added. */
    @Synchronized
    fun add(text: String): Boolean {
        val clean = text.trim().removeSuffix(".").trim()
        if (clean.length < 3 || clean.length > 300) return false
        val items = list()
        if (items.any { it.text.equals(clean, ignoreCase = true) }) return false
        write((items + Memory(text = clean)).takeLast(maxItems))
        return true
    }

    @Synchronized
    fun remove(id: String) = write(list().filterNot { it.id == id })

    @Synchronized
    fun clear() { file.delete() }

    private fun write(items: List<Memory>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(items))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
