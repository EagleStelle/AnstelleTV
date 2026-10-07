package tv.anstelle.core.model

import java.util.Locale

/** A language the viewer can choose, by its ISO 639-2/T code. */
data class Language(val code: String, val name: String)

/**
 * Language codes as tracks and settings use them. Jellyfin and Matroska tag streams with ISO
 * 639-2 (bibliographic `fre` or terminologic `fra`), Media3 with ISO 639-1 (`fr`); everything
 * is compared as ISO 639-2/T.
 */
object Languages {
    /** ISO 639-2/B codes that differ from their /T code. */
    private val bibliographic = mapOf(
        "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho",
        "cze" to "ces", "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu",
        "gre" to "ell", "ice" to "isl", "mac" to "mkd", "mao" to "mri", "may" to "msa",
        "per" to "fas", "rum" to "ron", "slo" to "slk", "tib" to "bod", "wel" to "cym",
    )

    /** Codes that say nothing about the language. */
    private val unknown = setOf("und", "mis", "mul", "zxx", "qaa", "unk")

    private val twoLetter: Map<String, String> by lazy {
        Locale.getISOLanguages().associateBy { iso3(it) }.filterKeys { it.isNotEmpty() }
    }

    /** `ja`, `jpn`, `ja-JP` → `jpn`; `fre` → `fra`; null for a blank or undetermined code. */
    fun normalize(code: String?): String? {
        val tag = code?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
        if (tag.isNullOrEmpty() || tag in unknown) return null
        bibliographic[tag]?.let { return it }
        if (tag.length == 2) return iso3(tag).ifEmpty { tag }
        return tag
    }

    /** "Japanese" for `jpn`, in [locale]; the code itself when it isn't a known language. */
    fun displayName(code: String, locale: Locale = Locale.getDefault()): String {
        val normalized = normalize(code) ?: return code
        val two = twoLetter[normalized] ?: return code
        return Locale.forLanguageTag(two).getDisplayLanguage(locale).ifBlank { code }
    }

    /** Every ISO 639-1 language, sorted by its name in [locale]. */
    fun all(locale: Locale = Locale.getDefault()): List<Language> =
        Locale.getISOLanguages()
            .mapNotNull { two ->
                val code = iso3(two).ifEmpty { return@mapNotNull null }
                val name = Locale.forLanguageTag(two).getDisplayLanguage(locale)
                if (name.isBlank() || name == two) null else Language(code, name.replaceFirstChar { it.titlecase(locale) })
            }
            .distinctBy { it.code }
            .sortedBy { it.name.lowercase(locale) }

    /** An ordered language list as settings store it: `jpn,kor,eng`. */
    fun encodeList(codes: List<String>): String = codes.mapNotNull(::normalize).distinct().joinToString(",")

    fun decodeList(raw: String): List<String> = raw.split(',').mapNotNull(::normalize).distinct()

    private fun iso3(two: String): String = runCatching { Locale.forLanguageTag(two).isO3Language }.getOrDefault("")
}
