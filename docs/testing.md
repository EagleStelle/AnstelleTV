# Testing

The project has three kinds of tests: JVM unit tests for logic, instrumented tests on a device or emulator for Room, decoders and app flows, and macrobenchmarks for performance. There is no coverage tooling and no CI configuration in the repository; checks run locally.

## Running tests

On Windows, use `.\gradlew` in place of `./gradlew`.

All unit tests (JVM modules use `test`, Android modules use `testDebugUnitTest`):

```sh
./gradlew testDebugUnitTest :core:model:test :core:common:test :core:scheduler:test :core:livetv:test
```

One module, class or method:

```sh
./gradlew :core:scheduler:test --tests "tv.anstelle.core.scheduler.ScheduleEngineTest"
./gradlew :core:data:testDebugUnitTest --tests "*StatsMathTest"
./gradlew :core:data:testDebugUnitTest --tests "*GeneratedChannelSyncTest.someMethod"
```

Instrumented tests need a running device or emulator:

```sh
./gradlew :core:database:connectedDebugAndroidTest   # DAO queries
./gradlew :core:player:connectedDebugAndroidTest     # FFmpeg decoder, program transitions
./gradlew :core:data:connectedDebugAndroidTest       # backup round trip, profiles, seeking
./gradlew :app:connectedDebugAndroidTest             # app flows; most need a signed-in device
```

Lint and the web UI's type check:

```sh
./gradlew lintDebug        # library modules are clean; :app keeps two known warnings
cd webui && pnpm check     # svelte-check
```

## Unit tests

Unit tests use JUnit 4 and `kotlin-test`, which the convention plugins add to every module, plus `kotlinx-coroutines-test` where needed, Mockito in `feature:guide`, and the Ktor test host in `webserver`. They cover the pure logic that the rest of the app leans on:

| Module | What is tested |
|---|---|
| `core:scheduler` | `ScheduleEngineTest` (back-to-back airings, playback orders, repeats, seasons and dayparts, resumable state: extending in pieces equals generating at once), `NowPlayingTest`, `InputsTest`, `SplitMix64Test`, `EnginePerformanceTest` |
| `core:model` | Coded enum stability, language matching, track selection rules, profile rights and access, media segments, progress, and `ThemeContrastTest`, which checks every shipped theme's contrast |
| `core:jellyfin` | Item mapping, rating scores, library rules, playstate, progress-only reporting, the library source, using anonymized JSON fixtures in `src/test/resources/fixtures` |
| `core:data` | Channel generation, numbering, groups and generated-channel sync; the lineup matcher (including 50,000 items in under 50 ms); schedule repository and updater; guide strips; playback reporting, watch rules, program lengths, durations, surf neighbours, stream facts, track labels and watch choices; dashboard history, screen time and stats math; backup format, sign-ins and peer transfer; server removal; PIN hashing; theme design; logo margins |
| `core:player` | `StreamPlannerTest` (direct play or HLS), `PlaybackBudgetTest` (buffer limits per device class) |
| `core:theme` | Theme spec parsing, watch actions |
| `feature:guide` | Grid state, auto preview, returning to the guide |
| `feature:player` | Player panel behavior |
| `webserver` | `LanGuardTest` (allowed address ranges), `PairingManagerTest` (PINs, expiry, rate limits), `ServerSignInsTest`, `ServerRoutesTest` |

Route tests run the real Ktor module in the test host with one API area faked, which is why each area has its own data source interface in `webserver/api`.

Time-sensitive tests pin `ZoneId.of("Asia/Manila")`, a zone far from UTC, so a UTC/local mix-up fails on any machine. Production code reads the time from `TimeSource`, so a test can pass a fixed one (`TimeSource { 1000L }`).

JVM tests that touch Jellyfin SDK code need `slf4j-nop` on the test runtime classpath, because the SDK logs through kotlin-logging.

## Instrumented tests

| Module | Tests | Needs |
|---|---|---|
| `core:database` | `WatchEventDaoTest` | Any device |
| `core:player` | `FfmpegDecoderTest` decodes AC3, E-AC3, DTS and TrueHD samples; `ProgramTransitionTest` covers back-to-back items and font caching, with sample media in `src/androidTest/assets` | Any device |
| `core:data` | `BackupRoundTripTest`, `ProfileLifecycleTest`, `ProgramSeekTest` | Any device |
| `feature:guide` | `GuideGridReturnTest` | Any device |
| `feature:ondemand` | `TvNavigationTest` | Any device |
| `app` | Catalog loading, watch history, Live TV switching, media durations, progress, shared progress, on-demand navigation | A device signed in to a Jellyfin server; some are opt-in |

`FfmpegDecoderTest` also guards a dependency decision: the Jellyfin FFmpeg extension (`1.9.0+1`) is built for Media3 1.9, and the app uses Media3 1.11.1. If a Media3 upgrade breaks that test, pin Media3 to the extension's version.

Tests that need a real server skip themselves (`assumeTrue`) when the device has no profiles or no signed-in server. Several app tests run only when asked, through instrumentation arguments:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.verifyLiveTv=true
```

| Argument | Test |
|---|---|
| `verifyLiveTv=true` | `LiveTvSwitchTest` (needs exactly three configured channels) |
| `verifyServerTimeout=true` | `ProgressPresenceTest` |
| `repairOwnerHistory=true` | `HistoryRecoveryTest` |
| `durationItemId=<id>`, `durationChannelNumber=<n>` | `MediaDurationTest` |

`gradle.properties` sets `android.injected.androidTest.leaveApksInstalledAfterRun=true`, so connected tests leave the app installed with its sign-ins and library.

## Benchmarks and the baseline profile

`:benchmark` holds macrobenchmarks that run against a release-like build:

| Benchmark | Measures |
|---|---|
| `StartupBenchmark` | Cold start |
| `GuideScrollBenchmark` | Guide scrolling frame timing (`FrameTimingMetric`) |
| `ChannelSwitchBenchmark` | Surfing: the `Tune` trace section, from key press to first frame |
| `DashboardBenchmark` | The dashboard's first open (`Stats` trace section) |

```sh
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest
# On an emulator:
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

They run on the device's own library and server, since frames need a real stream; there is no fixture seeder. Sign in and let the library sync before running them.

The baseline profile covers start, the guide, tuning and surfing, On Demand and the dashboard:

```sh
./gradlew :app:generateBaselineProfile
```

It needs a device signed in to a server with channels, and writes to `app/src/release/generated/baselineProfiles`. Benchmark and baseline-profile builds are signed with the debug key, so they install over a debug install and keep its data.

Debug builds also log missed frame deadlines every 5 seconds of drawing (`adb logcat -s Anstelle/Jank`), and the dashboard logs its query times (`adb logcat -s Anstelle/Stats`). Results of earlier performance passes are in `docs/performance-2026-10-15.md` and `docs/performance-2026-10-16.md`.
