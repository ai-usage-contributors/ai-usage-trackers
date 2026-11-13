package local.aiusagetrackers

object UnitEquivalence {
    fun exactHint(definition: TrackerDefinition): String? {
        val divisor = when (definition.unit) {
            SpanUnit.MINUTE -> 60
            SpanUnit.HOUR -> 24
            SpanUnit.DAY -> 7
            else -> return null
        }
        if (definition.amount % divisor != 0) return null
        val exact = definition.mode == PeriodMode.ELAPSED ||
                (definition.mode == PeriodMode.REPEATING && definition.unit in setOf(SpanUnit.MINUTE, SpanUnit.DAY))
        if (!exact) return null
        val next =
            when (definition.unit) {
                SpanUnit.MINUTE -> "${if (definition.mode == PeriodMode.REPEATING) "repeating" else "elapsed"} hour"
                SpanUnit.HOUR -> "elapsed day"
                else -> if (definition.mode == PeriodMode.REPEATING) "repeating week" else "elapsed week"
            }
        val count = definition.amount / divisor
        return "${definition.amount} ${definition.unit.label.lowercase()} = $count $next${if (count == 1) "" else "s"}. " +
                "With the same ${if (definition.mode == PeriodMode.REPEATING) "anchor" else "ending boundary"}, the tracked range is identical. Your configuration is unchanged."
    }

    const val EXPLANATION =
        "Elapsed durations allow exact conversions: 60 minutes = 1 elapsed hour; 24 hours = 1 elapsed day; 7 days = 1 elapsed week. " +
                "Calendar hours/days/weeks have different reset boundaries and are not generally equivalent. " +
                "Repeating 7 days equals 1 repeating week with the same anchor, but repeating 24 hours may differ from a day across daylight-saving changes. " +
                "Months have variable lengths; weeks cannot be converted exactly to months."
}
