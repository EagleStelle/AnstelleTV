package tv.anstelle.core.jellyfin.sync

import org.jellyfin.sdk.model.api.ParentalRating

/**
 * Maps an `OfficialRating` string to a numeric score (roughly the minimum age) using the
 * server's `/Localization/ParentalRatings` table, so kid profiles can filter with
 * `ratingScore <= maxRating`.
 *
 * The table only covers the server's metadata country, while items often carry ratings
 * from elsewhere ("12", "NC16", "M18", "PG13", "6+"). Those fall back to a
 * punctuation-insensitive match, then to the age written in the rating.
 */
class RatingScores(
    /** The server's table as it lists it, rating name to score; the parental rating choices are made from it. */
    val table: Map<String, Int>,
) {
    private val byKey: Map<String, Int> = table.mapKeys { key(it.key) }

    fun score(rating: String?): Int? {
        if (rating.isNullOrBlank()) return null
        val trimmed = rating.trim()
        byKey[key(trimmed)]?.let { return it }
        // "US-PG-13", "DE-16", "gb-15": drop a country prefix and try again.
        CountryPrefix.find(trimmed)?.let { match ->
            byKey[key(trimmed.substring(match.range.last + 1))]?.let { return it }
        }
        val age = Age.find(trimmed)?.value?.toIntOrNull() ?: return null
        return age.takeIf { it in 0..MAX_AGE }
    }

    companion object {
        private val CountryPrefix = Regex("^[A-Za-z]{2}[-: ]")
        private val Age = Regex("\\d{1,2}")
        private val NonAlphanumeric = Regex("[^A-Z0-9]")
        private const val MAX_AGE = 21

        fun from(ratings: List<ParentalRating>): RatingScores = RatingScores(
            buildMap {
                for (rating in ratings) {
                    val score = rating.ratingScore?.score ?: rating.value ?: continue
                    put(rating.name, score)
                }
            },
        )

        private fun key(name: String) = name.uppercase().replace(NonAlphanumeric, "")
    }
}
