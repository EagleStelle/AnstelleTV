# Technical decisions

Each entry gives the decision, the reason, and what it costs. `product.md` records many more product-level decisions and the numbers behind them.

## Everything on the device

The app has no backend. Room holds the library mirror, channels, schedules, profiles and stats, and the admin web UI is served by the app itself.

- Why: one install, no account, nothing to host or keep running. Server-based tools such as Tunarr and ErsatzTV need their own server plus an IPTV client.
- Cost: every job has to fit on a TV stick. That pushed the scheduler to be a pure engine with a small state blob, the lineup matcher to run in memory, and schedules to be kept only 8 days ahead instead of 30 (70% fewer `schedule_slot` rows).

## Channels from metadata, with no templates

`ChannelGenerator` builds channels from the genres, tags, decades and studios Jellyfin's metadata providers filled in. `channel-words.json` only provides wording.

- Why: a new user gets working channels right after signing in, and the channels follow whatever the library holds. A template list of titles or networks would go stale and wouldn't match most libraries.
- How it stays sane: thresholds (3 shows and 12 hours of airtime per channel, 8 shows per theme, a full day for movie-only channels), dropping channels that overlap 85% with one already kept, and keeping a channel that is close to the limit at three quarters of the thresholds so channels don't flicker in and out between syncs.
- Genre and decade channels never mix series with movies, or anime with cartoons. Whether a show is anime or a cartoon is decided per show from genres, tags and audio languages together; a show whose signals disagree is `unclear` and goes on no genre or decade channel.
- Library channels are locked to hide and favorite. Lineup, group, playback, name and logo belong to channels someone built, so a sync never overwrites a person's work and a person never fights a sync.

## Lineups are plain rule rows

A generated channel and a hand-built channel use the same `channel_rule` storage, matcher and scheduler.

- Why: one code path. The lineup editor, the matcher's tests and the scheduler treat every channel alike, and a unit test checks that each generated lineup matches exactly what the generator counted.
- Alternative not taken: SQL-side matching. Loading the catalog once and compiling rules in memory is simpler and matches 50,000 items in under 50 ms.

## A deterministic, clock-free scheduler

`ScheduleEngine.generate(inputs, state, until)` lives in a plain JVM module and takes time as a parameter.

- Why: schedules are reproducible from stored state, the engine runs and is tested on the plain JVM, and extending a schedule in pieces gives the same result as generating it at once (this is tested).
- `EngineState` stores cursors as positions (season, episode, id), not indexes, so new or deleted episodes don't reset a series. Shuffle decks are stored as seed and position, about 50 bytes each.
- Channel starts are staggered by moving a fresh state back to a random point in its first program, so channels don't all start at the same minute. The random point comes from the state's own generator, so the same state always gives the same start.

## Back to back, with no filler and no slot grid

Programs air for exactly their own length, and the next one starts the moment the last ends. Guide times land on odd minutes.

- Why: the product rule is no commercials, bumpers or station breaks. A half-hour grid would need filler to pad each slot.
- Cost: a bad runtime from Jellyfin or a file header (a 24-minute episode reported as 6 hours) would break a channel. `MediaDurations` and a background file probe compare each episode with the median of its season and correct outliers, and a program that ends more than 5 s off its slot moves the rest of the schedule (`ScheduleRepository.endAt`).

## Seeking moves the schedule

Seeking on a channel shifts that airing and everything after it on the channel (`ScheduleRepository.shift`).

- Why: the guide always matches what is on screen. The alternative, letting the player drift from the schedule, makes the guide wrong until the next program.
- Channels skip nothing. Intro and credit skipping exists only in the on-demand player, using Jellyfin's media segments.

## Tuning without a server call

The stream decision is made on the device from stored metadata and a `CapabilityProbe` snapshot of the device's decoders.

- Why: channel changes feel like TV, and surfing never waits on a round trip. Sync already stores container, codecs, tracks and font attachments for this purpose.
- Direct play is the default. HLS from Jellyfin is used only when the container, video codec, size or bit depth doesn't fit. Audio rarely forces a transcode because the FFmpeg extension decodes AC3, E-AC3, DTS and TrueHD.
- A file that turns out not to play directly is retried once as HLS.

## A pool of pre-warmed players

`PlayerPool` keeps one active ExoPlayer and up to two standbys aimed at the channels on either side.

- Why: with one standby in the last surfing direction, about half the presses in a mixed up and down surf tuned cold on the TV emulator. Two neighbours make both directions warm.
- Cost and limits: standbys are paused, muted and capped at 4 s / 8 MB of buffer (2 s / 4 MB on low-RAM devices). They drop to one after 10 s of rest and are released after two minutes, so a long watch uses one stream. The second standby prepares only direct-play programs, so the server never transcodes a channel nobody is watching.
- Alternative tried and dropped: keeping the previous active player as a standby. It held the whole active buffer, 55 to 90 MB more heap, for no extra warm switches.

## libass for ASS/SSA subtitles

ASS and SSA are drawn by libass (`io.github.peerless2012:ass-media`), the library Jellyfin's Android TV app uses.

- Why: anime subtitles depend on positioning, styling and karaoke that Media3's text renderer drops.
- Cost: libass holds one item at a time and never frees fonts until its player is released. ASS programs therefore don't queue behind the previous program; the standby prepares them during the last 30 s. A player whose libass holds more than 48 MB of fonts (16 MB on low-memory devices) is replaced the next time it stops.
- Font attachments, 5 to 33 MB in some files, are cached by content in `cache/fonts`. Once they are cached, `FontMatroskaExtractor` seeks past the Attachments element instead of downloading it again.

## Room with stable enum codes

Every persisted enum implements `CodedEnum` with a fixed integer `code`.

- Why: renaming or reordering an enum never corrupts stored rows or backups.
- Migrations: debug builds fall back to destructive migration while the schema changes between phases. Release builds drop only the pre-release versions listed in `AnstelleDatabase.PRE_RELEASE_VERSIONS`; real migrations start at the first public release.

## Tokens in the Keystore

Jellyfin access tokens are encrypted with an AES-256-GCM key from the Android Keystore (`KeystoreTokenStore`), and only the ciphertext is stored.

- Why: `EncryptedSharedPreferences` is deprecated. A Keystore or decrypt failure drops the token and the server shows "Signed out" instead of crashing.
- Tokens never go into a URL. The player's data source adds the `Authorization` header to every media request, HLS segments included.
- Backups never contain tokens. A restore keeps the sign-ins this device already has and signs out every other one.

## Profiles are Jellyfin users

Each profile is a Jellyfin user, signed in per server with its own device id, with an owner, admins and members.

- Why: Jellyfin already decides what each user may see, and its dashboard then shows who is watching. Sync records per profile what its user can't see, and those programs still air but are blacked out as *No access*.
- Channels stay shared by every profile; only settings like theme, languages, watch history and dashboard are per profile.
- PINs are 4 digits under PBKDF2-HMAC-SHA256 with 20,000 iterations, implemented over `Mac` because `PBKDF2WithHmacSHA256` arrived only in API 26.

## The web admin inside the app

A Ktor CIO server in a `specialUse` foreground service serves the SvelteKit SPA and an admin API.

- Why: Google TV and Fire TV have no usable file picker and a poor keyboard. A browser on the LAN can upload logos, import theme files, save backups and edit long lineups.
- Security: binds to LAN and loopback only, rejects non-local remote addresses, rejects cross-origin writes and sockets, and pairs with a short-lived PIN shown on the TV. Only a SHA-256 of the 256-bit token is stored, and every request looks it up again, so a pairing deleted any other way stops working immediately.
- Scope: admin only. Nothing plays in the browser, and there are no per-profile pages.
- Words and rules come from `core:data`, shared with the app's settings screens, so the web never disagrees with the TV.

## Web UI packaging quirks

- AAPT drops directories that start with `_`, so SvelteKit's `appDir` is `app` instead of `_app`.
- AAPT unpacks `.gz` files and strips the extension, so pre-gzipped assets use a `.gzip` suffix and are listed in `noCompress`.
- `asset-index.txt` lists every served path, so the server never walks the APK.
- SvelteKit 3 reads its configuration from `sveltekit({...})` in `vite.config.ts`, and imports use `#lib/...` with explicit extensions.

## One APK for TV and touch

The same APK serves D-pad and touch. `LocalIsTv` switches layouts, and `FocusSurface` is the one clickable surface for both.

- Why: one build to test and ship. Fire TV, Google TV and phones differ mostly in input and layout, not in features.
- Focus is a color fill with no scale or glow, which reads clearly across a room. The player acts on OK at key-up, because acting at key-down moved focus and the key-up then registered as a click.
- API 25 (Fire OS 6) lacks `RuntimeShader`, variable font axes and real blur, so scanlines are a cached bitmap shader, the Barlow fonts are bundled as static files, and the age-restricted blur falls back to a solid bar before API 31.

## Release size

- One APK per ABI plus a universal one. The Fire TV APK (`armeabi-v7a`) is 9.7 MB against 21.5 MB universal.
- The Jellyfin SDK's own R8 rules keep every API model, about 21,000 methods. Those rules are ignored (`optimization.keepRules.ignoreFrom`), and kotlinx.serialization's rules keep what the app reads. That cut dex from 11.1 MB to 8.3 MB.
- Resources are English only (`localeFilters`).
