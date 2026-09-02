package org.bittrace.tools

import androidx.compose.runtime.mutableStateListOf

/**
 * The tools, each of which opens in a window of its own.
 *
 * A window rather than a view in the rail: a tool is something you run *beside*
 * what you were doing — comparing two flows while still watching traffic arrive —
 * and a view would make you leave the thing you opened it to look at.
 */
enum class Tool(val id: String, val title: String) {
    DIFF("diff", "Diff"),
}

/**
 * Which tool windows are open.
 *
 * One window per tool, not per invocation: asking for Diff twice should bring
 * you the diff you already have rather than a second empty one, which is what a
 * set of open tools gives for free.
 */
class ToolWindows {

    private val opened = mutableStateListOf<Tool>()

    /** The tools currently on screen, in the order they were opened. */
    val open: List<Tool> get() = opened

    fun show(tool: Tool) {
        if (tool !in opened) opened += tool
    }

    fun close(tool: Tool) {
        opened -= tool
    }
}
