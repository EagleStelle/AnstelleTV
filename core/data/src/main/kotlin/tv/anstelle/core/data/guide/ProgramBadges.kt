package tv.anstelle.core.data.guide

import tv.anstelle.core.model.HdrType

/** The picture and sound badges a program's details show: "4K", "HDR10", "DV", "Atmos", "5.1"… */
object ProgramBadges {
    /** Audio formats worth a badge; plain stereo codecs (AAC, MP3…) get none. */
    private val NotableAudio = setOf("Atmos", "DTS:X", "DTS-HD", "DTS", "TrueHD", "DD+", "DD")

    fun of(hdrType: HdrType, width: Int?, height: Int?, audioFormat: String?, audioChannels: Int?): List<String> = buildList {
        val w = width ?: 0
        val h = height ?: 0
        when {
            w >= 3200 || h >= 2000 -> add("4K")
        }
        when (hdrType) {
            HdrType.SDR -> Unit
            HdrType.HDR10 -> add("HDR10")
            HdrType.HDR10_PLUS -> add("HDR10+")
            HdrType.HLG -> add("HLG")
            HdrType.DOLBY_VISION -> add("DV")
        }
        if (audioFormat in NotableAudio) add(audioFormat!!)
        when (audioChannels) {
            6 -> add("5.1")
            8 -> add("7.1")
        }
    }
}
