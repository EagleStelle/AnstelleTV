# AnstelleTV — product and build context

AnstelleTV turns a Jellyfin library into 24/7 retro cable TV on Android phones, tablets, Android TV / Google TV and Fire TV (one APK, touch and D-pad). Channels play programs back to back. There is no backend. All data lives in on-device SQLite (Room). A LAN web UI (SvelteKit) is served by the app itself for setup and in-browser watching.

The app is native: Kotlin + Jetpack Compose (Compose for TV on TV, Material 3 on phones), with ExoPlayer for playback. There is no WebView. The web UI is for browsers on other devices on the LAN.

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | Scaffold: modules, catalog, Room schema, Hilt, placeholder screens with D-pad focus, Ktor server with pairing, SvelteKit shell, baseline profile + benchmark modules | Done |
| 2 | Jellyfin login (Quick Connect, password), multiple servers, incremental sync, reconciliation, rating scores | Done |
| 3 | Scheduler engine: back-to-back airings, playback orders, seasonal lineups, dayparts, resumable state, `nowPlaying` | Done |
| 4 | Channels: 167 templates, matcher, auto-generation, lineup builder, groups, numbers, logos, schedule worker | Done |
| 5 | Playback: player pool, local tuning, surfing, info banner, tracks, off air, watch reporting | Done |
| 6 | Guide grid with preview window and details, group tabs, on-demand movies and shows, Add to channel | Done |
| 7 | Live TV | Next |
| 8–11 | Profiles/themes, dashboard/backup, web UI parity, performance | Planned |

The full plan, with key designs for every phase, lives in `C:\Users\Impulsor\.claude\plans\the-name-of-the-kind-shell.md`.

## Identity

- App name: **AnstelleTV**, from the folder name. It replaces the spec's "RetroCast" placeholder.
- applicationId and root package: `tv.anstelle.app`. Modules use `tv.anstelle.core.*`, `tv.anstelle.feature.*` and `tv.anstelle.webserver`.
- Brand: an A-frame antenna (the letter A) on a retro TV with fading scanlines.
  - Colors: `#0E1116` background, `#4FB3FF` blue, `#FFB547` amber.
  - Assets are regenerated with `python tools/brand/generate_brand.py`.
- Testing uses the owner's private Jellyfin 12.1.0 server with a non-admin account. Its address and contents are deliberately kept out of this repository, and test fixtures recorded from it are anonymized.

## Toolchain

- Gradle 9.8.0 wrapper. The Gradle daemon is pinned to **JDK 17** by `gradle/gradle-daemon-jvm.properties`; foojay downloads it automatically. The launcher may run on any newer JDK (this machine has JDK 25).
- Android SDK: `compileSdk`/`targetSdk` 37, `minSdk` 25 (Fire OS 6). The test modules use minSdk 28.
- Node 22.17+ and pnpm 12.9.1 (pinned in `webui/package.json`). Without pnpm on PATH, the build uses `corepack pnpm`.

### Versions (resolved 2026-10-05)

AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.20, KSP 2.3.12, Compose BOM 2026.09.00, tv-material 1.1.0, material-icons-extended 1.7.8 (last published; pinned outside the BOM), Room 2.8.5, WorkManager 2.12.0, Hilt 2.60.1, Media3 1.11.1, Jellyfin SDK 1.9.1, Ktor 3.6.0, OkHttp 5.5.0, Coil 3.6.3. Web: SvelteKit 3.0.0, Svelte 5.57, Vite 8.3, TypeScript 6.0, unplugin-icons 24.

## Build and run

```sh
./gradlew assembleDebug                  # also builds the web UI via pnpm (skipped with a warning if pnpm is missing)
./gradlew testDebugUnitTest :core:model:test :core:common:test :core:scheduler:test :core:livetv:test
./gradlew lintDebug
./gradlew assembleRelease                # R8 full mode; needs pnpm; signed with the debug key unless keystore.properties exists
./gradlew :app:generateBaselineProfile   # on a connected device (Phase 11: Fire TV Stick)
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest
```

- Emulators: AVDs `atv` (Google TV, API 36) and `phone` (Pixel 8, API 36).
- Web UI on a device: run `adb forward tcp:8765 tcp:8765`, then open `http://localhost:8765`.
- Web UI dev server: `cd webui && pnpm dev`. It proxies `/api` and `/ws` to `ANSTELLE_DEVICE`, default `http://127.0.0.1:8765`.
- Release signing: add `keystore.properties` at the repo root with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. This file is not checked in.

## Modules

| Module | Role |
|---|---|
| `build-logic` | Convention plugins: `anstelle.android.application/library/compose/feature/room/test`, `anstelle.hilt`, `anstelle.jvm.library`, `anstelle.webui` |
| `core/model` | Pure Kotlin models; enums with stable `code` values for storage |
| `core/common` | `TimeSource`, dispatcher qualifiers, `AppLog` ring buffer, `AppInfo` |
| `core/scheduler` | Pure JVM schedule engine: `ScheduleEngine`, `CandidatePool`, `EngineState`, `nowPlaying` |
| `core/livetv` | M3U / XMLTV / HDHomeRun (Phase 7) |
| `core/database` | Room DB with all tables; exported schemas in `core/database/schemas` |
| `core/jellyfin` | The shared `OkHttpClient` and `Jellyfin` SDK instance, server discovery, sign-in, Keystore token store, library source and the DTO → Room mappers |
| `core/player` | `PlayerPool` (two ExoPlayers), `PlayerFactory` (OkHttp data source with the server's auth header, FFmpeg audio fallback), `CapabilityProbe`, `StreamPlanner` (direct play or HLS), `JellyfinStreams` (local stream URLs, side-loaded subtitles), `LanguageTrackSelector`, `PlayerSubtitles` and `SubtitleOverlays` (libass), `FontCache`, `TuneTrace` |
| `core/theme` | `ThemeSpec` JSON model, `AnstelleTheme` (Material 3 + TV), shared focusable components |
| `core/data` | Hilt modules, repositories (`ServerRepository`, `ScheduleRepository`, `ChannelRepository`, profiles, settings), `LibrarySyncer`, channel templates and the lineup matcher (`channels/`), `ScheduleUpdater`, guide cells and program details (`guide/`), on-demand pages (`ondemand/`), `ChannelPlayer`, `OnDemandPlayer` and `PlaybackReporter` (`playback/`), `DatabaseChanges`, `WebServerController`, workers. Template JSON lives in `core/data/src/main/assets/templates` |
| `feature/*` | Guide (grid, preview window, details, tabs), on-demand browsing, the channel and on-demand players, settings (media servers, channels, Add to channel), dashboard (log viewer), profiles (placeholder) |
| `webserver` | Ktor CIO server, pairing, LAN guard, static SPA serving, `/api`, `/ws` |
| `baselineprofile`, `benchmark` | Baseline profile generator and macrobenchmarks (stub journeys) |
| `webui` | SvelteKit SPA |

## Decisions and gotchas (learned in Phase 1)

### Android asset packaging

- **No `_`-prefixed asset folders.** AAPT's default ignore pattern drops directories that start with `_`, so SvelteKit's `appDir` is set to `app` instead of `_app`.
- **No `.gz` assets.** The asset packager unpacks `.gz` files and drops the extension. Pre-gzipped web files therefore use the `.gzip` suffix and are listed in `noCompress`. `asset-index.txt` lists every served path, so the server never walks the APK.

### SvelteKit 3

- Configuration moves from `svelte.config.js` (now unsupported) into `sveltekit({...})` in `vite.config.ts`.
- `$lib` is replaced by `#lib` (package.json `imports`), and imports use explicit extensions (`#lib/api.ts`).
- `tsconfig.json` extends `$app/tsconfig` and must include `$app/types`.

### Web server security

- The server binds only to LAN IPv4 addresses and loopback, and rebinds on network changes.
- A remote-address guard allows only RFC 1918, link-local, ULA and loopback addresses.
- Pairing:
  - The TV shows a 6-digit PIN. It lives 2 minutes and allows 5 attempts.
  - At most 10 pairing starts are allowed per 10 minutes.
  - On success the browser gets a 256-bit token as an HttpOnly, SameSite=Strict cookie. `Authorization: Bearer` is also accepted.
  - Only the token's SHA-256 is stored.
- Cross-origin writes and cross-origin `/ws` are rejected. Unpaired `/ws` gets a 401 before the upgrade.

### Platform

- **Tokens:** `EncryptedSharedPreferences` is deprecated, so Jellyfin tokens are encrypted with an Android Keystore AES-256-GCM key and only the ciphertext sits in the private `jellyfin_tokens` prefs file (`KeystoreTokenStore`). A Keystore or decrypt failure drops the token and the server shows "Signed out", instead of crashing.
- **Android 17 (API 37):** `ACCESS_LOCAL_NETWORK` is declared and requested at runtime.
- **Foreground service:** the web server runs as a `specialUse` foreground service.
- **FFmpeg decoder:** `org.jellyfin.media3:media3-ffmpeg-decoder` latest is `1.9.0+1`, built for Media3 1.9. It works with Media3 1.11.1: `FfmpegDecoderTest` decodes AC3, E-AC3, DTS and TrueHD on a device. If a later Media3 breaks that test, pin Media3 to the extension's version.
- **Database migrations:** debug builds fall back to destructive migration while the schema moves between phases. Pre-release schema versions (listed in `AnstelleDatabase.PRE_RELEASE_VERSIONS`) are also dropped in release builds. Real migrations start with the first public release.
- **Cleartext HTTP** is allowed app-wide, because LAN Jellyfin, HDHomeRun and M3U sources use plain HTTP on arbitrary IPs.
- **Gradle warning:** builds print `Configuration.setVisible` deprecation warnings that come from third-party Gradle plugins. They are harmless until Gradle 11.

### Jellyfin (learned in Phase 2)

- **Adding a server works like the official Jellyfin apps.** There is one "Server address" field that takes an IP address, a host name or a full URL. The SDK's `discovery.getAddressCandidates()` expands the input (with and without HTTPS, ports 8096/8920), and `getRecommendedServers()` checks every candidate; the best-scoring one wins. There is no separate home/remote address (user decision). Adding the same server again under another address updates the existing entry, matched by server Id, so its libraries stay.
- **Sign-in:** Quick Connect (the code is polled every 3 s) or username and password. Signing in as a different user on a known server triggers a full re-fetch.
- **SDK dates are device-local.** The SDK decodes server timestamps into `LocalDateTime` in the device's zone, and sends `LocalDateTime` query parameters with the device's UTC offset. The server handles offset-qualified `minDateLastSaved` correctly; a zone-less value is read differently. Convert only through `SdkTime.kt`. Unit tests run in `Asia/Manila` so UTC/local mix-ups fail on any machine.
- **Grouped views:** a user view that groups libraries (one "TV Shows" view over several TV libraries) is not a library. Real libraries come from `CollectionFolder` views plus `/UserViews/GroupingOptions`, with the collection type from `/Items/{id}`.
- **Incremental sync:** `BaseItemDto` has no `DateLastSaved`, so each library's watermark is the *server* clock (`/GetUtcTime`) at the start of its last sync, re-read with a 5-minute overlap.
- **Series first:** episodes usually have no genres, studios, tags or rating of their own. Series are stored first and episodes inherit from them at sync time; a changed series re-reads its episodes.
- **Reconciliation:** deletions and played-state changes don't touch `DateLastSaved`. An ID-only listing (plus an `IsPlayed` listing) runs at least every 6 hours and on manual sync. It deletes vanished items and sets `inputsHash = 0` on the schedules that air them, so the scheduler regenerates those channels.
- **Fields:** `MediaSources` (source id, container, streams) cost about 8 KB per item, so pages hold 150 items.
- **Ratings:** `/Localization/ParentalRatings` only lists the server's metadata country, while real items carry "12", "NC16", "M18", "PG13" or "6+". `RatingScores` falls back to a punctuation-insensitive match, then to the age written in the rating.
- **Libraries:** movies, shows, music videos, home videos and mixed libraries are synced. Music, books, photos, collections, playlists and live TV are not (music is on hold). Each library can be turned off on the server screen: its items are deleted at once, and turning it back on fetches it in full.
- **Logging:** the SDK logs through kotlin-logging. On Android that maps to logcat; JVM unit tests need `slf4j-nop` on the test runtime classpath.

### Scheduler (learned in Phase 3)

- **Back to back.** Each program airs for exactly its own length and the next one starts the moment it ends, so guide times land on odd minutes (8:00, 8:24, 8:46). One `schedule_slot` row is one whole airing. The first program of a new schedule starts at the time the caller passes in.
- **Pure and deterministic.** `ScheduleEngine.generate(inputs, state, until)` has no clock, I/O or shared state. Candidates are sorted canonically (show, season, episode, id) by `CandidatePool`, so load order never changes a schedule. Specials (season 0) sort after the regular seasons; items without numbers sort last.
- **Resumable state.** `EngineState` holds the next start time, the SplitMix64 state, a per-show "next episode at or after (season, episode, id)" cursor, shuffle decks and the running block. A shuffle deck is stored as its seed and position, not its order, so the blob costs about 50 bytes per played show and per deck. Cursors are positions, not indexes, so new or deleted episodes don't reset a show. Extending 10 days by 20 equals generating 30 at once (tested for every order, with seasons and dayparts, through `encode`/`decode`).
- **Regeneration.** `ScheduleInputs.fingerprint()` is the `inputsHash` (never 0, since 0 marks a schedule stale). After a change, `ScheduleEngine.rewind` moves each show back to its first discarded episode, so regenerating after the current and next airings skips nothing.
- **Playback orders.** SHUFFLE deals every eligible item once per deck and never opens a deck with the item that closed the previous one; weights don't apply. EPISODE_ORDER picks a show by weight (the highest weight among its episodes; a movie is a show of one) and plays its next episode. BLOCKS plays up to `blockSize` consecutive episodes of the picked show.
- **Seasons and dayparts** are evaluated at each program's local start. Month/day ranges may wrap the year. A daypart window may wrap midnight; unrated items never pass its rating limits (same as the SQL kid filter). Shuffle keeps a separate deck per lineup and daypart. `SeasonMode.VISIBLE_IN_RANGE` is only about channel visibility, so it is not an engine input.
- **Off air.** When nothing is eligible (e.g. no item passes a kids daypart), the channel gets an `OFF_AIR` row until the next daypart edge or local midnight, whichever comes first, and the TV shows an off-air screen. A channel with an empty pool is off air one local day at a time.
- **`nowPlaying`** uses the same rule as the SQL lookup: the last slot starting at or before now, if now is before its end. The media position is the time since the slot started. `ScheduleRepository` answers it with one indexed query and stores generated slots and state in one transaction.

### Channels (learned in Phase 4)

- **Templates are data.** Every `*.json` file in `core/data/src/main/assets/templates` is read (`{"templates": [...]}`), so a new template is a new file or entry, never code. A template has an `id` (lowercase, dashes), `name`, `category` (its guide group), `order` and `blockSize`, `minItems`/`minShows` (a movie counts as a show), rule `groups`, and optionally `era` (a year range added to every group), `visible` (MM-DD dates when the channel is in the guide, may wrap the year) and `seasons` (lineups that replace the rules on their dates). A broken file is logged and skipped on the device; the unit tests parse every shipped file.
- **Rules.** A lineup is rule groups (OR) of rules (AND), plus shows or items always included (with a weight) and never included. Text compares ignoring case. `KEYWORD` matches whole words or phrases in the title, the show title and the tags (punctuation ignored), not overviews, which matched too loosely. `OFFICIAL_RATING` with `AT_MOST`/`AT_LEAST`/`BETWEEN` compares the numeric score; unrated items never pass a limit. `DECADE` values are first years (1980). A program's running time only sets the length of its airing; there is no rule on it. A missing value only passes `NOT_ANY_OF`. An item's weight is the highest of the groups and picks that take it; excludes always win.
- **Storage.** Lineups are `channel_rule` rows: each group's rules share a `logicGroup` and carry the group's weight; picks are `INCLUDE`/`EXCLUDE` rows with `logicGroup` -1; lists are `|`-separated. A daypart that names a `ruleLogicGroup` uses that group only as an extra filter inside its window, so the group is left out of the base lineup. Show and item picks store Jellyfin ids, which survive a resync.
- **Matching is in memory.** `MediaItemDao.catalog()` loads every item once with the fields rules test; `LineupMatcher` compiles the rules (lowercased sets, parsed numbers). 50,000 items match in under 50 ms on the JVM (unit-tested).
- **Auto-generation.** `ScheduleWorker` runs after every library sync, at app start and every 12 hours. The first time a profile's library has items, it creates every template channel with enough content (a setting per profile remembers it), numbered from 1 in template order, each in a group named after its category. "Add from templates" on the channel list adds the matching templates a profile doesn't have yet, one by one or all at once. Kid profiles only count items within their rating limit, the same filter their schedules use.
- **Schedules follow the channel.** `ScheduleUpdater` keeps every visible channel 30 days ahead (it tops up once a schedule is 12 hours short). A new channel starts at the current hour. When the inputs hash changes (rules, playback order, matched items, the device zone) or sync marks the schedule stale, the airing on now and the next one stay and the rest is regenerated; an off-air stretch or an airing whose item was deleted is not kept. A schedule that ran out while the app was unused restarts at the current hour instead of filling the past. Hidden channels are not scheduled. Edits in the channel screens reschedule that channel right away.
- **Channel numbers** are unique per profile: giving a channel a taken number swaps the two.
- **Logos** are decoded, scaled to at most 256 px and stored as PNG in `files/logos`, with a new file name per upload so cached copies refresh. Phones pick one with the system picker; any device can upload over the web API (`PUT /api/channels/{id}/logo` with the image bytes, at most 4 MB; `GET` serves it, `DELETE` removes it). `/api/channels` reports a versioned `logoUrl`.
- **Collections** (box sets) aren't part of item pages, so reconciliation lists the box sets, reads each one's items and writes their names into `collections`; episodes get their series' box sets. Incremental sync keeps the stored value.

### Playback (learned in Phase 5)

- **It plays like TV.** Channels follow the schedule's wall clock: no skipping or seeking inside a program. Up/down or the channel keys surf, digits enter a number (it tunes 2 s after the last digit), left or the last-channel key jumps back, OK shows the info banner and then the audio and subtitle choices. Phones swipe up or down to surf and tap for the controls.
- **Tuning makes no server call.** `ChannelPlayer` reads the airing from SQLite, decides direct play or HLS locally (`StreamPlanner` against a `CapabilityProbe` snapshot of `MediaCodecList`, `AudioCapabilities` and the FFmpeg extension) and builds the stream URL from the stored server address. The access token never goes into a URL: the player's data source adds the server's `Authorization` header to every media request, HLS segments included.
- **Direct play** streams the file (`/Videos/{id}/stream?static=true`). **HLS** (`/Videos/{id}/master.m3u8`, H.264 + stereo AAC, video and audio copied when they already fit) is used when the container, video codec, size or bit depth doesn't fit; audio almost never forces it, because the FFmpeg extension decodes AC3, E-AC3, DTS and TrueHD. Jellyfin's playlist covers the whole item, so the player simply starts at the airing's position. A file that turns out not to play directly is retried once as HLS. A transcoded stream carries one audio track and no subtitles of its own (see *Audio and subtitles*).
- **Back to back.** The playlist holds the program on now and the next one; at each hand-off the following program is queued. When the new program is more than 3 s behind the wall clock, it catches up by seeking. A channel that goes off air shows an "OFF AIR" screen until the stretch ends, then tunes itself again.
- **Player pool.** Two ExoPlayers: the one on screen and a standby pre-warmed (about 4 s of buffer) for the next channel in the surfing direction, refreshed every 20 s. Tuning to it swaps the players. The standby keeps audio off, and video too unless a second hardware H.264 decoder is available.
- **Tracks.** Each program starts on the profile's audio and subtitle defaults; picking a track in the player only changes the program on now (see *Audio and subtitles*).
- **Watch reporting** (Settings, Watch history): playback start, progress every 10 s and stop are reported in the background after the first frame. *Report, don't mark watched* (default): a view that didn't run from the first two minutes (or 5%) to 90% is reported, and if Jellyfin marks the item played, the app unmarks it, unless it was played before. *Report everything* leaves Jellyfin's marks. *Off* sends nothing. Views of 30 s or more are also kept as `watch_event` rows for the stats pages.
- **FFmpeg extension:** `media3-ffmpeg-decoder` 1.9.0+1 works with Media3 1.11.1; `FfmpegDecoderTest` decodes AC3, E-AC3, DTS and TrueHD samples on a device (`./gradlew :core:player:connectedDebugAndroidTest`).
- **`LoadControl` in Kotlin:** delegating with `by` skips the interface's Java default methods and ExoPlayer then crashes in `getBackBufferDurationUs`; `PoolLoadControl` forwards every method by hand.
- **OK on key-up:** the player screen acts on OK when the key is released. Acting on key-down moves focus to the controls, which then take the key-up as a click.

### Guide and on demand (learned in Phase 6)

- **The guide** is a preview window playing the channel on now, the selected program's details (episode, time, rating, year, genres, length, overview and badges such as 4K, HDR10, DV, Atmos, DTS, 5.1), group tabs (all channels, favorites, then each group that has channels; the open tab is remembered per profile) and the program grid. It opens at the current half hour and goes 7 days ahead.
- **Grid layout.** A lazy column of channel rows sharing one time state. Each row's cells are a `ProgramStrip`: parallel arrays of starts, ends, item ids and titles, so a row lays out without an object per cell. A row composes only the cells inside the visible hours plus 30 minutes on each side, and a custom layout places them by reading the time state, so scrolling the hours relays rows out without recomposing them. A program that began before the visible hours is clipped to the left edge so its title stays readable. The now line and the dimmed past are drawn over the rows from a clock that ticks twice a minute, without recomposition.
- **Loading.** Cells are read from SQLite per 6-hour window for every channel at once (one query joining `schedule_slot` and `media_item`, range-bounded by the longest slot), kept in an LRU of 8 windows, with the windows on either side fetched ahead. Schedule or library changes clear the cache and the visible windows load again.
- **D-pad.** The grid is one focus target that moves the selection itself: right and left step cell to cell and page the hours when a cell starts within 15 minutes of an edge; up and down keep the time, so the cell airing at that moment is selected; channel or page keys move a screenful. Left at the guide's start and up from the first row hand focus to the drawer and the tabs. Entering the drawer lands on the open destination. The guide keeps its place (hours, selection, scroll) while the player or another page is on top; a new launch opens at the current half hour.
- **Touch.** Drag or fling sideways moves the hours, the list scrolls vertically, tap selects, long-press opens the actions.
- **Selecting and watching** (like TiviMate): OK or a tap on a program airing now shows its channel in the preview window; again, it goes full screen. Holding OK, Menu, or OK on a later program opens its actions: watch the channel (when on now), start from beginning, watch from here (on now, from the airing's current point), and Add to channel. Focus resting on a row for a second pre-warms that channel on the standby player, so the preview and full screen tune from it.
- **Shared playback.** The preview window and the full-screen player show the same `ChannelPlayer`. Each screen acquires it while visible; when the last one releases it, playback stops 1.5 s later unless another screen takes it over, so going full screen and back never retunes.
- **On demand** lists TV shows and movies from SQLite (posters from Jellyfin through Coil and the shared OkHttp client), within the profile's rating limit. A show page starts the show from its first episode or lists its seasons; an item page offers *Watch from here* when Jellyfin has a resume point (read when the page opens), *Start from beginning*, *Add to channel* and, for an episode, the show's other episodes. On-demand playback has its own player (the channel player stops first, since many TV devices run one video decoder): OK pauses, left and right jump 10 s back and 30 s ahead, up opens audio, subtitles and next episode; phones get a seek bar. Episodes continue into the rest of the show. It is reported to Jellyfin like any client (Jellyfin keeps its marks and resume points; *Off* still sends nothing) and kept as an `ON_DEMAND` watch event.
- **Channel views leave resume points.** Progress reports from a channel airing make Jellyfin keep a resume point for that item, so *Watch from here* on demand picks up where the channel was left.
- **Add to channel** adds the show (for an episode) or the movie to a channel's *always include* picks and drops it from its excludes; the schedule changes after the airing on now and the next one. It can also create a new channel of its own.
- **JankStats** runs in debug builds and logs, every 5 seconds of drawing, how many frames missed their deadline (`adb logcat -s Anstelle/Jank`).

### Audio and subtitles

- **Defaults per profile** (Settings, *Audio and subtitles*): an ordered list of audio languages, a subtitle mode (*Off*, *Always on*, *When audio is in another language*) and an ordered list of subtitle languages. They are stored as `playback.audio.<profile>`, `playback.subtitles.mode.<profile>` and `playback.subtitles.<profile>`, the lists as ISO 639-2/T codes (`jpn,kor,eng`). Every program, on a channel or on demand, starts on them; a track picked in the player only changes the program on now.
- **Rules** (`TrackRules`, unit-tested). Audio: the first listed language the item has, else its default track. Subtitles: the first listed language the item has, a full track before a forced one, else none. *When audio is in another language* shows none when the audio is in one of the subtitle languages; audio without a language counts as another language. Languages are compared as ISO 639-2/T, so `ja`, `jpn` and `ja-JP` match, and so do `fre` and `fra`.
- **Direct play.** Media3 picks the audio from the language list; `LanguageTrackSelector` (a `DefaultTrackSelector`) picks the subtitle by the rules once the audio is known. Subtitle files next to the video are loaded alongside the stream.
- **Transcoded streams** carry the audio track asked for (`audioStreamIndex`, picked on the device) and no subtitles, so the subtitle the rules pick, if it is text, is loaded next to the stream from `/Videos/{id}/{source}/Subtitles/{index}/Stream.ass` (or `.srt`). The player only offers that one. Image subtitles (PGS, VobSub) aren't available when transcoding.
- **Sync stores the tracks.** Each item keeps its audio and subtitle streams and its font attachments (`media_item.tracks`, JSON), so picking them needs no server call.
- **ASS and SSA are drawn by libass** (`io.github.peerless2012:ass-media` 0.5.1, the library Jellyfin's Android TV app uses), so styling, positioning, karaoke and animation look as authored. It draws in its own layer over the video (OpenGL, at most 1080p, scaled up on 4K screens). Fonts come from the Matroska file's attachments or, for a side-loaded subtitle, from the item's attachments (`/Videos/{id}/{source}/Attachments/{index}`, kept in `cache/fonts` up to 150 MB; tuning waits at most about 3 s for them).
- **libass holds one item at a time.** It drops its tracks whenever the player moves to another item, so a program or episode with ASS subtitles isn't queued behind the one before it: it starts on its own when that one ends, a short pause instead of a gapless hand-off. libass keeps every font it is given until its player is released, so each font goes in once.
- **Views.** The full-screen player, the on-demand player and the guide's preview put their player's libass layer into the `PlayerView` (`SubtitleOverlays`). When two views show the same player, the one opened last draws.

## Audio and subtitles verification (2026-10-07)

- **Gradle:** `assembleDebug testDebugUnitTest lintDebug assembleRelease` passes, with new JVM tests for language codes, the track rules, track storage and the mapper (`items_subtitles_derived.json`). libass adds 2–3 MB of native code per ABI (about 11 MB in the universal APK).
- **Google TV AVD, real server**, after a full sync of a library where most episodes carry ASS with embedded fonts and many have more than one audio language. Defaults: audio Japanese, Korean, English; subtitles in English when audio is in another language.
  - The settings screens work with the D-pad: numbered lists, the picker and its filter, the mode tile.
  - Direct play: Japanese audio wins over English, and the English ASS is drawn by libass with the file's fonts, full screen and in the guide's preview.
  - A Korean and English item without Japanese (transcoded): Korean audio is requested by `audioStreamIndex`, and the full English subtitle is side-loaded instead of the forced one; the player offers just that one.
  - A transcoded ASS episode on demand: the side-loaded ASS and its 18 fonts (cached) are drawn as authored. The next episode, also ASS, isn't queued and starts on its own with its subtitles.
- Not checked on a device: the settings screens on the phone, and a channel moving from a program to an unqueued ASS program at its scheduled end.

## Phase 6 verification (2026-10-07)

- **Gradle:** `assembleDebug testDebugUnitTest lintDebug assembleRelease` passes, plus the JVM module tests: 133 unit tests, 0 failures (guide strips, window reads and merges, badges, program text, grid navigation and paging, on-demand reporting rules). Lint shows no issues in any library module; the app module keeps its two earlier warnings.
- **Google TV AVD, real server:** the preview plays the last channel; D-pad moves cell to cell, pages the hours, scrolls rows and hands focus to the drawer (landing on Guide) and the tabs; details and badges follow the selection; OK previews, OK again goes full screen (from the pre-warmed standby), and Back returns to the guide still playing, with the selection kept. Program actions, on-demand show and item pages, *Watch from here* from Jellyfin's resume point, pause and seeking, the next episode queued, the resume point updated after stopping, and Add to channel all work.
- **Phone AVD** (a copy of the TV's database, no sign-in): tap to select and preview, fling sideways, vertical scrolling, on-demand pages.
- **100 channels** (58 copies added to the TV AVD's database for the test, scheduled 30 days ahead in 11 s), release build, `dumpsys gfxinfo`:
  - Phone AVD, flinging up, down and sideways: 2.9% of 961 frames missed their deadline, 14 with a slow UI thread.
  - Google TV AVD, D-pad down 60 rows, right 12, back up and left with the preview playing: 8.4% missed. UI-thread work stayed within budget (p99 15.5 ms, 1 of 120 frames over 16 ms); the misses come from render-thread time, since both AVDs draw with the software GPU and decode the preview in software. Clipping every cell to a rounded path cost render time and was replaced with shaped backgrounds. The real numbers come from the Fire TV Stick in Phase 11.

## Phase 5 verification (2026-10-06)

- **Gradle:** `assembleDebug testDebugUnitTest lintDebug assembleRelease` passes, plus the JVM module tests: 120 unit tests, 0 failures (stream decisions, watch rules and the earlier suites). Lint shows no issues in any library module; the app module keeps its two earlier warnings.
- **FFmpeg smoke test** on the Google TV AVD: the extension loads into `DefaultRenderersFactory` and decodes all four samples.
- **Google TV AVD, real server** (debug build):
  - Library sync is now about 20 s for 3.7k items (it was 77 s while media segments were fetched).
  - Tune to first frame: 1.0–1.8 s cold with direct play, 1.0 s when swapping from the standby, 9.2 s for HLS (a 10-bit HEVC episode the emulator can't decode; the server starts transcoding mid-file). The `Tune` trace section and log line report each one.
  - The info banner, number entry (two digits), up/down surfing, the controls panel and the audio picker work with the D-pad.
  - Gapless hand-off: the next program started 2.0 s behind the schedule (the tune latency), under the 3 s threshold, and the banner moved on to it.
  - Report, don't mark watched: four programs joined near their end were marked played by Jellyfin and unmarked by the app; a reconcile afterwards still counted the same 918 played items.

## Phase 4 verification (2026-10-06)

- **Gradle:** `assembleDebug testDebugUnitTest lintDebug assembleRelease` passes, plus the JVM module tests: 117 unit tests, 0 failures. Lint shows no issues in any library module; the app module keeps its two earlier warnings.
- **Unit tests:**
  - Matcher: every field and operator, case-insensitive lists, whole-word keywords, rating text and scores, missing values, AND within a group and OR across groups, weights, includes and excludes; 50,000 items match in under 50 ms.
  - Rule rows round-trip, including picks, weights and groups kept for dayparts.
  - Every shipped template parses and is valid (167, unique ids). A new template file with no code change becomes a planned channel; broken files name the file and template and don't stop the others.
  - Planner thresholds (`minItems`, `minShows`), existing templates skipped, kid rating limits.
  - Inputs: base pool, rating limit, override seasons as seasonal pools, daypart rule groups only inside their window, fingerprints.
  - `ScheduleUpdater`: a new schedule starts at the current hour and reaches 30 days; unchanged inputs are left alone; extension keeps every existing row and stays contiguous; a new lineup keeps the airing on now and the next one; an airing whose item was deleted is replaced from its start; hidden channels get nothing; a schedule that ran out restarts at the current hour.
  - Box-set membership changes (own and series box sets, removals), box-set reading through the SDK, logo upload routes (401, 404, 400 for non-images, 413, versioned caching).
- **Google TV AVD, real server:**
  - After a full sync, auto-generation created 42 template channels (6 of them seasonal) in 8 groups and scheduled them in 4.7 s (debug build); 73k rows, all contiguous, every program exactly its item's length, 30 days ahead, starting at the current hour. App restarts and later syncs report every schedule up to date.
  - Channel list, detail (now and next airings at back-to-back times), lineup editor with live match counts, a new channel built with the D-pad (genre filter, multi-pick, a second rule), playback order, favorite, group, rename and renumber (swapping two channels), and delete all work; edits reschedule the channel at once.
  - A 512 px PNG uploaded over `PUT /api/channels/1/logo` was stored as 256 px, served with a versioned immutable URL and shown on the channel page.

## Phase 3 verification (2026-10-05)

- **Gradle:** `assembleDebug testDebugUnitTest lintDebug assembleRelease` passes, plus the JVM module tests: 84 unit tests, 0 failures. Lint shows no issues in any library module; the app module keeps its two earlier warnings.
- **Scheduler tests (JVM):**
  - Determinism: the same inputs give identical slots and state bytes; another seed gives another schedule; input order doesn't matter.
  - Splitting: 30 days at once equals 10 days, then `encode`/`decode` of the state, then 20 more, for all three orders, with a seasonal lineup and a daypart.
  - Back to back: in Manila, Kathmandu (+05:45) and New York across the November DST change, rows are contiguous and every program row lasts exactly as long as its item.
  - `nowPlaying` at a program start, inside it, exactly where the next episode starts, off air, and just outside the schedule.
  - Orders: shuffle decks hold every item once with no repeat across decks; episode order plays each show in order and wraps; block runs have the block length; show weights 1:2:5 air within 2 percentage points of 1/8, 2/8 and 5/8 over about 5,700 programs.
  - A Dec 20 – Jan 5 lineup switches by local date across the new year; dayparts (one wrapping midnight) hold across both US DST changes; an empty window goes off air and returns exactly at the window's end; an empty pool is off air until each local midnight.
  - Performance: 100 channels × 30 days (pool building included, about 93k slots) take about 0.2 s on the JVM; the limit is 5 s.
- **`ScheduleRepository`:** saving a generated schedule and resolving `nowPlaying` through the DAO gives the same answer as the engine; the stored state resumes; an unreadable state counts as missing.
- No device check this phase: nothing on screen uses the scheduler until channels exist (Phase 4).

## Phase 2 verification (2026-10-05)

- **Gradle:** `assembleDebug testDebugUnitTest lintDebug assembleRelease` passes, plus the JVM module tests: 58 unit tests, 0 failures. The new tests run the mapper and the real SDK request/decoding path (through a fixture-backed `ApiClient`) against anonymized JSON recorded from a Jellyfin 12.1.0 server. `items_movie_page_derived.json` is built from a recorded episode because the test account has no movie library. Lint shows no issues in any library module; the app module keeps its two earlier warnings (`DataExtractionRules`, `IconMissingDensityFolder`).
- **Google TV AVD:**
  - Adding a server by bare domain resolved to its `https://` URL; adding it by bare IP resolved to `http://<ip>:8096`.
  - Sign-in worked both times. Adding the server again by IP kept a single entry.
  - The token survived an app restart (Keystore decrypt).
  - First sync: show and episode counts equal `/Items/Counts` for the user exactly. About 3.7k items took about 77 s on the emulator.
  - Second sync: `incremental since …, 0 items fetched` for both libraries, then the reconcile pass; 2.9 s in total.

## Phase 1 verification (2026-10-05)

- **Gradle:** `assembleDebug testDebugUnitTest lintDebug assembleRelease` passes, along with the JVM module tests and both test modules. That is 38 unit tests with 0 failures, and lint reports no issues in any library module.
- **Web UI:** `pnpm check` reports 0 errors; `pnpm build` passes.
- **Google TV AVD:**
  - Launch goes straight to the guide when there is one profile.
  - The first tile has focus, and D-pad works through the guide, player (channel up/down), drawer, settings and profiles.
  - The PIN overlay appears with Cancel focused.
- **Phone AVD:** the notification permission prompt, bottom navigation, Settings with the LAN URLs, and light status bar icons all work.
- **Web server** (debug and R8 release builds):
  - `/api/health` is open.
  - `/api/*` and `/ws` return 401 when unpaired; cross-origin POST returns 403.
  - A wrong PIN is rejected; the right PIN sets an HttpOnly cookie, after which `/api/session`, `/api/channels`, `/api/profiles` and `/ws` (101) work.
  - The SPA is served gzipped with immutable caching for hashed assets.
- **Cold start on AVDs:** about 1.8 s for debug (unoptimized) and 0.69 s for release on the phone AVD. The real target is measured on a Fire TV Stick in Phase 11.

## Known follow-ups

- On a focused tile, the muted subtitle color has low contrast. This will be tuned with the theme work in Phase 8.
- Movie sync is covered by the derived fixture only. Give the test account a movie library to check it against a real server.
- `ScheduleEngine.rewind` rewinds show cursors but not shuffle decks: items in discarded slots of a shuffle channel wait for the next deck.
- Box-set membership is only checked against hand-made fixtures: the test account's one box set lists no items it can see.
- On TV, moving D-pad focus onto a text field opens the on-screen keyboard right away, so scrolling past name and number fields means closing it first. Look at showing the keyboard only on OK.
- Choosing a logo on the device uses the system file picker, which many TV devices lack (not tried on the TV AVD); a browser can upload one with `PUT /api/channels/{id}/logo`. The web page for it comes with web UI parity (Phase 10).
- Template channels are created once per profile; templates added in an app update only appear under "Add from templates".
- HLS takes about 9 s to start on the test server, and a channel tuned that way runs behind until its next program. Measure on the Fire TV Stick; the standby could pre-warm HLS channels earlier.
- The off-air screen and the phone gestures weren't exercised on a device: no channel has dayparts yet, and only the TV AVD was used.
- Music channels (and their now-playing screen) wait until music libraries are back.
- Guide frame timing on the AVDs is limited by their software GPU; measure the 100-channel scroll on the Fire TV Stick (Phase 11).
- The Add to channel page sits outside the tabs, so no drawer or bar destination is highlighted while it is open.
- On TV, the first focus on the show and item pages scrolls their header partly out of view.
- On-demand lists cover movies and shows; music videos only air on channels.
