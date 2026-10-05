# AnstelleTV — product and build context

AnstelleTV turns a Jellyfin library into 24/7 retro cable TV on Android phones, tablets, Android TV / Google TV and Fire TV (one APK, touch and D-pad). Channels play programs back to back. There is no backend. All data lives in on-device SQLite (Room). A LAN web UI (SvelteKit) is served by the app itself for setup and in-browser watching.

The app is native: Kotlin + Jetpack Compose (Compose for TV on TV, Material 3 on phones), with ExoPlayer for playback. There is no WebView. The web UI is for browsers on other devices on the LAN.

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | Scaffold: modules, catalog, Room schema, Hilt, placeholder screens with D-pad focus, Ktor server with pairing, SvelteKit shell, baseline profile + benchmark modules | Done |
| 2 | Jellyfin login (Quick Connect, password), multiple servers, incremental sync, reconciliation, media segments, rating scores | Done |
| 3 | Scheduler engine: back-to-back airings, playback orders, seasonal lineups, dayparts, resumable state, `nowPlaying` | Done |
| 4 | Channels: templates, matcher, custom builder, schedule extension worker | Next |
| 5–11 | Playback, guide, live TV, profiles/themes, dashboard/backup, web UI parity, performance | Planned |

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
| `core/player` | `PlayerFactory` (ExoPlayer, OkHttp data source, FFmpeg audio fallback) |
| `core/theme` | `ThemeSpec` JSON model, `AnstelleTheme` (Material 3 + TV), shared focusable components |
| `core/data` | Hilt modules, repositories (`ServerRepository`, `ScheduleRepository`, profiles, settings), `LibrarySyncer`, `DatabaseChanges`, `WebServerController`, workers |
| `feature/*` | Guide, player, on-demand, profiles, settings, dashboard (placeholders plus the log viewer, web-control settings and the media server screens) |
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
- **FFmpeg decoder:** `org.jellyfin.media3:media3-ffmpeg-decoder` latest is `1.9.0+1`, built for Media3 1.9. It is used with Media3 1.11.1 and is gated by a decode smoke test in Phase 5. If that test fails, Media3 gets pinned to 1.9.x.
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
- **Fields:** `MediaSources` (source id, container, streams, `HasSegments`) cost about 8 KB per item, so pages hold 150 items.
- **Media segments** are fetched only for items whose source reports `HasSegments`, 4 at a time, and cached in `media_segment`.
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
  - First sync: show and episode counts equal `/Items/Counts` for the user exactly. About 3.7k items took about 77 s on the emulator, most of it per-item segment requests.
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

- When D-pad focus enters the TV drawer, it should land on the selected destination.
- On a focused tile, the muted subtitle color has low contrast. This will be tuned with the theme work in Phase 8.
- The `collections` column (box-set membership) is still empty. Fill it during reconciliation when channel templates need it (Phase 4).
- Movie sync is covered by the derived fixture only. Give the test account a movie library to check it against a real server.
- The first sync spends most of its time on per-item segment requests (about 50 s for 3.4k items on the emulator). Measure on the Fire TV Stick in Phase 11.
- `ScheduleEngine.rewind` rewinds show cursors but not shuffle decks: items in discarded slots of a shuffle channel wait for the next deck.
