package com.arin.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A group of chats that share instructions and files. */
@Serializable
data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val instructions: String = "",
    val files: List<Attachment> = emptyList(),
    val updated: Long = System.currentTimeMillis(),
)

/** Keeps all projects in one JSON file in the app's private storage. */
class ProjectStore(context: Context) {
    private val file = File(context.filesDir, "projects.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun list(): List<Project> =
        runCatching { json.decodeFromString<List<Project>>(file.readText()) }.getOrDefault(emptyList())
            .sortedByDescending { it.updated }

    fun get(id: String): Project? = list().firstOrNull { it.id == id }

    @Synchronized
    fun save(p: Project) {
        write(list().filterNot { it.id == p.id } + p.copy(updated = System.currentTimeMillis()))
    }

    @Synchronized
    fun delete(id: String) {
        get(id)?.let { Attachments.deleteFiles(it.files) }
        write(list().filterNot { it.id == id })
    }

    @Synchronized
    fun deleteAll() {
        list().forEach { Attachments.deleteFiles(it.files) }
        file.delete()
    }

    private fun write(items: List<Project>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(items))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
