# Live TV switching verification — 2026-10-16

Target: B866V2F, Android 14, approximately 2 GB RAM. Physical ADB access is unavailable. Device checks use the existing TV and phone emulators; these results do not establish physical-box performance.

## Implementation

- EPG automatic preview waits 500 ms, down from 2 seconds. Changing selection, opening a dialog, stopping the screen or disposing it cancels the pending request. Returning starts a fresh wait; the callback checks the latest airing, dialog and visibility state. OK and full-screen Channel Up/Down remain immediate. Focus prewarming retains its 1-second debounce.
- Channel players use a 250 ms initial media-buffer threshold. On-demand playback retains 1 second. Both retain the 2-second recovery threshold and existing ongoing buffer budgets. These are media durations, not promised wall-clock startup times.
- Cold tuning stops unrelated standby media loading. Warm promotion retains its validated media and existing profile/account checks. The active player retains the existing media-replacement path so an unnecessary stop does not discard decoder reuse. Standby track defaults, language preferences, mute and video limits are applied together.
- Playback listeners belong to one attachment and tune generation. Retired listeners cannot update another tune or start its history session. Cancellation checks follow preparation waits; banner lookups finish before state mutations. Stop, errors, off-air and restricted outcomes close their traces.
- Successful tunes mark `TuneRendered`. The benchmark selects only attempts containing that marker and requires every requested switch to render. A closed, canceled attempt cannot become a fast successful sample.

## Focused verification

The unit, native and app results in this section were recorded before the DRY follow-up described below.

All 265 unit checks pass without failures or skips: player 3, data 238, guide 12, theme 12. Guide coverage includes the 499/500 ms boundary, no repeated preview on a stable selection, stop/resume, selection replacement, dialog cancellation and disposal. Existing shared watch-action tests pass.

All six native player checks pass on each emulator. They cover the initial/recovery thresholds, unchanged on-demand buffering, atomic muted standby defaults, canceled traces, lazy standby cleanup, gapless playlists and ASS handover with one video decoder.

The opt-in app test passes against the configured TV library. It renders after rapid Up/Up/Down, ignores injected callbacks from the retired listener, recovers over real HLS after an injected active format error, and ignores callbacks after stop. Manual TV navigation confirms preview/full-screen/guide handover and automatic preview on the next selected row.

Debug and release-derived benchmark APK builds pass. Player, data and guide debug lint pass. An initial threshold-test failure used an invalid empty Media3 timeline; the corrected HTTP-media fixture passes on both emulators.

## Matched media comparison

The baseline is `ed7fd82`, built from an isolated archive. Both builds run the same `ChannelTuneBenchmark` against the same loopback MP4 and ASS/MKV assets, at position 1,500 ms. Each build completes 12 cold and 12 prepared-standby tunes. Standby video is disabled. Builds and other device tests are idle during measurement.

| Measurement | Baseline | Candidate |
| --- | ---: | ---: |
| Cold first-frame median | 552.5 ms | 522.0 ms |
| Cold first-frame P95, nearest rank | 760 ms | 846 ms |
| Warm first-frame median | 360.0 ms | 361.5 ms |
| Warm first-frame P95, nearest rank | 416 ms | 413 ms |
| Process CPU across the pass | 22.374 s | 22.096 s |
| Peak sampled process PSS | 90.55 MiB | 90.53 MiB |
| Rebuffers after first frame | 0 | 0 |

These short, fast-loopback samples show no consistent general first-frame speedup. The cold median improves while its tail worsens, and warm timing is essentially unchanged. The fixture exercises the player pool and buffering policy; it does not measure the full app, a moving live schedule or server startup. PSS is the instrumentation process's sampled memory, not full-app RSS or a hard allocation bound.

The EPG change removes 1.5 seconds of deliberate waiting before a preview request. Pauses of 500–749 ms can now start streams that a 750 ms delay would skip, so actual browsing can create more requests. Cancellation protects obsolete requests; it does not make parsing and network work free.

## Release-derived full-screen surfing

The final release-derived APK passes three five-switch runs against the configured TV library, starting each pass at channel 1000. All 15 requests render; the metric requires five successful first-frame markers per pass. The benchmark reports a 1,126.3 ms median and 1,812.3 ms P95, with a maximum of 1,842.9 ms. Builds and other device tests are idle during measurement.

These are final-candidate observations, not a paired full-app baseline comparison: the live airing positions and server cache state move. They must not be compared directly with the older report's different files and positions. `Tune` begins when playback is requested, so these measurements exclude the deliberate EPG focus delay. Physical-box tuning and sustained playback still need verification on the B866V2F.

Raw logs, JSON, UI snapshots, baseline sources and traces remain under ignored `build/`. They can contain private library metadata and are not committed. Reproduction artifacts include `live-tv-comparison.json`, `live-tv-*-samples.log`, `live-tv-native-emulator-*.log`, `live-tv-final-callbacks.log`, and the final build/check logs.
The final macrobenchmark log is `live-tv-final-release-surf.log`; its three Perfetto traces are in `live-tv-final-traces/`.

## DRY follow-up

Active and standby playback now share `PooledPlayer.resetTrackParameters`, which owns override clearing, enabled track types and preferred audio languages. Each caller retains its original preference and volume setter order. Standby still pauses and mutes before updating preferences, and its existing memory/decoder limit reaches the single parameter update without temporarily enabling video.

Guide prewarm cancellation has one owner, and opening the player, tuning a preview and stopping the preview share cancellation of both pending jobs. The calls keep their original position relative to state changes and tuning. Trace names now live in `core/common/TuneTraceNames`; playback retains its existing constant aliases, and the benchmark reads the same compile-time constants through a dependency on the existing common module. The 500 ms preview delay, 1-second prewarm delay and buffer budgets are unchanged.

No compilation was run, as requested. Ten non-compiling source/SQL checks pass: expanding the extracted helpers reproduces the original active/standby operation sequences and guide cancellation order; resolving trace constants preserves the trace implementation, SQL and measurements. The SQL returns the same five samples from each of the three recorded Perfetto traces, all 15 sample values unchanged. Synthetic SQL cases retain process and time-window filtering and reject attempts without a valid rendered marker. `git diff --check` passes.

These checks validate extraction and query equivalence, not Android execution of the edited Kotlin. Source review found no added allocations, asynchronous work or media operations in the playback path; the track reset still uses one builder and one final assignment. The earlier compiled tests and timings do not validate this follow-up, and physical-device performance remains unverified. The local check script and its input snapshots are under ignored `build/dry-refactor/`.
