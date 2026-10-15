# Live TV performance and Jellyfin reporting verification

Target: B866V2F, Android 14, approximately 2 GB RAM. The physical box is not connected through ADB. Device results below come from the TV AVD (API 36, 1,538,789,376 bytes physical RAM) and phone AVD.

## Changes

- Catalog reads, startup scheduling and missing-schedule tuning apply saved duration corrections locally. Remote measurements run sequentially in unique background work, outside scheduling and sync locks, with a 20-second pass budget. Failed file identities wait 24 hours; changed source identities are eligible immediately. Measurements check the current identity before being saved. Successful measurements invalidate schedules in a batch, including when later probes are cancelled; subsequent maintenance repairs interrupted batches.
- Startup requests coalesce independently from explicit full updates after sync. Scheduler revision and timezone determine whether a full local pass is needed. The deterministic eight-day horizon remains unchanged.
- Current-program preparation precedes upcoming stream and banner work. Prepared standby streams are validated against current profile, account, preferences, item and schedule. Tuning cancels obsolete preparation and HLS retries. First-frame and playing callbacks both trigger standby maintenance, so their ordering cannot postpone prewarming until the 20-second timer.
- Standby players are created lazily and released when idle. Low-memory detection includes Android's low-RAM flag, physical RAM at most 2 GiB, and heap class at most 128 MB. These devices use a 32 MB active buffer target, a 2-second/4 MB standby target, no standby video decoding, and a 16 MB retained-font renewal threshold. Normal-device budgets remain unchanged. Targets bound loading decisions; an individual extractor read can overshoot the duration target.
- Font preparation retains authored ASS fonts before playback, uses one cancellable three-second download deadline, and leaves directory trimming to housekeeping. Stream request-to-headers tracing contains no URLs or credentials and separates connection/server response time from extraction and decoder preparation.

## Measured results

Both builds used valid TV credentials and the same updated benchmark driver. The unchanged baseline is commit `18ae28b`. The repeated baseline ran immediately after the candidate, with builds idle. All 15 switches rendered in each build.

| Measurement | Repeated baseline | Candidate |
| --- | ---: | ---: |
| Cold preview median, 3 runs | 3.934 s | 4.691 s |
| Cold preview maximum | 4.108 s | 4.814 s |
| Channel-switch median, 15 switches | 1.001 s | 1.312 s |
| Channel-switch P95, nearest rank | 3.550 s | 3.041 s |
| App CPU per 50-second surf pass, median | 41.0 CPU-seconds | 48.6 CPU-seconds |
| Peak sampled app RSS across surf passes | 327.0 MiB | 319.5 MiB |
| Median of each pass's mean app swap | 111.7 MiB | 57.6 MiB |

These results **do not demonstrate a general tuning speedup**. The cold runs played different files, confirmed against the stored schedules, so their latencies are not a paired comparison. For 12 candidate switches with the same airing present in the baseline, the candidate median was 1.026 s versus 0.801 s for the corresponding baseline airing medians. Positions and cache state still differed. Disabling standby video on low-memory devices exchanges an already-created decoder for lower resource use; the emulator supports more concurrent video decoding than a single-decoder box. Smaller configured buffers are proven; a CPU reduction is not proven.

Cold preview remains roughly 4–5 seconds on this AVD. In inspected direct-play traces, request-to-response-headers wait totaled roughly 0.2–0.4 seconds; container loading/parsing, decoder setup and GC account for much of the remaining time. Header wait does not include transferring response bodies. These direct-play samples contain no server transcoding time. HLS startup time must be attributed separately when measuring the physical box.

Current UI initial display measured 0.983 s median over ten cold starts. This is not scheduler completion or first video, and no UI startup speedup is claimed. The startup change removes remote duration waits from scheduling by construction and regression tests; these runs do not measure the worst case with an uncached, slow duration probe.

Playback timings precede the final cancellation-persistence change in background maintenance. Playback tuning, buffer and font behavior did not change afterward. Reproduction artifacts: `build/verified-measurements.log`, `build/repeated-baseline-measurements.log`, their trace directories, and `build/performance-analysis-summary.json`. Earlier overnight baseline results and the temporarily broken prewarm candidate are not used to claim an improvement.

## History contract

The active player explicitly selects the source. On Demand passes `channelId = null` even when opened from Live TV. A carried channel ID cannot select the history mode.

| Mode | Resume data | Watched status | Now Playing |
| --- | --- | --- | --- |
| Off | No writes | Unchanged | None |
| Status | Unchanged | Flag-only update on qualifying completion | None |
| Progress | Every five seconds and lifecycle edges | Played and PlayCount unchanged | Generated metadata session |
| Both | Every five seconds and lifecycle edges | Completion updates enabled | Normal library session |

Live TV requires 30 seconds of actual playback, excluding pauses and buffering; On Demand starts from its first frame. Pause, resume, seek and stop report immediately. Completion includes natural endings with incorrect runtime metadata and survives pending updates, seeks and reconnects.

Progress saves resume data against the real item separately from Now Playing. Session reports use an empty top-level library ID and a generated metadata ID, including cleanup, so neither ordinary session reports nor idle cleanup can update the real item's watched state. The presence carries title and time without identifying a real library item.

Reporting is ordered and coalesced outside playback work, with bounded transient retry. A failed credential is invalidated only if it still matches the failed user, device and token. Pending reports resume after authentication as the same user. Delayed older sessions cannot rewind newer resume data or clear a newer player's Now Playing entry. Successful history changes and failed attempts have separate events; hidden catalogs invalidate cached history without reading the catalog every five seconds.

## Verification and limits

All 323 targeted unit checks pass: data 230, player 3, Jellyfin 19, scheduler 62, guide 9, with no failures or skips. Data/player/Jellyfin debug lint and debug, instrumentation and release-derived benchmark APK builds pass. Unit coverage includes scheduling current, absent, expired and invalidated schedules; duration persistence, cooldown, replacement and cancellation; low-memory selection; all four history modes in both sources; the 29.999/30-second boundary; buffering and clock changes; natural completion; coalescing, retry and reconnect; and opposing Live TV/On Demand modes.

The native player suite passes on both emulators: playlist preloading without a boundary rebuffer, ASS standby handoff with one video decoder, and lazy standby cleanup. Measured ASS handoff was 333 ms on TV and 271 ms on phone with builds idle. An earlier 2,055 ms failure occurred during concurrent R8 builds and was excluded after this repeat.

Authenticated tests against Jellyfin confirm live resume and Now Playing updates, completion, unchanged Played/PlayCount, and both simulated and actual idle cleanup. Actual server cleanup occurred after 560,447 ms with Played/PlayCount unchanged. The final debug APK also passed sign-in health, normal Progress reporting, and visible-catalog recovery checks: Continue Watching, Next Up and episode-count badges matched the server. Server and profile credentials both validate as the same existing user; no reconnect was needed in the final run. Phone playback checks require no server authentication; authenticated phone history remains unverified.

Release-derived macrobenchmarks pin the same starting channel, require all five switches to render, preserve the two-second guide auto-preview delay, and wait long enough to include slow HLS starts. `Tune` measures tune request to rendered first frame; it excludes the deliberate guide delay. UI initial display and scheduler completion are different measurements. Background work can restart a killed process, so the startup driver force-stops before each explicit cold launch.

Builds run separately from timed playback. Emulator timing, moving live schedules, file/container differences, server caches and transcoding make these observations unsuitable as a physical-device speedup guarantee. Earlier runs with failed channel pinning or an emulator exit are excluded. The B866V2F still needs cold/warm tuning, preview/fullscreen handoff, sustained buffering, CPU and memory measurements under its actual network and decoder limits. Real HLS and subtitle combinations beyond the exercised samples also need that check.

Raw logs, benchmark JSON and Perfetto traces remain under ignored `build/`; they may contain private library information and are not committed.
