package local.aiusagetrackers

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

object TrackerOrdering {
    fun sortByDuration(existing: List<TrackerDefinition>, ascending: Boolean): List<TrackerDefinition> {
        val byDuration = compareBy(::duration)
        return existing.sortedWith(if (ascending) byDuration else byDuration.reversed())
    }

    private fun duration(tracker: TrackerDefinition): Duration {
        if (tracker.mode == PeriodMode.FIXED) {
            val explicit = runCatching {
                val zone = tracker.zoneId.takeIf(String::isNotBlank)?.let(ZoneId::of) ?: ZoneId.systemDefault()
                val start = LocalDateTime.parse(tracker.fixedStart).atZone(zone)
                val end = LocalDateTime.parse(tracker.fixedEnd).atZone(zone)
                Duration.between(start, end)
            }.getOrNull()
            if (explicit != null && !explicit.isNegative) return explicit
        }
        val minutes = when (tracker.unit) {
            SpanUnit.MINUTE -> 1L
            SpanUnit.HOUR -> 60L
            SpanUnit.DAY -> 1440L
            SpanUnit.WEEK -> 10080L
            SpanUnit.MONTH -> 43200L
        }
        return Duration.ofMinutes(minutes * tracker.amount)
    }

    private fun rank(unit: SpanUnit): Int = when (unit) {
        SpanUnit.MINUTE -> 0; SpanUnit.HOUR -> 1; SpanUnit.DAY -> 2; SpanUnit.WEEK -> 3; SpanUnit.MONTH -> 4
    }

    /** The insertion index is a gap in the visible sequence before removing the source. */
    fun moveVisible(existing: List<TrackerDefinition>, sourceId: String, insertion: Int): List<TrackerDefinition> {
        val visible = existing.filter { it.visible }.toMutableList()
        if (existing.map { it.id }.distinct().size != existing.size || insertion !in 0..visible.size) return existing
        val from = visible.indexOfFirst { it.id == sourceId }
        if (from < 0) return existing
        val value = visible.removeAt(from)
        visible.add(if (insertion > from) insertion - 1 else insertion, value)
        var next = 0
        return existing.map { if (it.visible) visible[next++] else it }
    }

    fun insertionIndex(existing: List<TrackerDefinition>, added: TrackerDefinition): Int {
        if ((existing + added).any { it.amount != 1 || it.mode == PeriodMode.FIXED }) return existing.size
        val ranks = existing.map { rank(it.unit) }
        val ascending = ranks.zipWithNext().all { (a, b) -> a <= b }
        val descending = ranks.zipWithNext().all { (a, b) -> a >= b }
        if (!ascending && !descending) return existing.size
        val index = if (ascending) ranks.indexOfFirst { it > rank(added.unit) }
        else ranks.indexOfFirst { it < rank(added.unit) }
        return if (index < 0) existing.size else index
    }
}
