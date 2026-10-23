# Installation

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| JDK | 17 or newer to launch Gradle | The Gradle daemon is pinned to JDK 17 in `gradle/gradle-daemon-jvm.properties`, and the foojay resolver downloads it if it is missing. |
| Android SDK | Platform 37 (`compileSdk` and `targetSdk`) | `minSdk` is 25 (Fire OS 6). Point Gradle at the SDK with `local.properties` (`sdk.dir=...`) or `ANDROID_HOME`. |
| Node.js | 22.17 or newer | Builds the web UI. |
| pnpm | 12.9.1 | Pinned in `webui/package.json`. Without `pnpm` on the PATH, the build runs `corepack pnpm`. |
| A device or emulator | Android 7.1.1 (API 25) or newer | Android TV, Google TV, Fire TV, phone or tablet. |
| Jellyfin | A server you can sign in to | Quick Connect needs to be enabled on the server to use it. Media segments for skipping intros need Jellyfin 10.10 or later. |
| Python 3 with Pillow | Optional | Only for `tools/brand/generate_brand.py` and `tools/themes/generate_themes.py`. |

The Gradle wrapper (9.8.0) is checked in, so Gradle itself needs no install.

## Steps

### 1. Clone the repository

```sh
git clone https://github.com/EagleStelle/AnstelleTV.git
cd AnstelleTV
```

### 2. Install dependencies

Gradle fetches the Android and Kotlin dependencies on the first build. The web UI's dependencies are installed by the `webUiInstall` task, which every Android build runs, so a separate `pnpm install` is only needed for working on the web UI by itself:

```sh
cd webui
pnpm install
```

### 3. Configure the build

The app needs no environment variables, API keys or `.env` file. Two optional files change the build:

- `local.properties` at the repo root sets the Android SDK path, if `ANDROID_HOME` isn't set:

  ```properties
  sdk.dir=/path/to/Android/Sdk
  ```

- `keystore.properties` at the repo root signs release builds with your own key. Without it, release APKs are signed with the debug key.

  ```properties
  storeFile=release.keystore
  storePassword=...
  keyAlias=...
  keyPassword=...
  ```

  `storeFile` is resolved from the repo root. Both files are gitignored.

The web UI dev server reads one variable, `ANSTELLE_DEVICE` (see [Web UI development](#web-ui-development)).

### 4. Database

There is nothing to set up. Room creates the database on the device on first launch. Debug builds recreate it when the schema version changes, which wipes the app's data on that device.

### 5. Build and run

Debug build (macOS/Linux):

```sh
./gradlew assembleDebug
```

On Windows:

```powershell
.\gradlew assembleDebug
```

The build runs `pnpm install` and `pnpm build` in `webui/`, gzips the output and copies it into `app/src/main/assets/webui`. If neither pnpm nor Corepack is available, a debug build skips the web UI with a warning; the app still works, but its web address answers with a "not built" page.

Install on a connected device or running emulator:

```sh
./gradlew installDebug
# or
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a Fire TV or other TV on the network with ADB debugging turned on:

```sh
adb connect <tv-ip-address>
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release build

```sh
./gradlew assembleRelease
```

Release builds use R8 full mode with resource shrinking and need pnpm (they fail without it). They produce one APK per ABI plus a universal one in `app/build/outputs/apk/release/`:

| APK | Use it for |
|---|---|
| `app-armeabi-v7a-release.apk` | Fire TV and other 32-bit Android systems |
| `app-arm64-v8a-release.apk` | Most current phones, tablets and Google TV devices |
| `app-x86_64-release.apk` | Emulators |
| `app-universal-release.apk` | Any of the above, at a larger size |

### Publishing a release

GitHub Actions builds and publishes releases. Pushing a tag `v<versionName>` (for example `v1.0.0`) runs the CI checks, builds the release APKs with the release key and publishes a GitHub release with GitHub's generated notes, which end with the *Full Changelog* link. The release holds the four APKs, named `AnstelleTV-v1.0.0-<abi>.apk`, the R8 mapping file and `SHA256SUMS.txt`.

1. Raise `versionName` in `app/build.gradle.kts`. `versionCode` follows from it (1.2.3 becomes 10203).
2. Commit, then tag and push:

   ```sh
   git tag v1.0.0
   git push origin main v1.0.0
   ```

The release workflow stops if the tag doesn't match `versionName` or if a signing secret is missing. It needs four repository secrets (Settings > Secrets and variables > Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | The keystore file, base64-encoded (`base64 -w0 release.keystore`) |
| `KEYSTORE_PASSWORD` | `storePassword` |
| `KEY_ALIAS` | `keyAlias` |
| `KEY_PASSWORD` | `keyPassword` |

## Verify it works

1. Launch AnstelleTV. A fresh install opens on *Add server*, which also offers *Restore backup*.
2. Enter the server address (an IP, host name or full URL) and sign in with Quick Connect or a username and password.
3. Wait for the first library sync. Live TV fills with channels grouped as Productions, Decades, Genres and Themes.
4. Pick a channel. It starts playing with the banner up, and the banner's tech line says DIRECT PLAY or TRANSCODING.

To check the embedded web server, turn on Settings > Web control on the device (on Android 17, allow local network access when asked), then forward the port and call the health endpoint:

```sh
adb forward tcp:8765 tcp:8765
curl http://localhost:8765/api/health
```

The response looks like:

```json
{"app":"AnstelleTV","version":"1.0.0","deviceName":"...","isTv":true}
```

Open `http://localhost:8765` in a browser to pair with the PIN shown on the device. From another computer on the same network, use the address shown on the Web control page instead.

## Web UI development

Run the SvelteKit dev server against a device that has web control turned on:

```sh
adb forward tcp:8765 tcp:8765        # for an emulator or USB device
cd webui
pnpm dev
```

`pnpm dev` proxies `/api` and `/ws` to `ANSTELLE_DEVICE`, which defaults to `http://127.0.0.1:8765`. To use a TV on the network instead:

```sh
ANSTELLE_DEVICE=http://<tv-ip-address>:8765 pnpm dev
```

Type-check the web UI with `pnpm check`.
