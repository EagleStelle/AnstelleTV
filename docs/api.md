# Web API

The app serves an HTTP API and a WebSocket from the device itself, for the SvelteKit admin UI and for other AnstelleTV devices copying a backup. It is a private API for those two clients. Routes are in `webserver/src/main/kotlin/tv/anstelle/webserver/routes`, request and response types in `api/Dtos.kt`.

## Basics

- Base URL: `http://<device-address>:8765` (8765 is the default port). The address appears on Settings > Web control on the device.
- The server runs only while web control is turned on, binds only to LAN IPv4 addresses and loopback, and answers 403 `forbidden` to any client outside RFC 1918, link-local, ULA and loopback ranges.
- Bodies are JSON (`application/json`) unless noted. Times are UTC epoch milliseconds (`...UtcMs`).
- Requests other than GET, and every `/ws` upgrade, must be same-origin: if an `Origin` header is present it must match `Host`, else the answer is 403 `bad_origin`. Clients that send no `Origin` (curl, another device) pass.

## Authentication

Every route except `/api/health`, `/api/pair/start` and `/api/pair/confirm` needs a paired token, sent as either:

- the `anstelle_token` cookie (HttpOnly, SameSite=Strict, set by `/api/pair/confirm`), or
- `Authorization: Bearer <token>`.

A token belongs to the owner or admin profile that was active on the TV when pairing was approved. It stops working the moment that profile becomes a member or is deleted. Without a valid token the answer is 401 `not_paired`. Routes marked *owner* also answer 403 `owner_only` to an admin's token.

### Pairing

1. `POST /api/pair/start` with a client name. The TV shows a 6-digit PIN, which lives 2 minutes.
2. `POST /api/pair/confirm` with the request id and the PIN. Success sets the cookie and returns the client id.

Limits: 5 PIN attempts per pairing, 10 pairing starts per 10 minutes, and after 10 wrong PINs within an hour pairing waits until the hour has passed. The pairing routes read at most 4 KB of body. The TV approves pairing only while the owner's or an admin's profile is active; otherwise both routes answer 403 `no_manager`.

## Errors

Errors share one shape:

```json
{ "error": "library_channel_locked", "message": "..." }
```

`message` is the same user-facing text the app shows, so clients can display it as is.

| Status | `error` | When |
|---|---|---|
| 400 | `bad_request` | Malformed JSON or a missing query parameter |
| 400 | `invalid` | A rule the device checks: duplicate group name, bad group start, empty lineup |
| 400 | `server` | The Jellyfin server could not be reached or rejected the sign-in |
| 400 | `not_a_theme`, `not_a_backup`, `not_an_image` | Uploaded file isn't what the route expects |
| 401 | `not_paired` | No valid token |
| 401 | `wrong_pin`, `too_many_attempts` | Pairing |
| 403 | `forbidden`, `bad_origin`, `no_manager`, `owner_only` | See above |
| 403 | `library_channel_locked`, `built_in_theme` | Editing something only hiding or copying is allowed on |
| 404 | `no_channel`, `no_group`, `no_server`, `no_theme`, `no_sign_in`, `not_found`, ... | Unknown id or route |
| 409 | from `ServerRemovalException` | Removing a server needs an ownership transfer first |
| 410 | `expired` | The pairing PIN expired |
| 413 | `too_large` | Body over the route's limit |
| 429 | `rate_limited` | Too many pairing starts |

## Endpoints

### Device and session

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/health` | none | `{app, version, deviceName, isTv}` |
| POST | `/api/pair/start` | none | Body `{clientName, device?}` (`device: true` when another AnstelleTV pairs). Returns `{requestId, expiresInSeconds, deviceName}`. |
| POST | `/api/pair/confirm` | none | Body `{requestId, pin}`. Sets the token cookie, returns `{clientId, clientName}`. |
| GET | `/api/session` | paired | This pairing and its profile: `{clientId, clientName, deviceName, version, isTv, profile: {id, name, role}}`, role `owner` or `admin`. |
| DELETE | `/api/session` | paired | Unpairs this client and clears the cookie. 204. |
| GET | `/api/overview` | paired | Readouts for the home page: `{servers, channels, onDemand}`. |
| GET | `/api/web` | paired | `{running, urls, clients}`: the addresses web control answers on and the paired browsers. |
| DELETE | `/api/clients/{id}` | paired | Unpairs a browser. 204. |

### Backup

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/backup` | paired | What a backup would hold: `{profiles, channels, groups, themes, servers, historyDays, savedUtcMs, canRestore}`. |
| GET | `/api/backup/export` | paired | The backup file as a JSON attachment, `Cache-Control: no-store`. |
| POST | `/api/backup/check` | owner | Raw backup file as the body (max 32 MB). Returns `{question, explanation}` to confirm before restoring, or 400 `not_a_backup`. |
| POST | `/api/backup/restore` | owner | Raw backup file as the body (max 32 MB). 202, then the app replaces its data and restarts. |

### Servers

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/servers` | paired | `[{id, name, address, version, userName, isSignedIn, lastSyncUtcMs, lastSyncError, progress}]` |
| GET | `/api/servers/{id}` | paired | `{server, counts, libraries, removal}`. `removal` says whether removing it needs an ownership transfer or resets the app. |
| DELETE | `/api/servers/{id}?transferOwnerTo={profileId}` | paired | Removes the server and its synced content. `transferOwnerTo` is needed when the owner would go with it. 204, or 409 / 403. |
| POST | `/api/servers/{id}/sync` | paired | Body `{full}`. Starts a sync. 202. |
| PUT | `/api/servers/{id}/libraries/{libraryId}` | paired | Body `{enabled}`. Takes effect at the next sync. 204. |
| POST | `/api/servers/find` | paired | Body `{address}`. Returns `{signInId, name, address, version, quickConnect}`. The found server is kept for 10 minutes, at most 4 at a time. |
| GET | `/api/servers/signin/{signInId}` | paired | `{state, code?, serverId?, message?}`, `state` one of `idle`, `waiting`, `signedIn`, `failed`. Poll every 3 s during Quick Connect. |
| POST | `/api/servers/signin/{signInId}` | paired | Body `{username, password}`. Returns `{id}` of the server. |
| POST | `/api/servers/signin/{signInId}/quickconnect` | paired | Returns `{code}` to enter in a Jellyfin app. The device waits up to 5 minutes for approval. |
| DELETE | `/api/servers/signin/{signInId}` | paired | Cancels the sign-in. 204. |

### Channels and groups

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/channels` | paired | `{channels: [{id, number, name, logoUrl, isHidden, isFavorite, groupId, groupName, fromLibrary, seasonLabel, inSeason}]}` |
| POST | `/api/channels` | paired | Body `{name, groupId, lineup}`. 201 `{id}`. |
| GET | `/api/channels/{id}` | paired | Detail: the channel, series and movie playback, `oncePerDay`, upcoming `airings`, and the playback choices to offer. |
| PATCH | `/api/channels/{id}` | paired | Any of `{name, groupId, favorite, visible, series, movies, oncePerDay}`. Library channels accept only `favorite` and `visible`. 204. |
| DELETE | `/api/channels/{id}` | paired | 204. Refused for library channels. |
| GET | `/api/channels/{id}/lineup` | paired | `{lineup, reach}` |
| PUT | `/api/channels/{id}/lineup` | paired | Body is a lineup (below). 204. |
| GET | `/api/channels/{id}/logo?v=` | paired | The logo as `image/webp` or `image/png`. With `v` it is cached as immutable. |
| PUT | `/api/channels/{id}/logo` | paired | Raw PNG, JPEG, WebP or GIF bytes, max 4 MB. The device trims and fits it to 400 × 240. 204. |
| DELETE | `/api/channels/{id}/logo` | paired | 204. |
| GET | `/api/lineup/builder` | paired | Fields, comparisons and weights the lineup builder offers. |
| GET | `/api/lineup/options?field=&op=&q=&from=` | paired | Values from the library for a field: `[{value, label, count}]`. |
| POST | `/api/lineup/preview` | paired | Body is a lineup. Returns it with each rule labeled, each pick titled, plus its `reach` (hours, series, episodes, movies). |
| GET | `/api/groups` | paired | `{groups: [{id, name, start, fromLibrary, channelCount}], nextStart, lastGroupId}` |
| POST | `/api/groups` | paired | Body `{name, start}`. 201 `{id}`. |
| PATCH | `/api/groups/{id}` | paired | Body `{name?, start?}`. Library groups take only `start`. 204. |
| POST | `/api/groups/{id}/move` | paired | Body `{delta}`, e.g. `-1` to move up one. 204. |
| DELETE | `/api/groups/{id}` | paired | Deletes an empty group someone made. 204. |
| GET | `/api/ondemand` | paired | On Demand's banner sizes and row order for Home, Movies and Series. |
| PUT | `/api/ondemand/banners/{home\|movies\|series}` | paired | Body `{value}`, the number of items the banner deals. 204. |
| PUT | `/api/ondemand/rows/{movies\|series}` | paired | Body `{keys}`, the rows in order. 204. |

A lineup:

```json
{
  "groups": [
    { "weight": 1.0, "rules": [
      { "field": "GENRE", "op": "ANY_OF", "values": ["Comedy"] },
      { "field": "DECADE", "op": "EQUALS", "values": ["1990"] }
    ] }
  ],
  "includes": [ { "kind": "series", "remoteId": "<jellyfin-series-id>", "weight": 1.0 } ],
  "excludes": [ { "kind": "movie", "remoteId": "<jellyfin-item-id>" } ]
}
```

Groups are ORed and rules inside a group are ANDed. `field` is a `RuleField` name (`GENRE`, `STUDIO`, `TAG`, `OFFICIAL_RATING`, `YEAR`, `DECADE`, `KEYWORD`, `LIBRARY`, `SERIES`, `ITEM`, `MEDIA_TYPE`, `KIND`) and `op` a `RuleOp` name (`EQUALS`, `CONTAINS`, `ANY_OF`, `NOT_ANY_OF`, `AT_LEAST`, `AT_MOST`, `BETWEEN`). `/api/lineup/builder` lists which comparisons each field accepts. Playback `order` is `SHUFFLE`, `EPISODE_ORDER` or `BLOCKS`.

### Themes

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/api/theme` | paired | `{id, spec}`: the theme the paired profile wears, which the web UI wears too. |
| PUT | `/api/theme` | paired | Body `{themeId}`. Puts the theme on the paired profile; the TV follows. 204. |
| GET | `/api/themes` | paired | `[{id, name, isBuiltIn, swatches, inUse, isDefault, spec}]` |
| POST | `/api/themes` | paired | A theme JSON file as the body (max 64 KB). 201 with the imported theme. |
| POST | `/api/themes/new` | paired | 201 `{id}` |
| GET | `/api/themes/{id}` | paired | The theme as a JSON file attachment. |
| DELETE | `/api/themes/{id}` | paired | 204. Built-in themes answer 403. |
| POST | `/api/themes/{id}/copy` | paired | 201 `{id}` of the copy. |
| GET | `/api/themes/{id}/spec` | paired | Designer view: `{id, isBuiltIn, spec, roles, readability, choices}`. |
| PUT | `/api/themes/{id}/spec` | paired | Body `{spec}`. Saves and returns the designer view. |
| POST | `/api/themes/{id}/spec/check` | paired | Body `{spec, choice?, index?}`. Returns the designer view for unsaved edits, with readability in words, without saving. |

### WebSocket

`GET /ws` upgrades to a WebSocket for paired, same-origin clients (401 before the upgrade otherwise). The server sends JSON messages:

```json
{ "type": "hello", "tables": [] }
{ "type": "invalidate", "tables": ["channel", "schedule_slot"] }
```

`invalidate` lists Room tables that changed, plus `sync` at most once a second while a server syncs. The web UI re-reads the data its page shows. If the pairing stops counting (unpaired, or the profile is no longer the owner or an admin), the server closes the socket with code 1008.

Everything outside `/api` and `/ws` serves the SPA. Unknown paths without a file extension fall back to `index.html`. If the APK was built without the web UI, those paths answer 503 with a short "not built" page.

## Examples

Pair from the command line and list channels:

```sh
DEVICE=http://192.168.1.20:8765

curl -s -X POST $DEVICE/api/pair/start \
  -H 'Content-Type: application/json' \
  -d '{"clientName":"curl"}'
# {"requestId":"...","expiresInSeconds":120,"deviceName":"Living room TV"}

curl -s -c cookies.txt -X POST $DEVICE/api/pair/confirm \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"<requestId>","pin":"<PIN shown on the TV>"}'
# {"clientId":3,"clientName":"curl"}

curl -s -b cookies.txt $DEVICE/api/channels
```

Create a channel in group 7 and give it a logo:

```sh
curl -s -b cookies.txt -X POST $DEVICE/api/channels \
  -H 'Content-Type: application/json' \
  -d '{"name":"Late Laughs","groupId":7,"lineup":{"groups":[{"rules":[{"field":"GENRE","op":"ANY_OF","values":["Comedy"]}]}]}}'
# {"id":42}

curl -s -b cookies.txt -X PUT $DEVICE/api/channels/42/logo \
  --data-binary @logo.png
```

Copy a backup the way another AnstelleTV does, with a Bearer token:

```sh
curl -s -H "Authorization: Bearer <token>" $DEVICE/api/backup/export -o backup.json
```
