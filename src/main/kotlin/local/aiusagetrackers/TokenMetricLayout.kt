package local.aiusagetrackers

enum class TokenMetric(val label: String) {
    TOKENS("Tokens"), WEEKLY("Weekly %"), FIVE_HOUR("5h %")
}

enum class TokenMetricOrder(vararg metrics: TokenMetric) {
    TOKENS_WEEKLY_FIVE_HOUR(TokenMetric.TOKENS, TokenMetric.WEEKLY, TokenMetric.FIVE_HOUR),
    TOKENS_FIVE_HOUR_WEEKLY(TokenMetric.TOKENS, TokenMetric.FIVE_HOUR, TokenMetric.WEEKLY),
    WEEKLY_TOKENS_FIVE_HOUR(TokenMetric.WEEKLY, TokenMetric.TOKENS, TokenMetric.FIVE_HOUR),
    WEEKLY_FIVE_HOUR_TOKENS(TokenMetric.WEEKLY, TokenMetric.FIVE_HOUR, TokenMetric.TOKENS),
    FIVE_HOUR_TOKENS_WEEKLY(TokenMetric.FIVE_HOUR, TokenMetric.TOKENS, TokenMetric.WEEKLY),
    FIVE_HOUR_WEEKLY_TOKENS(TokenMetric.FIVE_HOUR, TokenMetric.WEEKLY, TokenMetric.TOKENS);

    val metrics = metrics.toList()

    override fun toString(): String = metrics.joinToString(" → ") { it.label }
}

data class TokenMetricLayout(
    val order: TokenMetricOrder = TokenMetricOrder.TOKENS_WEEKLY_FIVE_HOUR,
    val tokenPrefix: String = "",
    val weeklyPrefix: String = "",
    val fiveHourPrefix: String = "",
) {
    fun parts(tokens: String, weekly: String?, fiveHour: String?): List<Pair<TokenMetric, String>> =
        order.metrics.mapNotNull { metric ->
            when (metric) {
                TokenMetric.TOKENS -> tokenPrefix + tokens
                TokenMetric.WEEKLY -> weekly?.let { weeklyPrefix + it }
                TokenMetric.FIVE_HOUR -> fiveHour?.let { fiveHourPrefix + it }
            }?.let { metric to it }
        }

    fun validate() {
        listOf(tokenPrefix, weeklyPrefix, fiveHourPrefix).forEach(::validatePrefix)
    }

    companion object {
        fun validatePrefix(text: String) {
            require(text.length <= 40 && text.none(Char::isISOControl)) {
                "Metric prefixes can contain up to 40 characters without control characters"
            }
        }
    }
}
