# AnstelleTV — product and build context

<!-- impeccable:product-schema 1 -->

AnstelleTV turns a Jellyfin library into 24/7 retro cable TV on Android phones, tablets, Android TV / Google TV and Fire TV (one APK, touch and D-pad). Channels play programs back to back. There is no backend. All data lives in on-device SQLite (Room). A LAN web UI (SvelteKit) is served by the app itself for setup and in-browser watching.

The app is native: Kotlin + Jetpack Compose (Compose for TV on TV, Material 3 on phones), with ExoPlayer for playback. There is no WebView. The web UI is for browsers on other devices on the LAN.

## Platform

android

This one record covers both surfaces: the native Android app (phones, tablets, TV) and the SvelteKit web UI it serves on the LAN.

## Users

- **Now:** the owner and their household, watching their own Jellyfin library.
- **Later:** a public release for Jellyfin owners in general. Design for someone who installs the app without help, even though no one outside the household uses it yet.
- **The job:** put the library on like cable TV. Turn it on, surf, land on something, and stop choosing what to watch. On demand is there when they do want to choose.

## Product Purpose

AnstelleTV makes a personal media library feel like broadcast TV again: always-on channels with a schedule, a guide and channel surfing, built from what the library already holds. Success means a new user gets working channels after signing in, with no setup.

## Positioning

- **No server to run.** Everything runs on the TV or phone. Tunarr and ErsatzTV need a separate server plus an IPTV client.
- **Channels build themselves** from the library's own metadata (genres, tags, decades, studios), with no templates or hand curation. Edits are optional and always win.
- **Plays like real TV:** a wall-clock schedule, surfing with the remote, a real guide grid. Seeking in a channel's program moves that channel's schedule with it, so the guide always shows what is on screen.
- **No monetization:** no ads, subscriptions or paid features, unlike QuasiTV and NostalgiaTV.

## Operating Context

- **TV:** Android TV, Google TV and Fire TV, used from the couch with a D-pad remote and no touch. Fire TV has no Google services, so nothing can depend on them (fonts are bundled, not downloaded).
- **Phone and tablet:** the same APK with touch: swipe to surf, tap for controls.
- **Browser on the LAN:** a laptop or phone opens the web UI the app serves. It pairs with a 6-digit PIN shown on the TV, then configures the app or watches in the browser.
- **Jellyfin:** one or more servers, on the LAN or remote, signed in with Quick Connect or a password. Jellyfin UX follows the official Jellyfin apps where one exists.
- **Profiles:** several per device, each a Jellyfin user, with roles (owner, admins, members), PINs and maturity ratings. Channels are shared by every profile (see *Profiles, roles and parental controls*).

## Capabilities and Constraints

The sections below record what is built and how. Product-level constraints:

- Nothing plays filler: no commercials, bumpers, station IDs or breaks. Programs run back to back.
- Phase 8 ships 16 user-selectable themes (user decision: at least 16), Rose the default. The UI has to stay themeable.
- The web UI reaches parity with the app in Phase 10.
- Music libraries and music channels are on hold.
- Live TV (Phase 7) is skipped for now.

## Brand Commitments

- The name **AnstelleTV** stays.
- The brand gets a new palette tied to Stelle from Honkai: Star Rail: the black-and-white contrast of her outfit. It uses no yellow, grey, blue, violet, orange or red, and no stock Tailwind or shadcn values. The reference is never named anywhere in the UI.
- The mark is a channel bug: an ink "A" on a rose tile, its crossbar running on past the right leg. The palette is ink, bone and rose; `DESIGN.md` holds the values and the rest of the visual system.
- The branding stays original: no artwork, logos or text from other products, the game included.

## Evidence on Hand

- The owner's Jellyfin library is the only real content. It is private: its address, titles and contents never appear in the repo, screenshots meant for others, or store copy.
- Verification numbers come from emulators (see the verification sections below). Real-device numbers wait for the Fire TV Stick in Phase 11.
- There are no outside users, testimonials, reviews or store listing yet. Don't invent any.

## Product Principles

1. **It plays like TV.** The clock decides what is on. Surfing and the guide are the main way in; on demand is the side door.
2. **Zero setup.** A new user signs in and gets channels.
3. **Everything on the device.** No backend, no account, nothing beyond Jellyfin and the LAN.
4. **Nothing in the way.** No ads, no filler, no upsell.
5. **Remote and touch alike.** Every screen works with a D-pad remote, and with touch on phones and tablets.

## Accessibility & Inclusion

- The web UI meets WCAG 2.2 AA, including contrast and full keyboard use.

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | Scaffold: modules, catalog, Room schema, Hilt, placeholder screens with D-pad focus, Ktor server with pairing, SvelteKit shell, baseline profile + benchmark modules | Done |
| 2 | Jellyfin login (Quick Connect, password), multiple servers, incremental sync, reconciliation, rating scores | Done |
| 3 | Scheduler engine: back-to-back airings, playback orders, seasonal lineups, dayparts, resumable state, `nowPlaying` | Done |
| 4 | Channels: channels made from library metadata, matcher, lineup builder, groups, numbers, logos, schedule worker | Done |
| 5 | Playback: player pool, local tuning, surfing, info banner, tracks, off air, watch reporting | Done |
| 6 | Guide grid with preview window and details, group tabs, on-demand movies and shows, Add to channel | Done |
| 7 | Live TV | Skipped for now |
| — | Android UI redesign: new brand, bundled type, flat on-screen-display look on every screen, settings menu, tablet rail | Done |
| 8 | Profiles as Jellyfin users with roles, PINs and maturity ratings; shared channels with age-restricted blackouts; 16 themes, the theme designer, import and export | Done |
| 9–11 | Dashboard/backup, web UI parity, performance | Planned |

The full plan, with key designs for every phase, lives in `C:\Users\Impulsor\.claude\plans\the-name-of-the-kind-shell.md`.

## Identity

- App name: **AnstelleTV**, from the folder name. It replaces the spec's "RetroCast" placeholder.
- applicationId and root package: `tv.anstelle.app`. Modules use `tv.anstelle.core.*`, `tv.anstelle.feature.*` and `tv.anstelle.webserver`.
- Brand: see *Brand Commitments* and `DESIGN.md`. The launcher icon and TV banner are regenerated with `python tools/brand/generate_brand.py` (needs Pillow); the in-app mark (`AnstelleMark`) draws the same geometry.
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
| `core/data` | Hilt modules, repositories (`ServerRepository`, `ScheduleRepository`, `ChannelRepository`, profiles, settings), `LibrarySyncer`, library channels and the lineup matcher (`channels/`), `ScheduleUpdater`, guide cells and program details (`guide/`), on-demand pages (`ondemand/`), `ChannelPlayer`, `OnDemandPlayer` and `PlaybackReporter` (`playback/`), `DatabaseChanges`, `WebServerController`, workers. The channel word list is `core/data/src/main/assets/channel-words.json` |
| `feature/*` | Guide (grid, preview window, details, tabs), On demand (channel rows and banner, channel pages), the channel and on-demand players, settings (media servers, channels, Add to channel), dashboard (log viewer), profiles (Who's watching, the profile page), themes and the theme designer (`settings/appearance`), the admin gate |
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
- **Reconciliation:** deletions don't touch `DateLastSaved`. An ID-only listing runs at least every 6 hours and on manual sync. It deletes vanished items and sets `inputsHash = 0` on the schedules that air them, so the scheduler regenerates those channels.
- **Fields:** `MediaSources` (source id, container, streams) cost about 8 KB per item, so pages hold 150 items.
- **Artwork:** item pages ask for one image of each kind: the poster (`Primary`), the first backdrop and the title logo. Their tags are stored (`primaryImageTag`, `backdropImageTag`, `logoImageTag`) and the URLs are built locally with the tag (`Artwork`, `jellyfinImageUrl`), so no page asks the server whether art exists. Episodes rarely have a backdrop or logo of their own; they use their series'.
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

- **Rules.** A lineup is rule groups (OR) of rules (AND), plus shows or items always included (with a weight) and never included. Text compares ignoring case. `KEYWORD` matches whole words or phrases in the title, the show title and the tags (punctuation ignored), not overviews, which matched too loosely. `OFFICIAL_RATING` with `AT_MOST`/`AT_LEAST`/`BETWEEN` compares the numeric score; unrated items never pass a limit. `DECADE` values are first years (1980). `KIND` compares a show's kind (anime, cartoons, `unclear` or `none`; see *Library channels*). A program's running time only sets the length of its airing; there is no rule on it. A missing value only passes `NOT_ANY_OF`. An item's weight is the highest of the groups and picks that take it; excludes always win.
- **Storage.** Lineups are `channel_rule` rows: each group's rules share a `logicGroup` and carry the group's weight; picks are `INCLUDE`/`EXCLUDE` rows with `logicGroup` -1; lists are `|`-separated. A daypart that names a `ruleLogicGroup` uses that group only as an extra filter inside its window, so the group is left out of the base lineup. Show and item picks store Jellyfin ids, which survive a resync.
- **Matching is in memory.** `MediaItemDao.catalog()` loads every item once with the fields rules test; `LineupMatcher` compiles the rules (lowercased sets, parsed numbers). 50,000 items match in under 50 ms on the JVM (unit-tested).
- **Schedules follow the channel.** `ScheduleUpdater` keeps every visible channel 30 days ahead (it tops up once a schedule is 12 hours short). A new channel starts at the current hour. When the inputs hash changes (rules, playback order, matched items, the device zone) or sync marks the schedule stale, the airing on now and the next one stay and the rest is regenerated; an off-air stretch or an airing whose item was deleted is not kept. A schedule that ran out while the app was unused restarts at the current hour instead of filling the past. Hidden channels are not scheduled. Edits in the channel screens reschedule that channel right away.
- **Channel numbers** are 0–9999 and unique: giving a channel a taken number swaps the two. The player's route uses -1 for "no channel asked for", since 0 is a real number.
- **Logos** are decoded, scaled to at most 256 px and stored as PNG in `files/logos`, with a new file name per upload so cached copies refresh. Phones pick one with the system picker; any device can upload over the web API (`PUT /api/channels/{id}/logo` with the image bytes, at most 4 MB; `GET` serves it, `DELETE` removes it). `/api/channels` reports a versioned `logoUrl`.
- **Collections** (box sets) aren't part of item pages, so reconciliation lists the box sets, reads each one's items and writes their names into `collections`; episodes get their series' box sets. Incremental sync keeps the stored value.

### Library channels

- **Made from metadata.** `ChannelGenerator` decides which channels exist and what they air from the library's own genres, tags, years and studios (the values Jellyfin's metadata providers fill in, such as TMDB and TVDB). Nothing lists titles, studios or networks. Four segments, each a guide group, in this order:
  - **Productions:** each Studios value by its own name, airing everything it made (series and movies, anime, cartoons and the rest). For series that includes the network ("Netflix", "NBC").
  - **Decades:** TV, movies and each media type's series and movies by decade ("90s TV", "80s Movies", "2010s Anime", "2010s Anime Movies"). An episode counts in the decade it aired.
  - **Genres:** each genre, each pair of genres and each genre with a media type, as a series channel and a movie channel ("Fright Night" and "Horror Picture Show" for Horror, "Case Files" and "Crime Sagas" for Crime and Drama, "Anime After Dark" and "Anime Midnight Show" for Anime and Horror).
  - **Themes:** each tag ("Isekai"), airing everything with it.
- **What may mix.** A genre or decade channel airs series or movies, never both, and one kind of show: anime, cartoons, or neither. Themes and productions air everything with their tag or studio. Channels someone builds may mix anything; the lineup builder offers the kind as one more optional rule.
- **Anime or cartoon** is decided per show by `MediaKinds` in the catalog, from the genres, tags and audio languages of all its episodes together. Anime: its genres or tags say anime (TMDB marks anime with the `anime` keyword) and its audio has Japanese, or no audio language is known. Cartoons: its genres say animation, nothing says anime, and no episode or movie plays Japanese first. Animation whose signals disagree (an anime genre with only English audio, an untagged film that plays Japanese, a western cartoon whose file defaults to a Japanese dub) is *unclear* and airs on no genre or decade channel. The word list says which language confirms a type (`"language": "jpn"` for Anime). A library folder's name decides nothing: one can hold both.
- **Order and numbers.** Segments in that order, names alphabetical inside each (case and accents ignored), decades by year and then TV, movies and each media type's series and movies. Each segment counts up from its own start: Productions 1000, Decades 2000, Genres 3000, Themes 4000 by default, changed under Settings, Channels, *Channel numbers* (any start from 0 to 9999; changing one renumbers that segment at once). A channel's number skips numbers other channels hold, so numbers stay unique even when starts overlap.
- **Genre channel names** come from the word list: `names` for series channels and `movieNames` for movie channels, retro TV-block names of at most three words for every genre TMDB and TVDB use (35, after merging spellings), each of them with Anime and with Cartoons, and every pair of them (700 names each; a unit test checks every combination has both, none of the 1,400 repeats and none is longer than three words). A genre outside that list (a custom one added in Jellyfin) gets a made-up name, with "Movies" after it for a movie channel.
- **Made-up names.** A genre has one modifier everywhere: its adjective when it has one ("Comedic Drama", "Comedic Anime", "Comedic Cartoons"), else itself ("Sports Anime", "Drama Cartoons"). In a pair, a head genre (Drama, Comedy, Thriller, Adventure…) ends the name ("Action Comedy", "Action Adventure", "Crime Drama"); between two heads or two others, the genre with an adjective modifies ("Comedic Drama", "Romantic Comedy"), else the one with fewer shows ("Mystery Thriller"). A genre value with `&` ("Sci-Fi & Fantasy") is two genres, so no name joins genres with "&". All-lowercase tags are title-cased ("Based on Manga"). Decades 1920–1990 are written short ("90s"), others in full ("2010s").
- **Word list.** `core/data/src/main/assets/channel-words.json` holds only language: the media types with the genres, tags and audio language that mark them, spellings of one genre (`Sci-Fi` = `Science Fiction`), head genres, adjectives, tags to ignore (TMDB's credit-stinger, sequel/remake, time-setting and mood keywords such as "amused" or "admiring"), title-case minor words and the genre channel names. It names no title, studio or network and decides no lineup. An unreadable file is logged and channels are made without it.
- **Thresholds** (`ChannelGenerator`): a channel needs 3 shows (a movie counts as one) and 12 hours of airtime; a theme needs 8 shows. Tags on more than half the library's shows, tags that equal a genre and media-type tags are skipped. A channel whose shows are 85% the same as a channel kept before it (genres first, then themes, decades and productions) is dropped, and so is a pair or typed genre that is 85% its parent genre or media type. A studio with 90% of its shows inside a bigger kept studio is dropped too: anime lists every regional station that carried a show, and those stations mostly repeat a bigger network's lineup.
- **Lineups** are ordinary rules, so the lineup editor, the matcher and the scheduler treat library channels like any other: every library spelling of the genre, tag or studio (`ANY_OF`), a `KIND` rule (anime, cartoons or `none`) and an exact `MEDIA_TYPE` rule for genre and decade channels, a `DECADE` rule. A unit test checks that every lineup matches exactly the items the generator counted, and that genre and decade channels keep to one kind and one media type. Movies-only channels shuffle; the rest play in episode order.
- **Following the library.** `ScheduleWorker` runs `ChannelRepository.syncGenerated()` after every library sync, at app start and every 12 hours, once for the whole library: channels are every profile's. It adds the channels the library newly has content for, updates the name, lineup and group of managed channels, removes a managed channel that is no longer made unless it would still be made at three quarters of the thresholds (so channels near the limit don't flicker; a channel that stops qualifying for another reason goes at once), and renumbers managed channels from their segment's start around the numbers other channels hold. `GeneratedChannelSync` makes these decisions and is unit-tested.
- **Edits and deletions win.** A channel is `isManaged` until someone renames it, changes its lineup (Add to channel included), playback order, number or group; from then on it keeps its changes and is never updated, removed or renumbered. Favorites, hiding and logos don't count as edits. Deleting a library channel stores its key in `dismissed_channel`, so it isn't made again; *Removed channels* on the channel list brings it back while the library still has content for it.
- **Your segments and channels.** *Add a segment* on the *Channel numbers* page makes a named group with a start of its own: the next thousand after every start and channel number (5000 with the defaults; nothing is made until someone adds one). A channel put in it with its Group setting takes its first free number, and changing the segment's start renumbers its channels in their order. Segments are guide groups like any other (`channel_group.numberStart`), so the Groups page renames, moves and deletes them too. A channel someone builds outside a segment takes the first free number after every segment's start (5000 with the defaults), so it never lands inside one.

### Playback (learned in Phase 5)

- **It plays like TV.** Channels follow the schedule's wall clock. Up/down or the channel keys surf, digits enter a number (it tunes 2 s after the last digit), the banner snaps in on every tune and seek with its keys showing (audio and subtitles on the left, *Restart* on the right), focus left on the picture so surfing and seeking keep working, and OK, Info or Menu moves focus into the keys. Phones swipe up or down to surf (no channel keys), tap for the banner and tap or drag its ladder to seek. Phones and tablets turn the player sideways and hide the system bars while it is open.
- **Seeking moves the schedule** (user decision). Left and right (or rewind and fast-forward) seek 10 s back and 30 s ahead, like any video, and *Restart* seeks to 0. The airing then starts where the new position puts it, and everything after it on that channel moves by the same amount, engine state included (`ScheduleRepository.shift`, one transaction). Airings it now overlaps are cut short; when it moves later, the part already shown stays in the guide's history as an airing of its own. Seeks stop 3 s short of the program's end and are written in order. Off-air stretches move with the rest, so a daypart edge moves too.
- **Stream facts.** Both players' banners carry a technical line: the server's name first, then DIRECT PLAY or TRANSCODING, then the file's quality ("4K Dolby Vision", "1080p"), video ("HEVC 10-bit") and audio ("TrueHD Atmos 7.1") as outlined badges (`StreamFacts`). They describe the file as synced; a transcoded stream plays the server's H.264 and AAC output.
- **Tuning makes no server call.** `ChannelPlayer` reads the airing from SQLite, decides direct play or HLS locally (`StreamPlanner` against a `CapabilityProbe` snapshot of `MediaCodecList`, `AudioCapabilities` and the FFmpeg extension) and builds the stream URL from the stored server address. The access token never goes into a URL: the player's data source adds the server's `Authorization` header to every media request, HLS segments included.
- **Direct play** streams the file (`/Videos/{id}/stream?static=true`). **HLS** (`/Videos/{id}/master.m3u8`, H.264 + stereo AAC, video and audio copied when they already fit) is used when the container, video codec, size or bit depth doesn't fit; audio almost never forces it, because the FFmpeg extension decodes AC3, E-AC3, DTS and TrueHD. Jellyfin's playlist covers the whole item, so the player simply starts at the airing's position. A file that turns out not to play directly is retried once as HLS. A transcoded stream carries one audio track and no subtitles of its own (see *Audio and subtitles*).
- **Back to back.** The playlist holds the program on now and the next one; at each hand-off the following program is queued. When the new program is more than 3 s behind the wall clock, it catches up by seeking. A channel that goes off air shows an "OFF AIR" screen until the stretch ends, then tunes itself again.
- **Player pool.** Two ExoPlayers: the one on screen and a standby pre-warmed (about 4 s of buffer) for the next channel in the surfing direction, refreshed every 20 s. Tuning to it swaps the players. The standby keeps audio off, and video too unless a second hardware H.264 decoder is available.
- **Tracks.** Each program starts on the profile's audio and subtitle defaults; picking a track in the player only changes the program on now (see *Audio and subtitles*).
- **Jellyfin watch history** (Settings, *Live TV* and *On demand*, per profile, user decision): Jellyfin keeps one watched mark and one resume point per item for all its apps, so Live TV and On demand each choose what they leave there (`WatchSync`): *Watched + resume*, *Watched only*, *Resume only* or *Off*. Live TV defaults to *Watched only*, so channel airings never move the resume point On demand left; On demand defaults to *Watched + resume*, like Jellyfin's own apps. TV steps the choices with left and right, the info panel listing all four with the one in use checked; touch picks from a list.
  - **Resume** reports playback start, progress every 10 s and stop in the background after the first frame. Settings without it send no reports.
  - **Watched on a channel** means watched through: the channel player samples its position every second while playing (`WatchCoverage`), and a program counts once 90% of its seconds have played on screen (the full-screen player or the guide's preview). Joining late, seeking past a part or leaving early doesn't count; *Start over* fills in what was missed. On demand, stopping past 90% counts, as in Jellyfin's apps.
  - **Marks** (`WatchRules`, unit-tested): a watched view is marked through `/UserPlayedItems` (one more play, resume point cleared), unless Jellyfin already marked it. Jellyfin also moves the mark on its own while it gets reports (on past 90%, and it can clear it when a rewatch starts), so when a setting keeps resume points but the view doesn't earn a mark, the item's mark from before playback (read when it starts) is put back with a played-only user-data update that leaves the resume point alone. *Watched + resume* on demand leaves Jellyfin's marks as they are.
  - Views of 30 s or more are also kept as `watch_event` rows for the stats pages, whatever the settings.
- **FFmpeg extension:** `media3-ffmpeg-decoder` 1.9.0+1 works with Media3 1.11.1; `FfmpegDecoderTest` decodes AC3, E-AC3, DTS and TrueHD samples on a device (`./gradlew :core:player:connectedDebugAndroidTest`).
- **`LoadControl` in Kotlin:** delegating with `by` skips the interface's Java default methods and ExoPlayer then crashes in `getBackBufferDurationUs`; `PoolLoadControl` forwards every method by hand.
- **OK on key-up:** the player screen acts on OK when the key is released. Acting on key-down moves focus to the controls, which then take the key-up as a click.

### Guide and on demand (learned in Phase 6)

- **The guide** is a preview window playing the channel on now, the selected program's details over its show's backdrop (the show's logo in place of its title when there is one; the episode with its rating and year on one line; the time, then the file's quality, video and audio as badges, the same ones the player's banner shows; three lines kept for the overview on TV, under a 56 dp logo, five on phones), group tabs (all channels, favorites, then each group that has channels; the open tab is remembered per profile) and the program grid. It opens at the current half hour and goes 7 days ahead.
- **Grid layout.** A lazy column of channel rows sharing one time state. Each row's cells are a `ProgramStrip`: parallel arrays of starts, ends, item ids and titles, so a row lays out without an object per cell. A row composes only the cells inside the visible hours plus 30 minutes on each side, and a custom layout places them by reading the time state, so scrolling the hours relays rows out without recomposing them. A program that began before the visible hours is clipped to the left edge so its title stays readable. The now line and the dimmed past are drawn over the rows from a clock that ticks twice a minute, without recomposition.
- **Loading.** Cells are read from SQLite per 6-hour window for every channel at once (one query joining `schedule_slot` and `media_item`, range-bounded by the longest slot), kept in an LRU of 8 windows, with the windows on either side fetched ahead. Schedule or library changes clear the cache and the visible windows load again.
- **D-pad.** The grid is one focus target that moves the selection itself: right and left step cell to cell and page the hours when a cell starts within 15 minutes of an edge; up and down keep the time, so the cell airing at that moment is selected; channel or page keys move a screenful. Left at the guide's start and up from the first row hand focus to the drawer and the tabs. Entering the drawer lands on the open destination. The guide keeps its place (hours, selection, scroll) while the player or another page is on top; a new launch opens at the current half hour.
- **Touch.** Drag or fling sideways moves the hours, the list scrolls vertically, tap selects, long-press opens the actions.
- **Selecting and watching** (like TiviMate): OK or a tap on a program airing now shows its channel in the preview window; again, it goes full screen. Holding OK, Menu, or OK on a later program opens its actions under a header that says when it airs (ON NOW with the time left, LATER, or AIRED). The words depend on when it airs (see *Watch choices*): on now, *Watch live*, *Start over* and *Watch on demand*; later, *Watch now*; aired, *Watch again*; always *Add show to a channel* or *Add movie to a channel*. An off-air stretch on now offers *Watch live* with when it ends; one still to come opens nothing. Back or a tap outside closes the actions. Focus resting on a row for a second pre-warms that channel on the standby player, so the preview and full screen tune from it.
- **Watch choices** (`WatchChoices`, unit-tested) are one vocabulary for the guide's actions and a channel's page in On demand, each with one line saying what happens, short enough for one row and never repeating the program's name. *Watch live* and *Start over* stay on the channel (user decision): *Start over* tunes it and seeks its program to 0:00, moving the schedule like the player's Start over key (`PlayerRoute(restart = true)`). *Watch on demand* opens the on-demand player for a program on now from its start, and *Resume on demand* from where the channel is (once it is a minute in); each line says where it starts and what differs from the channel ("From the start, then the next episodes.", "From 15 min in, then the next episodes."; a movie's say "off the schedule"). *Watch now*, *Watch again*, *Resume* and *Play from the start* play on demand too and leave the channel as it is. Positions read as minutes in ("From 12 min in", "Resume from 31 min in"), never as a clock time.
- **Shared playback.** The preview window and the full-screen player show the same `ChannelPlayer`. Each screen acquires it while visible; when the last one releases it, playback stops 1.5 s later unless another screen takes it over, so going full screen and back never retunes.
- **On demand is by channel** (user decision: it fixes choosing, so it is not a library browser). The page is a banner, then one row per guide group in the guide's own order (Settings, Channels, *Groups*; favorites first, channels outside a group last as *Other channels*), each channel a tile: its program's backdrop under the wash with the show's or movie's logo and a ladder of how far the airing has got, then the channel's number and name and the time left. Off-air tiles say when the channel is back. What every channel airs comes from one read (`ScheduleDao.airingsAt`), reloaded when the first airing ends and on schedule or library changes. A program over the profile's maturity rating shows as blacked out (see *Profiles, roles and parental controls*).
- **The banner** deals N channels airing something, at random, once per visit (Settings, Channels, *On demand banner*: 5 to 30, 10 by default, shared like the channels; the deal holds while the page reloads; programs the profile may not watch are never dealt). Each slide is the program's backdrop under the wash with the channel's number and name, the logo, the episode, ON NOW with the time left, two lines of overview and the ladder; squares count the channels. It cuts to the next channel every 8 s while focus is elsewhere and holds while focused; left and right (or a swipe) step it, and left at the first channel goes on to the rail. OK opens the channel.
- **A channel's page** is about the program it airs now, or the next one while it is off air (*Up next*); it stays on that program even after it ends while the page is open. The hero shows the channel's number and name, the episode's still (a movie's poster), the logo, facts, when it airs with the time left and its ladder, and three lines of overview on TV. Then the watch choices, two to a row from 600 dp: *Watch live*, *Start over*, *Watch on demand* and *Resume on demand* (off air: *Watch live* with when it's back, and *Watch now*). *Add to a channel* isn't a way to watch, so it sits at the page's top end (user decision): at the end of the hero's channel line on TV, in the back bar on phones. Jellyfin's own resume point isn't a choice of its own; an episode's row plays from it. A show lists every episode within the rating limit, by season tab (the on-air season open and marked), each with its still, the minutes already watched and two lines of overview; on TV, down from the last choices lands on the episode on the channel. OK on an episode plays it from its resume point, or from the start; holding it (or Menu) offers resume, *Play from the start* and Add to channel, and for the episode on the channel the page's own choices. On demand and its channel pages use the guide's margins and gaps (32/12/8 dp on TV, 12/8/6 on phones: `screenSide`, `screenEdge`, `screenGap`), so moving between Live TV and On demand never shifts the edges. Resume points for the whole show come from one request (`Playstate.resumePoints`) when the page opens and again when it comes back into view.
- **On-demand playback** has its own player (the channel player stops first, since many TV devices run one video decoder): OK pauses, left and right jump 10 s back and 30 s ahead, the remote's previous and next keys step through the show. The banner always shows its keys, tracks on the left and the transport on the right in the remote's order: *Previous*, 10 s, *Pause*, 30 s, *Next*, pause in the middle and focused first. Up, down or Menu moves focus into them; phones get a seek bar. Previous and next follow the show's episode order wherever the episode was started; at either end of the show the key stays in place, dimmed, and focus passes over it, and a movie has neither. Episodes continue into the rest of the show. What it leaves in Jellyfin follows Settings, *On demand* (see *Jellyfin watch history*), and it is kept as an `ON_DEMAND` watch event.
- **Add to channel** adds the show (for an episode) or the movie to a channel's *always include* picks and drops it from its excludes; the schedule changes after the airing on now and the next one. It can also create a new channel of its own.
- **JankStats** runs in debug builds and logs, every 5 seconds of drawing, how many frames missed their deadline (`adb logcat -s Anstelle/Jank`).

### Audio and subtitles

- **Defaults per profile** (Settings, *Audio and subtitles*): an ordered list of audio languages, a subtitle mode (*Off*, *Always on*, *When audio is in another language*) and an ordered list of subtitle languages. They are stored as `playback.audio.<profile>`, `playback.subtitles.mode.<profile>` and `playback.subtitles.<profile>`, the lists as ISO 639-2/T codes (`jpn,kor,eng`). Every program, on a channel or on demand, starts on them; a track picked in the player only changes the program on now.
- **Track names** (`TrackLabels`, unit-tested), the same in both players: a track is its language alone. Tracks that share a language add only the facts that tell them apart (English · Signs and English · Full; English · 5.1 and English · stereo), and are numbered when nothing does. The banner's audio and subtitle keys show the language only.
- **Rules** (`TrackRules`, unit-tested). Audio: the first listed language the item has, else its default track. Subtitles: the first listed language the item has, a full track before a forced one, else none. *When audio is in another language* shows none when the audio is in one of the subtitle languages; audio without a language counts as another language. Languages are compared as ISO 639-2/T, so `ja`, `jpn` and `ja-JP` match, and so do `fre` and `fra`.
- **Direct play.** Media3 picks the audio from the language list; `LanguageTrackSelector` (a `DefaultTrackSelector`) picks the subtitle by the rules once the audio is known. Subtitle files next to the video are loaded alongside the stream.
- **Transcoded streams** carry the audio track asked for (`audioStreamIndex`, picked on the device) and no subtitles, so the subtitle the rules pick, if it is text, is loaded next to the stream from `/Videos/{id}/{source}/Subtitles/{index}/Stream.ass` (or `.srt`). The player only offers that one. Image subtitles (PGS, VobSub) aren't available when transcoding.
- **Sync stores the tracks.** Each item keeps its audio and subtitle streams and its font attachments (`media_item.tracks`, JSON), so picking them needs no server call, and its audio languages with the default first (`media_item.audioLanguages`, `|jpn|eng|`), which the catalog reads to tell anime from cartoons.
- **ASS and SSA are drawn by libass** (`io.github.peerless2012:ass-media` 0.5.1, the library Jellyfin's Android TV app uses), so styling, positioning, karaoke and animation look as authored. It draws in its own layer over the video (OpenGL, at most 1080p, scaled up on 4K screens). Fonts come from the Matroska file's attachments or, for a side-loaded subtitle, from the item's attachments (`/Videos/{id}/{source}/Attachments/{index}`, kept in `cache/fonts` up to 150 MB; tuning waits at most about 3 s for them).
- **libass holds one item at a time.** It drops its tracks whenever the player moves to another item, so a program or episode with ASS subtitles isn't queued behind the one before it: it starts on its own when that one ends, a short pause instead of a gapless hand-off. libass keeps every font it is given until its player is released, so each font goes in once.
- **Views.** The full-screen player, the on-demand player and the guide's preview put their player's libass layer into the `PlayerView` (`SubtitleOverlays`). When two views show the same player, the one opened last draws.

### Profiles, roles and parental controls (Phase 8)

- **Profiles are Jellyfin users.** A profile shows its Jellyfin user's name and picture, read again at every library sync; nothing local names it. *Add a profile* signs in (Quick Connect or password) and makes a profile from that user: on the only server signed in, or, with more than one, on the one picked first from a short list (user decision). A profile has **one Jellyfin user per server** (`profile_account`; user decision): the profile page has a sign-in row for each connected server. Each sign-in uses a device id of its own (`profile-xxxxxxxx`), so it never replaces the server's session or another profile's, and its token sits in the Keystore token store keyed `<server id>/<device>`. The profile is named after its user on the first server it has one on. The library and its sync stay on the server's own sign-in (user decision).
- **Who a profile is on a server** (`ProfileRepository.watchAccount`, user decision): with a user of its own there, the profile **plays as that user** (streams open with that user's token and device, so the server's own access rules apply and Jellyfin's dashboard shows who is watching) and its watched marks and resume points go there. Once that sign-in's token is gone, nothing plays as anyone else: the player asks to check the sign-in. A profile with no user of its own anywhere (the first profile, made at first launch) is each server's own sign-in, watch history and all. A profile with users on other servers only plays as the server's own sign-in on a server it has none on, keeps no watch history there, and its parental rating still applies.
- **Library access** (user decision): channels are shared, but a profile sees only what its own Jellyfin user may. Every library sync lists, ID-only, what each profile's user can see in the synced libraries (`LibrarySyncer.findHidden`: the user's own views, then each one's item ids, so Jellyfin applies the user's folders, parental rating and tags), and the rest is kept for the profile in `profile_hidden_item`. Such a program still airs, blacked out like an age-restricted one but as **No access** ("Not shared on Jellyfin"; `Restriction.NoAccess`, `ProfileAccess`), joins the restricted stretch around it, never plays on demand and drops out of a show's episode list. A channel whose every upcoming program is hidden from the profile leaves its guide, On demand and surfing altogether (`ProfileRepository.tunable`, `ScheduleDao.hiddenChannels`; user decision: what a profile can't use isn't shown). A new sign-in clears what was hidden from the user before and syncs the server to look again. A library the server's own sign-in can't see never reaches any channel, so that sign-in wants the widest access.
- **Roles** (user decision, `ProfileRights`, unit-tested). The first profile set up is the **owner**, the root admin; there is always exactly one. The owner makes other profiles **admins** and back, edits every profile without its PIN, can't be deleted, and can hand the owner role to another profile (*Transfer ownership*, select twice), staying an admin. An admin edits members' profiles without their PINs and the admin's own, never another admin's or the owner's. A **member** edits only the member's own profile. Only the owner and admins add profiles.
- **No access means not shown** (user decision): a profile never sees a setting it can't change. *Manage profiles* lists only the profiles the one watching may edit; on the profile page, *Parental Rating* shows only to the owner or an admin editing a member, *Set Admin* and *Transfer ownership* only to the owner editing someone else, and the *Permissions* heading only when one of them shows.
- **PINs.** 4 digits, stored as `pbkdf2$20000$salt$hash` (PBKDF2-HMAC-SHA256 written over `Mac`, since `PBKDF2WithHmacSHA256` arrived in API 26). A profile's PIN is asked when switching to it (always at launch) and before editing one's own profile (user decision). Five wrong PINs in a row block PIN checks for 30 seconds.
- **App-wide settings are the owner's and admins'** (user decision): media servers, channels (lineups, numbers, groups, segments, Add to channel, logos), web control and the dashboard. A member doesn't see them at all: Settings shows only Playback and Appearance, the Dashboard tab is gone, the guide and On demand offer no *Add to a channel*, and the empty-guide page has no Settings link. A member who lands on one of those routes anyway (a restored back stack) is sent back (`AdminOnly`). Pairing a browser is app-wide too: on a member's profile the pairing overlay doesn't show.
- **Per profile** (user decision): watch history (*Live TV* and *On demand*, stored as `sync.live.<profile>` and `sync.ondemand.<profile>`), the theme, audio and subtitles, the guide's open tab and the last channel. Deleting a profile signs its Jellyfin user out and removes its settings.
- **Channels are shared** (user decision): every profile sees every channel, group, number and schedule; channel tables carry no profile. The library's channels are made once from the whole library.
- **Parental Rating** (Permissions on the profile page, user decision): members only, set by the owner or an admin. The choices are Jellyfin's own ratings (user decision): the server's `/Localization/ParentalRatings` table, kept at every sync (`ratings.table.<server>`), one level per score, from *No limit* down. A level shows only when some item in the library scores above the level under it and up to it, so every step changes what plays; items rated in another country's system ("NC16", "12+") count by their score. A level is named with the table's ratings the library carries most, at most two ("TV-MA / R"), else the table's first for that score (`RatingLimits.levels`, unit-tested). Before the first sync stores a table, the library's own ratings stand in. A program rated over the limit, or unrated under a limit, still airs on its channel but is **age restricted** for the profile (`Restriction.Age`): its picture and backdrop are blacked out, its overview is dropped, and its title, logo and episode are blurred wherever they show (user decision; `Modifier.obscured`, a real blur from API 31, a solid bar on older devices such as Fire OS, and screen readers hear "Restricted"). Back-to-back restricted programs count as **one stretch** (user decision): the guide merges them into one cell (`ProgramStrip.mergeRestricted`, unit-tested), and every "until" (the player's, the guide's cell and actions, On demand's tile and channel page) is the end of the whole stretch (`ScheduleDao.restrictedUntil`: the first slot from there that is off air, or within the limit and not hidden from the profile). The channel player keeps the screen dark with a lock, AGE RESTRICTED, the rating against the limit and UNTIL the stretch's end, then tunes itself again; a restricted program is never queued behind another or pre-warmed, and nothing is reported. The guide's cells lie on the ground with a lock and *Age restricted · until …*, and its actions offer only *Watch live* (dark) while the stretch airs. On demand never deals a restricted channel to the banner, its tile says *Age restricted until …* over a black picture, the channel page offers only *Watch live*, nothing over the limit plays on demand, and a show's episode list keeps to the limit.
- **Wording and grouping** (user decision): the profile page never calls a profile "it" or uses a personal pronoun, and repeats no profile name in a row (the page title carries it). It has two groups, the way account pages group settings: *Profile* (Theme; the sign-in, *Authentication* with one server or one row named after each server when there are more, showing the Jellyfin user the profile plays as there, or *Not signed in*; Set PIN or Change PIN; Remove PIN), the same for everyone who may edit the profile, then *Permissions* (Set Admin, Parental Rating, Transfer ownership), what the owner or an admin sets. Row titles are two words at most (user decision). *Delete profile* (select twice) stands apart at the foot under no heading.

### Themes (Phase 8)

- **16 built-in themes** in `core/theme/src/main/assets/themes`, Rose first: Amber, Cobalt, Contrast, Ember, Frost, Lagoon, Lilac, Mint, Mono, Moss, Paper (light), Phosphor, Plum, Sepia and Velvet. `tools/themes/generate_themes.py` builds each one from a hue ladder and a signal color in OKLCH and checks contrast floors; `ThemeContrastTest` checks every shipped file. They are copied into the `theme` table at start (`ThemeRepository.seedBuiltIns`).
- **Each profile wears one** (Settings, *Themes*); the whole app re-themes at once, and phones switch their system bar icons for light grounds.
- **The designer** edits a copy (built-in themes never change): the name, each color role as lightness, colorfulness and hue ladders or a hex code, type, guide row density and scanlines. Colors that read badly against each other are named with the ratio they reach and the one they need. Changes save as they are made.
- **Scanlines** are a cached 1-pixel-wide bitmap repeated by an `ImageShader` over everything, video included, so a frame allocates nothing (API 25 has no `RuntimeShader`).
- **Import and export:** phones use the system file picker; any device trades theme JSON over the web API (`GET /api/themes`, `GET /api/themes/{id}` as a file, `POST /api/themes`, at most 64 KB).
- **TV text fields** open the on-screen keyboard only on OK, so moving through a form no longer pops it up.

### Look and feel (UI redesign)

- **One visual system, recorded in `DESIGN.md`.** The app speaks the TV's own on-screen display: channel banner, now/next bar, number entry, a setup menu. Everything is flat: no shadows, gradients, glow or blur, near-square corners.
- **Focus is a fill.** A focused row, cell, tab, poster or drawer item turns rose with ink text; nothing zooms or glows (Compose for TV's default scale and glow are switched off). `FocusSurface` in `core/theme` is the one clickable surface every control is built from, for D-pad and touch. Buttons and keys are fills with no outline in any state (user decision).
- **State never relies on color alone.** On air is a solid square, up next a hollow one; problems carry a warning icon as well as the accent.
- **Fonts:** Barlow and Barlow Condensed (OFL, `core/theme/licenses/barlow-OFL.txt`) are bundled as static files in `core/theme/src/main/res/font`. Fire TV has no downloadable fonts, and Fire OS 6 (API 25) can't set variable font axes. Numbers use tabular figures.
- **Theme spec.** `ThemeColors` names roles, not hues: background, surface, surfaceHigh, line, text, textMuted, accent, onAccent, accentContainer, onAccentContainer, plus the guide and banner colors. The default theme is `assets/themes/rose.json`, mirrored by `DefaultTheme`; Phase 8 adds 15 more in the same format (see *Themes*).
- **Settings** is a setup menu: each row shows its value on the right, choices step with left and right on the D-pad, and on TV and tablets an info panel beside the menu explains the focused row.
- **Navigation is one component everywhere** (user decision), styled like YouTube's or Spotify's tabs rather than Material's: no divider, no pill, no ripple or hover, on the surface step so it reads apart from the page. The open tab has a filled icon and a bright label, the rest are outlined and quiet; a press briefly shrinks the icon. Phones get a bottom bar; tablets (600 dp and wider) and TV get the same tabs as a rail headed by the bug (TV replaced the expanding drawer). On TV the D-pad's focus fills the tab rose, and entering the rail lands on the open destination. The first destination is labelled *Live TV*.
- **Phone sub-pages** (settings pages, show and item pages) have a back bar. On phones a two-value setting is a switch and a multi-value one opens a list; the left/right stepping is TV only.
- **Guide header.** The preview and the details meet with no gap, one band of picture (user decision). On a phone held upright (narrower than 600 dp) the preview spans the width with the details hanging straight under it (user decision); wider screens keep them side by side.
- **Backdrops** lie under one flat wash of the ground at 80% (`BACKDROP_WASH`), never a gradient, so bone text keeps its contrast even over a white sky. With the guide's past shade it is one of two translucent layers.
- **Player keys live in the banner** (user decision). Each player has one banner, always with its keys (user decision). The tech line sits at the right end of the NOW line (ON DEMAND in the on-demand player) on every device; NOW and the time hold still and a tech line too long for the room left scrolls as a marquee (user decision). The controls are a row of OSD keys under a hairline inside the program's column; the number block and the NEXT column beside it don't change. The track keys (their icon and the track in use: ♪ JAPANESE, subtitles OFF) anchor left and the player's own actions right: *Restart* on a channel; previous, 10 s back, pause, 30 s ahead and next on demand. Both banners show the show's or movie's logo in place of its title when there is one. There are no channel keys: phones swipe. Opening audio or subtitles swaps the row for the choices, the one in use checked. The banner sits 12 dp from the screen's edges on TV as on phones (user decision). Nothing floats over the picture apart from number entry and notices. Progress reads time in, a 40-segment ladder, full length, in both players on TV and phones; phones tap or drag the ladder to seek.
- **Banners sized for the hand** (user decision: phones don't copy the TV). A phone held sideways is about 410 dp tall against a TV's 540, and a dp at arm's length looks about as big as one across the room, so phone and tablet banners use TV-sized type or smaller (body-medium detail, a 28 dp logo, NEXT in title-medium and body-small), a 24 dp ladder strip that Compose widens to the 48 dp touch minimum, and 48 dp keys that close the column with no padding under them. On a Pixel 8 the channel banner went from 226 dp (58% of the screen with its inset) to about 155 dp. NEXT shows on screens at least 720 dp wide; a narrower phone leaves it out. The on-demand banner on phones sets NEXT at the right end of the detail line instead of a row of its own.
- **Banner ends are squares** (user decision): the channel number block and the NEXT block are squares of one size, as tall as the banner (`SquareEnds` settles the side by asking the middle column its height at the width left over). NEXT shows the next program's logo or title, then S#E# and the episode title. Under a logo, a movie's line puts its title beside its year, 2025 · Title, in NEXT, both banners and the guide's details (user decision); without a logo the title is set above and the year stands alone. In the guide a movie's year leads its line, so its facts keep only the rating.
- **Guide grid layout is unchanged.** The redesign only restyled it: the program on air carries a small square drawn from the clock without recomposing rows; the now line runs over the cells down from a playhead in the time header (user decision: on the blocks, not behind them); the focused cell's detail text is full ink so it stays legible under the past shade; cells too narrow for a word show only their fill. On phones the channel column stacks the number above the name.

## UI redesign verification (2026-10-07)

- **Gradle:** `assembleDebug testDebugUnitTest lintDebug` passes, including the JVM module tests (`ThemeSpecTest` checks `rose.json` against `DefaultTheme`).
- **Devices:** Google TV AVD (1920×1080), Pixel 8 AVD and the same AVD resized to a 2560×1600 tablet. Every top-level screen, the player banner, number entry, program actions, settings (TV setup menu with info panel, phone switch and choice list), show page and dashboard were captured on the shipped device classes.
- **Review:** a separate design review asked for eight fixes (touch controls in settings, focused-cell contrast under the past shade, the phone details line, phone back bars, a solid banner, pinned settings headers, bare narrow guide cells, the nav). After two rounds it passed them. The now line stays over the cells by user decision.
- Not checked: hardware (Fire TV Stick waits for Phase 11), the launcher icon on a real launcher, 1.3× font scale, and the profile picker with more than one profile.

## Library channels verification (2026-10-07)

- **Gradle:** `assembleDebug testDebugUnitTest lintDebug assembleRelease` passes, plus the JVM module tests: 169 unit tests, 0 failures (generator names, segments, mixing rules, anime and cartoon decisions, thresholds, duplicates, order, rating limits, determinism, lineups matching exactly what was counted; the sync's add, refresh, remove and segment numbering; the word list and its 1,400 names; audio languages from the mapper). Lint shows no issues in any library module; the app module keeps its two earlier warnings.
- **Speed:** 50,000 items generate in about 150–220 ms on a warm JVM. Reading each show's genre, tag and studio lists once instead of once per episode made it about three times faster.
- **Google TV AVD, real server** (276 shows, 3,749 items, mostly anime; TVDB and TMDB metadata):
  - The first rules made 185 channels: TMDB's mood, time-setting and meta keywords became themes, and anime lists every regional station that carried a show. With ignored tags, an 8-show bar for themes and the rule that drops a studio inside a bigger one, the library gets 106 channels: Productions 32 (1000–1031), Decades 7 (2000–2006), Genres 30 (3000–3029), Themes 37 (4000–4036), scheduled 30 days ahead in about 9 s (debug build).
  - Of 276 shows, 221 are anime, 10 cartoons, 42 neither and 3 unclear (an untagged anime film with Japanese audio, a western cartoon whose files default to a Japanese dub, and one more). A check of every scheduled airing found no genre or decade channel mixing series and movies or kinds, or airing unclear animation.
  - Following the library: new rules installed over existing channels removed and added channels at the next sync and renamed others in place. Changing a channel's playback order stopped it being managed while the rest moved around it. Deleting a managed channel recorded it and renumbered the rest; *Removed channels* listed it and *Bring back* restored it in its place.
  - *Channel numbers*: setting Productions to 100 renumbered them 100–130 at once, and back to 1000–1030. *Add a segment* made a named group starting at the next free thousand.
- Not checked on a device: the phone layouts of the new screens, kid profiles (the AVD has one profile), putting a channel into a segment, and the last change to where a segment or a channel built outside one starts (5000 with the defaults; covered by the build only).

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

## Phase 4 verification (2026-10-06)

- **Gradle:** `assembleDebug testDebugUnitTest lintDebug assembleRelease` passes, plus the JVM module tests: 117 unit tests, 0 failures. Lint shows no issues in any library module; the app module keeps its two earlier warnings.
- **Unit tests:**
  - Matcher: every field and operator, case-insensitive lists, whole-word keywords, rating text and scores, missing values, AND within a group and OR across groups, weights, includes and excludes; 50,000 items match in under 50 ms.
  - Rule rows round-trip, including picks, weights and groups kept for dayparts.
  - Inputs: base pool, rating limit, override seasons as seasonal pools, daypart rule groups only inside their window, fingerprints.
  - `ScheduleUpdater`: a new schedule starts at the current hour and reaches 30 days; unchanged inputs are left alone; extension keeps every existing row and stays contiguous; a new lineup keeps the airing on now and the next one; an airing whose item was deleted is replaced from its start; hidden channels get nothing; a schedule that ran out restarts at the current hour.
  - Box-set membership changes (own and series box sets, removals), box-set reading through the SDK, logo upload routes (401, 404, 400 for non-images, 413, versioned caching).
- **Google TV AVD, real server:**
  - After a full sync, 42 channels in 8 groups were scheduled in 4.7 s (debug build); 73k rows, all contiguous, every program exactly its item's length, 30 days ahead, starting at the current hour. App restarts and later syncs report every schedule up to date.
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

## Artwork, stream facts and channel seeking verification (2026-10-08)

- **Gradle:** `assembleDebug` passes; new JVM tests for `Artwork` (series fallback, own art first, none), `StreamFacts` (quality, codecs, audio layouts), `ScheduleRepository.shift` (seeking back keeps the shown part as history and merges repeats; seeking ahead cuts or drops what it overlaps; engine state and `generatedUntil` move) and the mapper's backdrop and logo tags.
- **Database:** version 5 (two artwork columns); version 4 is dropped like every pre-release schema, so the server is added again and the library re-synced.
- **Google TV AVD, real server:** nearly every show and movie has a backdrop and a logo. The guide's details show the logo over the washed backdrop with five overview lines; show pages open on the hero. In the player, the tech line read "<server> | DIRECT PLAY 1080P H.264 OPUS STEREO"; 30 s ahead moved the airing 27 s earlier and *Start from beginning* 679 s later, with the guide showing the shown part as its own airing. On the device's SQLite, the channel's 1,838 slots stayed back to back with no gaps and the last one ended at the stored `generatedUntil`. Audio choices opened inside the banner.
- **Phone AVD** (the TV's database; its own sign-in is rejected by the server, so nothing plays there): the guide stacks a full-width preview over the details, the player turns sideways and hides the bars and Back restores portrait, the show page's hero runs under the back arrow, and the banner's keys sit under the program like on TV.
- Not checked: transcoding's label on a device, a phone that can play, tablets, seeking across a program's end on a channel with ASS subtitles, and contrast over every backdrop.

## On demand by channel verification (2026-10-08)

- **Gradle:** `assembleDebug testDebugUnitTest lintDebug` passes, with new JVM tests for track names (`TrackLabels`: a language alone, only the facts that differ, numbering, no language) and the watch choices (tense, minutes, wording by when it airs). Lint shows no new issues; `SettingsScreen` and the app module keep their earlier warnings.
- **Google TV AVD, real server:** the banner deals ten airing channels and holds while focused; rows follow the group order with the time left and ladders; Back from a channel page lands on the tile it came from. A channel's page shows the program and its choices on the first screen; down from the last choice lands on the episode on the channel; Menu on it offers the page's choices, live included. The guide's actions read by tense (on now, later; a movie offers *Add movie to a channel*). In the player, *Previous* stepped from an episode to the one before it and NEXT followed; on a first episode *Previous* stays dimmed in place; the audio list showed languages only.
- **Phone AVD** (the TV's database; nothing plays there): banner, rows, channel page with *Watch live* as the rose primary, a held episode's dialog, and the banner size row with its list.
- A finish design review asked for five fixes (a status label above the on-air episode, a primary choice on touch, the phone ladder's pitch, the banner row's wording, a recorded direction); all were made.
- Not checked: an off-air channel's page (no channel was off air), two subtitle tracks in one language on a device, tablets.

## Profile accounts and library access verification (2026-10-09)

- Built (`:app:compileDebugKotlin`, schema 7 exported) and unit-tested: `ProfileAccessTest`, the guide's restricted stretches with hidden items (`GuideTest`), and every other module's tests.
- Not checked on a device (both AVDs were offline): a member signed in on a second server, playing as a profile's own user (Jellyfin's dashboard should show that user), a library the profile's user can't see blacked out as No access, a channel made only from such a library leaving the guide, and a profile whose token is gone.

## Known follow-ups

- A channel playing in the preview or full screen keeps the number it was tuned with, so after a sync renumbers channels its label is out of date until it is tuned again.
- In an anime-heavy library several school themes overlap (School, High School, Japanese High School, School Life); none is similar enough to another to be dropped.
- Movie sync is covered by the derived fixture only. Give the test account a movie library to check it against a real server.
- `ScheduleEngine.rewind` rewinds show cursors but not shuffle decks: items in discarded slots of a shuffle channel wait for the next deck.
- Box-set membership is only checked against hand-made fixtures: the test account's one box set lists no items it can see.
- Choosing a logo on the device uses the system file picker, which many TV devices lack (not tried on the TV AVD); a browser can upload one with `PUT /api/channels/{id}/logo`. The web page for it comes with web UI parity (Phase 10).
- HLS takes about 9 s to start on the test server, and a channel tuned that way runs behind until its next program. Measure on the Fire TV Stick; the standby could pre-warm HLS channels earlier.
- The off-air screen and the phone gestures weren't exercised on a device: no channel has dayparts yet, and only the TV AVD was used.
- Music channels (and their now-playing screen) wait until music libraries are back.
- Guide frame timing on the AVDs is limited by their software GPU; measure the 100-channel scroll on the Fire TV Stick (Phase 11).
- The Add to channel page sits outside the tabs, so no drawer or bar destination is highlighted while it is open.
- On a phone a channel's page opens on the on-air episode's season but doesn't scroll to the episode, so the program at the top stays in view.
