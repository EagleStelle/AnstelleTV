# AnstelleTV — product and build context

AnstelleTV turns a Jellyfin library into 24/7 retro cable TV on Android phones, tablets, Android TV / Google TV and Fire TV (one APK, touch and D-pad). There is no backend. All data lives in on-device SQLite (Room). A LAN web UI (SvelteKit) is served by the app itself for setup and in-browser watching.

The app is native: Kotlin + Jetpack Compose (Compose for TV on TV, Material 3 on phones), with ExoPlayer for playback. There is no WebView. The web UI is for browsers on other devices on the LAN.

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | Scaffold: modules, catalog, Room schema, Hilt, placeholder screens with D-pad focus, Ktor server with pairing, SvelteKit shell, baseline profile + benchmark modules | Done |
| 2 | Jellyfin login (Quick Connect, password), multiple servers, incremental sync, media segments | Next |
| 3–11 | Scheduler, channels, playback, guide, live TV, profiles/themes, dashboard/backup, web UI parity, performance | Planned |

The full plan, with key designs for every phase, lives in `C:\Users\Impulsor\.claude\plans\the-name-of-the-kind-shell.md`.

## Identity

- App name: **AnstelleTV**, from the folder name. It replaces the spec's "RetroCast" placeholder.
- applicationId and root package: `tv.anstelle.app`. Modules use `tv.anstelle.core.*`, `tv.anstelle.feature.*` and `tv.anstelle.webserver`.
- Brand: an A-frame antenna (the letter A) on a retro TV with fading scanlines.
  - Colors: `#0E1116` background, `#4FB3FF` blue, `#FFB547` amber.
  - Assets are regenerated with `python tools/brand/generate_brand.py`.
- Testing uses the owner's private Jellyfin 12.1.0 server. Its address and contents are deliberately kept out of this repository.

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
| `core/scheduler` | Pure JVM schedule engine (so far: `SplitMix64`, `SlotMath`) |
| `core/livetv` | M3U / XMLTV / HDHomeRun (Phase 7) |
| `core/database` | Room DB with all tables; exported schemas in `core/database/schemas` |
| `core/jellyfin` | The shared `OkHttpClient` and the `Jellyfin` SDK instance (through `OkHttpFactory`) |
| `core/player` | `PlayerFactory` (ExoPlayer, OkHttp data source, FFmpeg audio fallback) |
| `core/theme` | `ThemeSpec` JSON model, `AnstelleTheme` (Material 3 + TV), shared focusable components |
| `core/data` | Hilt modules, repositories, `DatabaseChanges`, `WebServerController`, workers |
| `feature/*` | Guide, player, on-demand, profiles, settings, dashboard (placeholders plus the log viewer and web-control settings) |
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

- **Tokens:** `EncryptedSharedPreferences` is deprecated, so Jellyfin tokens will be wrapped with an Android Keystore AES-GCM key (Phase 2).
- **Android 17 (API 37):** `ACCESS_LOCAL_NETWORK` is declared and requested at runtime.
- **Foreground service:** the web server runs as a `specialUse` foreground service.
- **FFmpeg decoder:** `org.jellyfin.media3:media3-ffmpeg-decoder` latest is `1.9.0+1`, built for Media3 1.9. It is used with Media3 1.11.1 and is gated by a decode smoke test in Phase 5. If that test fails, Media3 gets pinned to 1.9.x.
- **Database migrations:** debug builds fall back to destructive migration while the schema moves between phases. Release builds need real migrations before the first public release.
- **Cleartext HTTP** is allowed app-wide, because LAN Jellyfin, HDHomeRun and M3U sources use plain HTTP on arbitrary IPs.
- **Gradle warning:** builds print `Configuration.setVisible` deprecation warnings that come from third-party Gradle plugins. They are harmless until Gradle 11.

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
