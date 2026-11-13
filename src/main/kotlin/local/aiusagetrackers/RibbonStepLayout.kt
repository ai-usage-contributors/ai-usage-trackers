package local.aiusagetrackers

/** Pure pixel geometry; widths include entry insets, and gap includes the separator. */
internal object RibbonStepLayout {
    data class Entry(val id: String, val width: Int)

    fun horizontalOffsets(entries: List<Entry>, width: Int, gap: Int): List<Pair<String?, Int>> {
        if (width <= 0) return listOf(null to 0)
        var right = 0
        return buildList {
            add(null to 0)
            entries.forEachIndexed { index, entry ->
                if (index > 0) right += gap
                right += entry.width
                val offset = (right - width).coerceAtLeast(0)
                if (offset > last().second) add(entry.id to offset)
            }
        }
    }

    fun ordered(entries: List<Entry>, anchor: String?, direction: Int): List<Entry> {
        if (entries.isEmpty()) return emptyList()
        val start = entries.indexOfFirst { it.id == anchor }.coerceAtLeast(0)
        return List(entries.size) { entries[Math.floorMod(start + it * direction, entries.size)] }
    }

    fun row(entries: List<Entry>, width: Int, gap: Int): List<Entry> {
        if (width <= 0 || entries.isEmpty()) return emptyList()
        var used = 0
        return entries.takeWhile { entry ->
            val next = used + (if (used > 0) gap else 0) + entry.width
            if (used == 0 || next <= width) { used = next; true } else false
        }
    }

    fun fits(entries: List<Entry>, width: Int, gap: Int): Boolean =
        entries.sumOf { it.width } + gap * (entries.size - 1).coerceAtLeast(0) <= width

    /** Remaining entries are packed in traversal order, each appearing exactly once. */
    fun remainingRows(entries: List<Entry>, visible: Set<String>, width: Int, gap: Int): List<List<Entry>> {
        val remaining = entries.filterNot { it.id in visible }
        if (width <= 0) return emptyList()
        return buildList {
            var index = 0
            while (index < remaining.size) {
                val packed = row(remaining.subList(index, remaining.size), width, gap)
                add(packed)
                index += packed.size
            }
        }
    }

    fun survivingAnchor(old: List<String>, current: List<String>, anchor: String?, direction: Int): String? {
        if (anchor in current) return anchor
        val start = old.indexOf(anchor)
        if (start >= 0) for (step in 1..old.size) {
            val next = old[Math.floorMod(start + step * direction, old.size)]
            if (next in current) return next
        }
        return current.firstOrNull()
    }
}
