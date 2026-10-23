# Usage guide

This guide assumes the app is installed (see [installation.md](installation.md)). The same flows work with a D-pad remote and with touch; where they differ, both are given.

## First run

1. A new install opens on *Add server*. Enter the server address: an IP, a host name or a full URL all work, and the app tries HTTP and HTTPS on ports 8096 and 8920 and keeps the best answer.
2. Sign in with Quick Connect (enter the code shown in any Jellyfin app signed in as that user) or with a username and password.
3. The user you signed in as becomes the first profile, the owner. The library starts syncing in the background.
4. Live TV fills with channels once the first sync finishes. Later syncs fetch only what changed.

To move an existing setup to a new device instead, pick *Restore backup* on the same screen (see [Backups](#backups-and-moving-to-another-device)).

## Channels the app makes

After each sync the app builds channels from the library's metadata in four groups:

| Group | Numbers from | Example names |
|---|---|---|
| Productions | 1000 | One channel per studio or network |
| Decades | 2000 | "90s TV", "80s Movies", "2010s Anime" |
| Genres | 3000 | Retro block names per genre, genre pair and genre plus anime or cartoons |
| Themes | 4000 | One channel per tag, such as "Isekai" or "Based on Manga" |

Inside a group, channels are numbered A to Z. You can change each group's start number in Settings > Live TV > Groups. Library channels follow the library: they can be hidden or made favorites, and anything else (lineup, name, logo) needs a channel you build yourself.

## Watching Live TV

Pick a channel from the guide, or press a channel's number on the remote. The program on now plays from wherever the schedule says it is.

| Action | Remote | Phone or tablet |
|---|---|---|
| Next or previous channel | Up and down, or the channel keys | Swipe up or down |
| Go to a channel number | Type the digits; it tunes 2 s after the last one | |
| Seek 10 s back or 30 s ahead | Left and right, or rewind and fast-forward | Swipe left or right |
| Show the banner | Any tune or seek shows it | Tap |
| Use the banner's keys | OK, Info or Menu moves focus into them | Tap a key |
| Start the program over | *Restart* in the banner | *Restart* in the banner |
| Audio or subtitle track | Track keys at the banner's left | Same |

Seeking moves the channel's whole schedule with you: whatever comes next on that channel starts later or earlier by the same amount, and the guide shows the change. Picking an audio or subtitle track changes only the program that is on now; the next program starts on your profile's defaults again.

The banner's tech line shows the server, DIRECT PLAY or TRANSCODING, and the file's quality, video and audio.

From the channel's *On demand* key you can switch to *Watch on Demand* (from the start) or *Resume on Demand* (from where the channel is) once the program is a minute in.

## The guide

The guide shows a preview window playing the selected channel, the program's details, group tabs (all channels, favorites, then each group) and a 7-day grid.

- Move through the grid with the D-pad, or drag and fling on touch. Up and down keep the time, so you land on whatever airs at that moment.
- OK or a tap on a program airing now plays it in the preview window. Press again to go full screen.
- Hold OK, press Menu, or press OK on a later program to open its actions: *Watch live*, *Start over*, *Watch on demand* for what is on now; *Watch now* for anything aired or still to come; and *Add show to a channel* or *Add movie to a channel*.

## On demand

On Demand is organized by channel. The Home tab has a banner of channels airing something, then one row per guide group, each channel a tile with what it airs now. Movies and Series tabs and Search browse the library directly, with Continue watching and Next up rows.

A channel's page is about the program it airs now. From there you can watch live, start over, watch on demand from the start or resume from where the channel is. A series lists every episode by season; OK on an episode plays it from its Jellyfin resume point.

The on-demand player:

- OK pauses and brings up the banner. Left and right jump 10 s back and 30 s ahead.
- The previous and next keys step through the show's episodes.
- *Episodes* at the top shows the season's episodes in the banner, and *On Demand* at the top goes back to the channel page you came from.
- Intros, outros, recaps, previews and commercials can be skipped if the server has media segments for them (Jellyfin 10.10 or later, for example from the Intro Skipper plugin). Set each kind to *Ask to skip*, *Skip* or *Play* in Settings > Playback > Skipping.

## Profiles

Each profile is a Jellyfin user. On launch, *Who's watching?* lists the profiles; *Add a profile* signs in another Jellyfin user (Quick Connect or password), and *Manage profiles* edits them.

| Role | Can do |
|---|---|
| Owner | Everything, including making admins, transferring ownership and restoring backups. There is exactly one. |
| Admin | App-wide settings (servers, channels, web control, backup) and editing members' profiles. |
| Member | Their own profile, playback, watch history and theme. App-wide settings are not shown at all. |

- A PIN (4 digits) is asked when switching to a protected profile, always at launch, and before editing your own profile. Five wrong PINs block PIN checks for 30 seconds.
- The owner or an admin can give a member a parental rating, chosen from the ratings the server and library actually use. Programs over the limit still air on their channel but show as AGE RESTRICTED until the restricted stretch ends.
- Programs a profile's Jellyfin user may not see show as *No access* the same way. A channel with nothing left the profile can watch drops out of its guide.
- Channels, groups and numbers are shared by all profiles. Theme, audio and subtitle languages, subtitle style, skipping, watch history settings and the dashboard are per profile.

## Settings

| Area | What is there | Who sees it |
|---|---|---|
| Servers | Add server; per server: sync now, full sync, a switch per library, the Jellyfin user, remove | Owner, admins |
| Live TV | New channel, Groups, the custom and library channel lists | Owner, admins |
| On Demand | Banner sizes and row order | Owner, admins |
| Playback | Subtitle and audio languages in order, subtitle mode, subtitle style, skipping | Everyone |
| Watch history | What Live TV and On Demand send to Jellyfin; the dashboard switch | Everyone |
| Themes | 16 built-in themes, custom themes, the designer, import and export | Everyone |
| Web control | Turn the web server on, its address, paired browsers | Owner, admins |
| Backup | Save, restore, copy from a nearby device | Owner, admins (restore is owner only) |

### Building a channel

1. Settings > Live TV > *New channel*.
2. Give it a name and pick a group you made (create one in *Groups* first if needed).
3. Add rule sets. A rule set matches items that pass all of its rules; the channel airs what any rule set matches. Rules pick values from what the library has: type, genre, studio or network, rating, year, tag, keyword, series or movie.
4. Add series or movies to *Always include* or *Never include* as needed.
5. The reach readout shows how many hours, series, episodes and movies the lineup covers as you build it.

On the channel's page, *Series* and *Movies* set the playback order: *Shuffle*, *Episode order* or *Blocks* (2 to 4 in a row). *Once daily* under Movies keeps a movie from airing again within 24 hours while anything else on the lineup can air.

*Add to channel* in the guide or On Demand adds a show or movie to an existing channel's always-include list, or starts a new channel from it.

### Watch history

Settings > Watch history decides what each player leaves in Jellyfin: *Both*, *Status* (watched marks only), *Progress* (resume points only) or *Off*. Live TV defaults to *Status*, so channel viewing doesn't move the resume points On Demand uses. On Demand defaults to *Both*. Live TV counts a view only after 30 s of actual playback.

## Dashboard

The Dashboard tab shows the active profile's own viewing: top channels, movies, series and genres, today against the daily average, a day, week, month or year chart split into Live TV and On demand, and habits such as streaks and the longest sitting. Pick a bar to see the ranks for that hour, day or month. *Turn off* at the foot stops collecting and asks whether to keep or delete the history.

## Web admin

1. On the TV, with the owner's or an admin's profile active, turn on Settings > Web control. Allow local network access if Android asks.
2. On a computer or phone on the same network, open the address shown there (port 8765 by default).
3. Enter the 6-digit PIN the TV shows. The browser stays paired to the profile that approved it.

The web admin covers Servers, Live TV, On Demand, Themes, Web control and Backup, with the same wording as the app's settings. It wears the paired profile's theme, and changes show up on the TV immediately, and the other way round. Nothing plays in the browser, and profiles, playback languages, watch history and the dashboard stay on the TV.

It is the easiest way to upload channel logos, import theme files and save or restore backups on TV devices without a file picker.

## Backups and moving to another device

Settings > Backup:

- *Save backup* writes one JSON file (`anstelletv-<device>-<day>.json`) with servers, libraries, profiles, PIN hashes, custom themes, groups, channels with lineups and logos, settings and dashboard history. It holds no tokens.
- *Restore from file* (owner only) replaces everything on the device after one confirmation, then the app restarts. Sign-ins don't travel: servers this device is already signed in to stay signed in, and the rest need signing in again. Every server syncs in full afterwards.
- *Nearby devices* (owner only) lists other AnstelleTV devices with web control on. Pick one, enter the PIN it shows, and its backup is copied over and restored. Use *Enter address* if your network blocks discovery.

A backup file contains PIN hashes, server addresses and user names. Four-digit PINs are quick to guess from the hash, so store backup files somewhere private.

On devices without a file picker (Google TV), saving and restoring a file go through the web admin's Backup page.

## Common problems

| Symptom | Cause and fix |
|---|---|
| The TV shows no PIN when pairing, or the browser gets "switch to the owner's or an admin's profile" | A member's profile is active. Switch to the owner or an admin on the TV and pair again. |
| "Too many pairing attempts" | More than 10 pairing starts in 10 minutes, or 10 wrong PINs in an hour. Wait and try again. |
| Browser can't reach the address | Web control is off, the browser is on another network, or Android denied local network access. Turn web control on and check the address on the Web control page. |
| The web address shows a "not built" page | The APK was built without pnpm. Install pnpm or enable Corepack and rebuild. |
| A server shows "Signed out" | Its token was rejected or could not be decrypted. Open the server in Settings > Servers and sign in again. |
| A program shows AGE RESTRICTED or No access | The active profile's parental rating or Jellyfin access excludes it. An owner or admin can change the rating on the profile page; access is set on the Jellyfin server. |
| A channel says it is off air | Nothing in its lineup is eligible right now (for example a season or daypart excludes everything). It comes back at the time shown. |
| Library changes don't show up | Libraries sync every 6 hours. Use *Sync now* on the server's page, or *Full sync* to fetch everything again. Turning a library on or off takes effect at the next sync. |
| Restore from file is missing | Only the owner restores, and only on devices with a file picker. Use the web admin's Backup page. |
