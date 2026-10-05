package tv.anstelle.core.livetv

/**
 * Live TV source formats handled by this module (Phase 7):
 * - M3U / M3U8 playlists (streamed line by line),
 * - XMLTV guides (pull parser, windowed to the guide range),
 * - HDHomeRun tuners (UDP discovery, then `discover.json` / `lineup.json`).
 */
object LiveTvSources {
    /** UDP port HDHomeRun devices listen on for discovery requests. */
    const val HDHOMERUN_DISCOVER_PORT = 65001

    /** Guide data older than this is dropped on import. */
    const val GUIDE_KEEP_PAST_MS = 6 * 60 * 60 * 1000L

    /** Guide data further ahead than this is dropped on import. */
    const val GUIDE_KEEP_FUTURE_MS = 7 * 24 * 60 * 60 * 1000L
}
