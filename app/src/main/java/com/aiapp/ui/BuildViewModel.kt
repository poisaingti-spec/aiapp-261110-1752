package com.aiapp.ui

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import com.aiapp.builder.BuildRequest
import com.aiapp.builder.BuildRunner
import com.aiapp.builder.BuildService
import com.aiapp.builder.Project
import com.aiapp.builder.TimelineItem

/** Thin wrapper: the build itself runs in [BuildService] so it survives closing the app. */
class BuildViewModel(private val app: Application) : AndroidViewModel(app) {
    init {
        BuildRunner.load(app)
    }

    /**
     * Everything the user has asked for, collected so one "Build" implements all of it at once.
     * Shown as a checklist in the Build tab before building.
     */
    val requirements = mutableStateListOf<String>()

    fun addRequirement(text: String) {
        val t = text.trim()
        if (t.isNotEmpty() && requirements.none { it.equals(t, ignoreCase = true) }) requirements.add(t)
    }

    fun addRequirements(texts: List<String>) = texts.forEach { addRequirement(it) }

    fun removeRequirement(index: Int) {
        if (index in requirements.indices) requirements.removeAt(index)
    }

    fun clearRequirements() = requirements.clear()

    /** The requirements plus any final text, as one brief for the build agent. */
    fun combinedBrief(finalText: String): String {
        val all = (requirements.toList() + finalText.trim()).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return when {
            all.isEmpty() -> ""
            all.size == 1 -> all[0]
            else -> "Implement ALL of these requirements in the app:\n" +
                all.mapIndexed { i, r -> "${i + 1}. $r" }.joinToString("\n")
        }
    }

    val timeline: List<TimelineItem> get() = BuildRunner.timeline
    val running: Boolean get() = BuildRunner.running
    val project: Project? get() = BuildRunner.project

    fun start(request: BuildRequest) = BuildService.start(app, request)

    fun stop() = BuildService.stop(app)

    fun newProject() = BuildRunner.newProject(app)

    fun projects() = BuildRunner.list(app)

    fun openProject(id: String) = BuildRunner.open(app, id)

    fun deleteProject(id: String) = BuildRunner.delete(app, id)
}
