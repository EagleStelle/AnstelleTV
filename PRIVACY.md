# AnstelleTV Privacy Policy

Effective date: October 28, 2026

AnstelleTV is an open-source Android app published by Eagle Stelle. It plays media from a Jellyfin server that you choose and run or have access to. This policy explains what the app handles and where that information goes.

## What the developer collects

The developer collects no data. AnstelleTV has no analytics, advertising, crash reporting or tracking of any kind, and it never sends data to the developer or to any third party. The developer runs no server that the app talks to.

## What the app sends to your Jellyfin server

The app connects to the Jellyfin server address you enter and sends that server:

- Your Jellyfin sign-in (password or Quick Connect) to obtain an access token
- Requests for library information, artwork and media streams
- Playback progress and watched status, so resume points stay in sync with your other Jellyfin apps

That server is operated by you or by whoever administers it, not by the developer. Its handling of your data is governed by its administrator and by Jellyfin's own software. If the server address uses plain `http://`, this traffic is not encrypted, so use `https://` when the server is reachable outside your home network.

## What stays on your device

The app stores the following only on your device:

- Server addresses, Jellyfin user names and IDs, and library metadata
- Channels, schedules, settings and themes
- Profiles, including a salted hash of each profile PIN (never the PIN itself)
- A per-profile history of what was watched and when, used for the Dashboard screen

Jellyfin access tokens are encrypted with a key held in the Android Keystore. Android's cloud backup is turned off for the app.

## Local network features

If you turn on Settings > Web control, the app runs a web server on your local network so that a browser on the same network can manage it. A browser must be paired using a PIN shown on your TV or phone before it can access anything. Turning Web control off stops the server.

Backups you export to a file, or copy directly to another AnstelleTV device on your network, contain your channels, settings, profiles and PIN hashes. They never contain Jellyfin access tokens.

## Retention and deletion

Data stays on your device until you remove it. Signing out of a server deletes its access token. Clearing the app's storage in Android settings, or uninstalling the app, deletes everything the app stored. Data kept by your Jellyfin server, such as watch history, can be managed or deleted from that server.

## Children

AnstelleTV is not directed at children. It offers profile PINs and parental rating limits so that adults can control what younger household members see.

## Changes

If this policy changes, the updated version will be published at this address with a new effective date.

## Contact

Questions about this policy can be raised at https://github.com/EagleStelle/AnstelleTV/issues.
