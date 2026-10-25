# Project structure

The repository is one Gradle build with 19 modules, plus the SvelteKit web UI in `webui/`. Module paths map to packages: `:core:data` is `tv.anstelle.core.data`, `:feature:guide` is `tv.anstelle.feature.guide`.

## Tree

```
AnstelleTV/
├── app/
│   ├── src/main/kotlin/tv/anstelle/app/
│   │   ├── AnstelleApplication.kt   Hilt application, WorkManager setup
│   │   ├── MainActivity.kt
│   │   ├── AppModule.kt             App-level Hilt bindings
│   │   ├── DeviceUi.kt              isTvDevice(), sets LocalIsTv
│   │   ├── JankLogger.kt            JankStats in debug builds
│   │   └── ui/                      AnstelleApp (navigation), Routes, PairingOverlay, StartupViewModel
│   ├── src/debug/                   Test entry points for instrumented tests
│   ├── src/androidTest/             App-level device tests
│   ├── src/release/generated/baselineProfiles/
│   └── src/main/assets/webui/       Packed web UI (generated, gitignored)
├── build-logic/convention/          Convention plugins and the web UI build tasks
├── core/
│   ├── model/        Models, coded enums, track and rating rules, OKLCH, segments
│   ├── common/       TimeSource, AppLog, AppInfo, dispatcher qualifiers, trace names
│   ├── scheduler/    ScheduleEngine, CandidatePool, EngineState, ScheduleInputs, SplitMix64
│   ├── database/     AnstelleDatabase, dao/, entity/, Converters; schemas/ (exported)
│   ├── jellyfin/     JellyfinClients, auth/, sync/, di/, SdkTime, SegmentSource, ServerProbe
│   ├── player/       PlayerPool, PlayerFactory, StreamPlanner, CapabilityProbe, JellyfinStreams,
│   │                 LanguageTrackSelector, PlayerSubtitles, SubtitleStyle, FontCache, TuneTrace
│   ├── theme/        AnstelleTheme, ThemeSpec, components/, assets/themes (16 built-ins),
│   │                 res/font (Barlow, Barlow Condensed), licenses/
│   ├── data/
│   │   ├── src/main/kotlin/tv/anstelle/core/data/
│   │   │   ├── (root)     ServerRepository, ScheduleRepository, ProfileRepository,
│   │   │   │              SettingsRepository, ThemeRepository, DatabaseChanges,
│   │   │   │              ServerRemoval, WebServerController
│   │   │   ├── sync/      LibrarySyncer, SyncStatus
│   │   │   ├── channels/  ChannelGenerator, GeneratedChannelSync, LineupMatcher,
│   │   │   │              ChannelRepository, ChannelLogos, numbering and lineup choices
│   │   │   ├── guide/     GuideRepository, ProgramStrip, LongestSlot
│   │   │   ├── ondemand/  CatalogRepository, OnDemandRepository
│   │   │   ├── playback/  ChannelPlayer, OnDemandPlayer, PlaybackReporter, MediaDurations,
│   │   │   │              TrackChoices, WatchRules
│   │   │   ├── stats/     DashboardHistory, DashboardStats, ScreenTime, StatsMath, PlayClock
│   │   │   ├── backup/    BackupStore, BackupRepository, PeerTransfer, NearbyDevices, RestoreSignal
│   │   │   ├── profiles/  PinHash, Restriction
│   │   │   ├── themes/    ThemeDesign (designer choices and readability)
│   │   │   ├── work/      LibrarySyncWorker, ScheduleWorker, ScheduleCleanupWorker, DurationWorker
│   │   │   └── di/        DataModule
│   │   └── src/main/assets/channel-words.json
│   └── livetv/       LiveTvSources constants for the skipped Live TV phase
├── feature/
│   ├── guide/        GuideScreen, GuideGrid, GuideGridState, GuideAutoPreview
│   ├── player/       PlayerScreen (channels), OnDemandPlayerScreen, PlayerChrome, PlayerPanel
│   ├── ondemand/     OnDemandScreen, ChannelScreen, CatalogScreen, TitleScreen, EpisodeScreen
│   ├── profiles/     ProfilePickerScreen, ProfileEditScreen
│   ├── settings/     SettingsScreen, BackupScreen, WebControlScreen, AdminOnly,
│   │                 appearance/ (themes, designer), channels/, playback/, servers/
│   └── dashboard/    DashboardScreen, ScreenTimeChart
├── webserver/
│   └── src/main/kotlin/tv/anstelle/webserver/
│       ├── AnstelleServer.kt, ServerModule.kt, WebServerService.kt, WebServerControllerImpl.kt
│       ├── routes/   ChannelRoutes, ServerRoutes, ThemeRoutes, DeviceRoutes, Calls
│       ├── api/      ChannelsApi, ServersApi, ThemesApi, BackupApi, DeviceApi, ServerSignIns, Dtos
│       ├── pairing/  PairingManager, PairingGate, PairedClientStore, Tokens
│       ├── net/      LanGuard, NetworkAddresses
│       ├── http/     StaticFiles, AssetSource
│       └── di/       WebServerModule
├── webui/
│   ├── src/routes/   backup, channels (custom, library, groups, new, [id], [id]/lineup),
│   │                 ondemand, pair, servers (add, [id]), themes ([id]), web
│   ├── src/lib/      api.ts, session.svelte.ts, theme.svelte.ts, live.svelte.ts, components/
│   ├── vite.config.ts  SvelteKit config, dev proxy
│   └── package.json
├── baselineprofile/  BaselineProfileGenerator
├── benchmark/        Startup, guide scroll, channel switch and dashboard macrobenchmarks
├── tools/
│   ├── themes/generate_themes.py  Builds the 16 theme JSON files and checks contrast
│   └── brand/generate_brand.py    Builds the launcher icon and TV banner (needs Pillow)
├── docs/             This documentation, performance reports, mockups, README screenshots
├── gradle/           libs.versions.toml, wrapper, gradle-daemon-jvm.properties (JDK 17)
├── compose_stability.conf
├── product.md        Product decisions and per-feature notes (source of truth)
├── DESIGN.md         Visual system: palette, type, components
└── LICENSE           GNU GPL v3.0
```

## Layers

| Layer | Modules | Depends on |
|---|---|---|
| Models and utilities | `core:model`, `core:common` | Nothing app-specific. Plain JVM. |
| Engine | `core:scheduler` | `core:model`. Plain JVM. |
| Storage | `core:database` | `core:model` |
| Remote | `core:jellyfin` | `core:model`, `core:common`, `core:database` (its mappers write entities) |
| Playback engine | `core:player` | `core:jellyfin`, `core:database`, Media3 |
| Design system | `core:theme` | `core:model` |
| Domain and repositories | `core:data` | All of the above |
| UI | `feature:*` | `core:data`, `core:theme` (added by `anstelle.android.feature`) |
| Delivery | `app`, `webserver`, `webui` | Everything they wire together |

The plain JVM modules (`core:model`, `core:common`, `core:scheduler`, `core:livetv`) use `anstelle.jvm.library`, so their tests run without Android.

## Folder rules

- `core:model` holds plain data and pure rules (`TrackRules`, `ProfileRights`, `RatingLimits`), with no Android and no I/O. Enums that get stored implement `CodedEnum` with a fixed `code`.
- `core:scheduler` must stay deterministic: no clock, no I/O, and no randomness except `SplitMix64` seeded from the engine state. Callers pass the time in.
- `core:database` holds entities, DAOs and queries. Business rules go in `core:data`. A new table or column that holds user data also has to go into the backup (`BackupStore`, `Backup`).
- `core:jellyfin` is the only module that calls the Jellyfin SDK. SDK dates are converted only through `SdkTime.kt`.
- `core:data` holds repositories and domain logic. Decisions go in pure functions with unit tests, not inside repositories or view models.
- `core:theme` holds shared components and theme plumbing. Colors are `ThemeColors` roles, never literal hues, and `FocusSurface` is the only clickable surface for both D-pad and touch.
- `feature:*` holds screens and view models. They reach data through `core:data`, never a DAO or the SDK directly. Only `app` wires features into navigation.
- `webserver` handles HTTP only. Rules and wording come from `core:data`, so the API and the app's settings screens give the same answers and the same messages.
- `webui` is admin management only: nothing plays and there are no per-profile pages. Styles are Tailwind utilities over theme-role tokens (`bg-accent`, `text-text-muted`) with Tailwind's own palette turned off.
- `app/src/main/assets/webui` is regenerated by the `copyWebUi` task on every build. Do not edit or commit it.
- `core/database/schemas` is generated by Room. Commit schema changes together with the code that caused them.
