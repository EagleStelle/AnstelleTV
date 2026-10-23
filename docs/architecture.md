# Architecture

AnstelleTV is a single Android app with no backend. The only outside system is Jellyfin, which supplies metadata and streams. Everything else, from channels and schedules to profiles, watch stats and the web admin, runs on the device and is stored in one Room database.

## Overview

```
 Jellyfin server(s)
      │  metadata (incremental sync)                 streams (direct play or HLS)
      ▼                                                         │
 core:jellyfin ──► core:data/sync ──► core:database (Room) ◄────┼──────────────┐
                                        │        ▲              │              │
                    core:data/channels ◄┘        │              ▼              │
                    (ChannelGenerator,           │        core:player          │
                     LineupMatcher)              │        (PlayerPool,         │
                          │                      │         StreamPlanner)      │
                          ▼                      │              ▲              │
                    core:scheduler ──► core:data/work ──────────┘              │
                    (ScheduleEngine)   (ScheduleUpdater writes schedule_slot)  │
                                                                               │
 feature/* (Compose UI)  ◄── repositories in core:data ──► webserver (Ktor) ◄──┘
                                                            │
                                                     webui (SvelteKit) in a LAN browser
```

## Components

| Component | Responsibility |
|---|---|
| `core:jellyfin` | The shared `OkHttpClient` and Jellyfin SDK instance, server discovery, Quick Connect and password sign-in, the Keystore-backed token store, the library source and DTO-to-entity mappers. `SdkTime.kt` handles every SDK date conversion. |
| `core:database` | Room database `AnstelleDatabase` with 21 tables, DAOs and exported schemas. |
| `core:data` | Repositories and domain logic: sync, channel generation, schedule updates, guide data, on-demand catalogs, playback and watch reporting, dashboard stats, backups, profiles, themes, settings and the WorkManager workers. |
| `core:scheduler` | A pure JVM schedule engine with no Android dependency. |
| `core:player` | ExoPlayer pool, device capability probe, stream planning, track selection, subtitle rendering and font cache. |
| `core:theme` | `ThemeSpec` JSON model, `AnstelleTheme`, and the shared Compose components (`FocusSurface`, settings rows, `MenuPage`, `ChannelLogo`). |
| `feature:*` | Screens and view models: guide, players, on demand, profiles, settings, dashboard. |
| `app` | `MainActivity`, navigation (`AnstelleApp.kt`, type-safe `@Serializable` routes in `Routes.kt`), the pairing overlay and app-level Hilt bindings. |
| `webserver` | Ktor CIO server, pairing, LAN guard, the admin API and `/ws`, run as a `specialUse` foreground service. |
| `webui` | SvelteKit single-page app built into `app/src/main/assets/webui` and served by `webserver`. |

## Data flow

### 1. Library sync

`LibrarySyncer` (`core/data/sync`) pulls items from each signed-in server into `media_item` through `core:jellyfin`.

- Sync is incremental. Each library's watermark is the server's own clock (`/GetUtcTime`) at the start of its last sync, re-read with a 5-minute overlap, because `BaseItemDto` exposes no `DateLastSaved`.
- Series are stored before their episodes, and episodes inherit genres, studios, tags and rating from the series.
- Deletions do not change any timestamp, so an ID-only listing runs at least every 6 hours and on manual sync. It deletes items that vanished and sets `inputsHash = 0` on the schedules that air them, which marks those schedules stale.
- Each item stores its audio and subtitle tracks and font attachments as JSON, so choosing tracks later needs no server call.
- For every profile, sync also records which items that profile's own Jellyfin user cannot see (`profile_hidden_item`).

`LibrarySyncWorker` runs the sync every 6 hours.

### 2. Channels

`ChannelGenerator` (`core/data/channels`) decides which channels exist using only library metadata. It builds four segments, each a guide group with its own number range:

| Segment | Default start | Source |
|---|---|---|
| Productions | 1000 | Studio and network values |
| Decades | 2000 | Air dates, split by media type and kind |
| Genres | 3000 | Genres, genre pairs, genre plus media type |
| Themes | 4000 | Tags |

`core/data/src/main/assets/channel-words.json` only supplies wording: genre channel names, genre spellings, adjectives and tags to ignore. It names no titles and decides no lineups.

A lineup is a set of ordinary `channel_rule` rows: rule groups are ORed and the rules inside a group are ANDed, plus explicit include and exclude picks. `LineupMatcher` compiles the rules and matches the whole catalog in memory; a unit test matches 50,000 items in under 50 ms.

`GeneratedChannelSync` reconciles generated channels with stored ones after every sync. Library-made channels can only be hidden or favorited (`LibraryChannelLockedException` otherwise). Channels a person builds are fully editable.

### 3. Schedule

`ScheduleEngine.generate(inputs, state, until)` in `core:scheduler` is deterministic. It has no clock, no I/O and no shared state:

- `CandidatePool` sorts candidates canonically, so load order never changes a schedule.
- `EngineState` is a compact, resumable blob: next start, SplitMix64 state, per-series cursors, shuffle deck seeds and repeat bookkeeping. Extending a schedule by 10 days and then 20 gives the same result as generating 30 at once.
- `ScheduleInputs.fingerprint()` becomes the stored `inputsHash`. When it changes, the airing on now and the next one are kept and the rest is regenerated.

`ScheduleUpdater` and `ScheduleWorker` (`core/data/work`) keep every visible channel 8 days ahead (the guide shows 7) and write one `schedule_slot` row per airing, back to back. App start and the 12-hourly worker only top up schedules that are due. The full library is read again only after a sync, a scheduler revision or a time-zone change.

### 4. Playback

Tuning a channel makes no server call:

1. `ChannelPlayer` reads the current airing from SQLite (the last slot that starts at or before now).
2. `StreamPlanner` chooses direct play or HLS by comparing the file's stored container and codecs with a `CapabilityProbe` snapshot of the device's decoders.
3. `JellyfinStreams` builds the stream URL from the stored server address. The player's OkHttp data source adds the `Authorization` header to every request, so the token never appears in a URL.
4. `PlayerPool` hands over the active ExoPlayer or swaps in a pre-warmed standby.

`PlayerPool` holds one active player and up to two standbys, aimed at the channels on either side while the viewer surfs. The standbys drop to one after 10 seconds of rest and are released after two minutes. Buffers are capped per player, and low-RAM devices get smaller caps.

Seeking on a channel moves that channel's whole schedule (`ScheduleRepository.shift`, one transaction), so the guide always matches the picture. `PlaybackReporter` sends Jellyfin watch status and progress according to each profile's settings and hands every view to the dashboard as `watch_event` rows.

### 5. UI

Feature modules read repositories through Hilt view models. A few patterns carry most of the performance work:

- The guide grid stores each row's cells as a `ProgramStrip` (parallel arrays of starts, ends, ids and titles) and places them in a custom layout that reads a shared time state. Scrolling through time lays rows out again without recomposing them.
- Guide cells load per 6-hour window for all channels in one query and are kept in an LRU of 8 windows.
- Screens read data only while visible. Hidden tabs and pages under the player stop reading and refresh when they show again.

`LocalIsTv`, set from `isTvDevice()` in `app/DeviceUi.kt`, switches between TV and phone layouts inside the one APK.

### 6. Web admin

`webserver` runs Ktor CIO inside `WebServerService`, a `specialUse` foreground service.

- It binds only to LAN IPv4 addresses and loopback, and `LanGuard` rejects any remote address outside RFC 1918, link-local, ULA and loopback ranges.
- `PairingManager` and `PairingGate` pair a browser with a 6-digit PIN shown on the TV. The resulting token belongs to the owner or admin profile that was active when pairing was approved, and it stops working as soon as that profile is no longer the owner or an admin.
- `StaticFiles` serves the SPA from `asset-index.txt` and pre-gzipped assets.
- Routes in `routes/` call one data source per area in `api/` (`ChannelsApi`, `ServersApi`, `ThemesApi`, `BackupApi`, `DeviceApi`), so route tests can fake one area at a time.
- `/ws` forwards Room table invalidations (`DatabaseChanges`) to the browser, which re-reads the page. Edits made in the browser reach the TV through Room like any other edit.

See [api.md](api.md) for the endpoints.

## Patterns

All data moves through Room. Sync, the scheduler, the UI and the web API all write to Room, and every reader observes Room flows. The web UI hears about changes through the same invalidation tracker.

Repositories in `core:data` do the I/O, and the decisions they make live in pure functions with unit tests: `TrackRules`, `ProfileRights` and `RatingLimits` in `core:model`; `WatchRules`, `StatsMath`, `ChannelNumbering`, `GroupRules` and `SignIns` in `core:data`, among others.

The scheduler takes inputs and state and returns slots and a new state. It has no clock of its own, so `ScheduleUpdater` passes in the time from `TimeSource`.

`build-logic` defines the `anstelle.*` convention plugins. A new feature module applies `anstelle.android.feature` and gets Compose, Hilt and the core modules without listing them.

`ProfileRights` decides who may do what. A screen a profile cannot use is not shown to it at all, and `AdminOnly` sends a member back if a restored back stack lands on one.

`ThemeColors` names roles such as background, surface, accent and text. The app and the web UI read colors only through those roles, so all 16 themes and custom ones apply everywhere.

## Database

Room schema, grouped by area (entities in `core/database/.../entity`):

| Area | Tables |
|---|---|
| Servers and library | `media_server`, `library`, `media_item` |
| Channels | `channel`, `channel_group`, `channel_rule`, `channel_season`, `channel_daypart` |
| Schedules | `schedule_slot`, `schedule_state` |
| Profiles | `profile`, `profile_account`, `profile_hidden_item` |
| App | `setting`, `theme`, `paired_client`, `watch_event`, `restored_item` |
| Live TV (unused) | `live_source`, `live_channel`, `live_program` |

- Enums are stored by a stable `code` from `core:model`, never by ordinal or name, so reordering an enum never corrupts data.
- Schemas are exported to `core/database/schemas`. Debug builds fall back to destructive migration. Release builds drop only the versions listed in `AnstelleDatabase.PRE_RELEASE_VERSIONS`; real migrations begin with the first public release.
- `ScheduleCleanupWorker` runs daily. It removes old slots and dashboard rows older than 400 days, trims the font cache, and runs `VACUUM` once a quarter of the file is free pages.
- Jellyfin tokens are not in the database. `KeystoreTokenStore` encrypts them with an AES-GCM key from the Android Keystore and keeps only the ciphertext in private preferences.

## Why it is built this way

With no backend, the product is one install with no account and nothing to host. The cost is that scheduling and matching have to be cheap enough for a Fire TV Stick, which is why the engine is pure and the matcher runs in memory.

Tuning locally makes channel changes fast and keeps them working through brief server outages. It works because sync stores everything the stream decision needs: container, codecs, tracks and fonts.

Deterministic scheduling makes every schedule reproducible from stored state. Extending, regenerating after an edit and shifting after a seek all go through the same engine, and the engine is tested on the plain JVM.

The web admin lives inside the app because TVs without a file picker or keyboard still need a way to edit lineups, import themes and save backups, and adding a server would break the no-backend rule.

[decisions.md](decisions.md) has the individual trade-offs.
