package com.github.yuriysemen.platesdetector

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object ModelUpdateLog {

    enum class Level { INFO, SUCCESS, ERROR }

    data class Entry(
        val timeMs: Long = System.currentTimeMillis(),
        val message: String,
        val level: Level = Level.INFO
    )

    private const val MAX_ENTRIES = 100

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    val latest: Entry? get() = _entries.value.lastOrNull()

    fun log(message: String, level: Level = Level.INFO) {
        _entries.update { (it + Entry(message = message, level = level)).takeLast(MAX_ENTRIES) }
    }
}
