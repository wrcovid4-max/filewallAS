# FileWall

An encrypted personal file vault for Android, with a Wear OS companion for viewing.

Rebuilt from screenshots of the original app after the source was lost. Native Kotlin +
Jetpack Compose, chosen because the Wear OS companion talks to the phone over the Play
Services Data Layer — which is a native-only API.

## Modules

| Module    | What it is                                                                  |
|-----------|-----------------------------------------------------------------------------|
| `:app`    | The phone app. Compose UI, Room metadata, Keystore-backed file encryption.   |
| `:wear`   | The Wear OS companion. Read-only viewfinder onto the phone's unlocked files. |
| `:shared` | The Data Layer contract both halves compile against, so paths can't drift.   |

## Building

Open the project in Android Studio (Ladybug or newer) and run the `app` configuration, or:

```bash
./gradlew :app:assembleDebug        # phone APK
./gradlew :wear:assembleDebug       # watch APK
./gradlew :app:testDebugUnitTest    # unit tests
```

- **compileSdk 35**, **minSdk 26**, targetSdk 35 (wear targets 34).
- Requires JDK 17+. Gradle wrapper is pinned to 8.11.1, AGP 8.7.3, Kotlin 2.0.21.
- The Android SDK is resolved from `local.properties` / `ANDROID_HOME` as usual.

The phone app is aimed at **Android 11 (API 30)** as the test device. Everything imports
through the Storage Access Framework — no `READ_EXTERNAL_STORAGE`, no
`MANAGE_EXTERNAL_STORAGE` — so scoped storage is a non-issue.

> **Not compiled here.** This was written in an environment with no Android SDK and with
> Google's Maven repository blocked by network policy, so it has never been through AGP.
> Sources were parse-checked with `kotlinc` 2.0.21 and are structurally clean, but expect
> to fix the odd import or signature detail on the first build.

## How the encryption works

Every file in the vault is stored as an opaque blob under
`/data/data/com.filewall/files/vault/blobs/<uuid>.fw`:

```
[ magic "FWV1" 4B ][ iv 16B ][ AES-256-CTR ciphertext … ][ HMAC-SHA256 32B ]
```

Two keys, both generated in and never leaving the **Android Keystore**: an AES key for the
body and an HMAC key for the tag. CTR rather than GCM on purpose — GCM will not release
verified plaintext until it has buffered the whole message, which would mean holding an
entire video in RAM. CTR streams in constant memory, and the trailing HMAC (computed over
the magic, IV and ciphertext) supplies the integrity GCM would have given us. Decryption
verifies the MAC in a separate pass *before* emitting a single plaintext byte.

Filenames, MIME types and folder membership live only in the Room database, which is itself
app-private. Nothing on disk reveals what a blob holds.

**Where plaintext can exist:** exactly one place — `cacheDir/preview/`, and only while an
external app is viewing a file type FileWall can't render itself (documents, and anything
unrecognised). Photos and video are decrypted in memory only. That directory is wiped when
the vault locks, when the activity pauses, and on next launch, and it's excluded from cloud
backup and device transfer.

### The passcode

The hidden-archive PIN is stored as a PBKDF2-HMAC-SHA256 digest (120k iterations, 16-byte
salt) and compared in constant time. Five wrong attempts start an escalating lockout
(30s, 60s, 120s… capped at five minutes). The PIN gates *visibility* of hidden items; the
file keys are gated by the OS Keystore independently.

## Backup

Two paths, one format. Both produce the same passphrase-encrypted `.fwvault` file:

```
[ "FWARCH01" 8B ][ salt 16B ][ iterations 4B ][ iv 16B ][ AES-CTR(zip) … ][ HMAC 32B ]
```

The zip inside holds a `manifest.json` plus one entry per file. PBKDF2-HMAC-SHA256 at
210k iterations derives both the AES and HMAC keys from the passphrase.

Why a passphrase and not the device key? Keystore keys are non-exportable by design, so a
backup encrypted with them could only ever be restored onto the same phone. The passphrase
is what makes the archive portable.

**Encrypted Archive** (works immediately): export to any location via the system file
picker, restore the same way. Restore is verified end-to-end before anything is written —
the archive is decrypted to a staging directory, the HMAC is checked, and only then are
files ingested.

**Google Drive** (needs one-time setup): uploads that same `.fwvault` into Drive's private
`appDataFolder`, so Google stores a blob it cannot read. Manual Backup/Restore buttons, plus
an optional **Back up daily** schedule (WorkManager, unmetered network, battery not low).

The schedule needs the passphrase without anyone there to type it, so enabling it seals the
passphrase with a *separate* AES-GCM key that never leaves the Keystore — inert on any other
hardware, and useless to someone holding a copy of the app's data directory. Switching the
schedule off erases it. Manual backup and restore never store anything, and restoring onto a
new phone still requires typing the passphrase, because that key cannot travel either.

### Enabling Google Drive backup

Sign-in will fail with *"This build has no Google OAuth client"* until you register it:

1. Create a project in the [Google Cloud Console](https://console.cloud.google.com/).
2. Enable the **Google Drive API**.
3. Configure the OAuth consent screen and add the `.../auth/drive.appdata` scope.
4. Create an **OAuth client ID → Android**, with:
   - package name `com.filewall`
   - the SHA-1 of the certificate you're signing with
     (`./gradlew :app:signingReport` prints it for debug builds)

No client ID string goes into the app — Google matches on package name and signing
certificate. Register both your debug and release certificates.

## The Wear OS companion

The watch is a viewfinder, not a second copy of the vault. Two rules shape the whole link:

- **Hidden items never leave the phone.** Not their bytes, not their names, not their
  existence — they're absent from the manifest entirely, so a lost watch cannot even ask.
- **Nothing is pushed speculatively.** The watch asks, the phone answers. A watch that's out
  of range costs nothing.

| Path                         | Direction     | Payload                                     |
|------------------------------|---------------|---------------------------------------------|
| `/filewall/request_manifest` | watch → phone | empty                                       |
| `/filewall/manifest`         | phone → watch | item JSON + a 160px thumbnail Asset per item|
| `/filewall/request_image`    | watch → phone | item id                                     |
| `/filewall/image/<id>`       | phone → watch | one 640px JPEG Asset                        |
| `/filewall/open_on_phone`    | watch → phone | item id                                     |

Photos open on the watch. Video and documents can't usefully be read on a 1.4" screen and
would be slow to pull over Bluetooth, so those show the thumbnail the manifest already
carried plus an **Open on phone** button. The phone answers with a notification rather than
launching itself — background activity starts have been blocked since Android 10, and a
vault that could throw itself open from inside a pocket would be wrong even where the
platform allowed it. Tapping the notification opens that item. A request naming a hidden
item is refused outright rather than acknowledged.

The manifest is capped at the 60 most recent unlocked files and is republished whenever the
vault changes. Turning off **Sync to Wear OS** in Security deletes everything the watch is
holding.

Both modules ship the same `applicationId` (`com.filewall`) because the Data Layer pairs
phone and watch apps by application id.

## Screens

**Open Vault** — search, the Unlocked/Hidden pill, item count with sort (Date Added, Name,
Size, Type) and direction, grid/list toggle, multi-select. Folders carry a colour and an
overflow menu (Rename / Colour / Delete); the upload FAB imports through the system picker.

**Hidden** — the passcode pad. On first use it walks set-then-confirm, so the hidden side can
never end up open but unprotected. Biometrics are offered automatically when enabled, and
become the only way in if *Disable Passcode Fallback* is on.

**Security** — storage breakdown, the lock/biometric/fallback switches, appearance,
inactivity auto-lock (15s / 30s / 1m / 5m / Never), device-storage-sync and screenshot
toggles, watch sync, and both backup paths.

**Viewer** — pinch-zoom image stage, in-place video playback, and the Item Details sheet with
Export / Move / Rename / Delete. Documents and unknown types hand off to an external viewer
through a `FileProvider` grant.

### A note on `Allow Screenshots`

Off by default, which sets `FLAG_SECURE`. That keeps vault contents out of screenshots,
screen recordings *and* the app-switcher thumbnail. Turning it on is what let the original
screenshots exist in the first place.

## Architecture

Manual DI — `AppContainer` builds everything lazily off `FileWallApp`. The object count
never justified an annotation processor.

```
FileWallApp ──> AppContainer ──┬─> VaultCrypto      (Keystore, AES-CTR + HMAC)
                               ├─> VaultRepository  (the only door to ciphertext)
                               ├─> VaultDatabase    (Room: items + folders)
                               ├─> SettingsStore    (DataStore)
                               ├─> PinManager       (PBKDF2 + lockout)
                               ├─> ThumbnailStore   (LRU, cleared on lock)
                               ├─> VaultArchive     (.fwvault)
                               ├─> DriveBackup      (appDataFolder over REST)
                               ├─> WearSyncManager  (Data Layer)
                               └─> LockController   (idle timer, hidden-vault state)
```

`LockController` is app-scoped rather than screen-scoped deliberately: walking from Hidden
to Security and back must not silently re-open the archive, and the idle timer has to keep
running while you're looking at an image.

## Video playback

Video streams straight out of the encrypted blob — no decrypt-to-disk step, so plaintext
never exists outside the decoder's buffers.

`VaultDataSource` is a media3 `DataSource` over the vault. Scrubbing a video is nothing but
a series of seeks, and CTR makes those cheap: the keystream for block *n* depends only on
the IV plus *n*, so `VaultCrypto.cipherAt` re-derives it at any offset with one big-endian
addition. GCM could not do this at any price — which is the second reason the format uses
CTR, beyond constant memory.

The trade-off is that a seeking reader can never check the trailing HMAC, because it never
reads the whole file. `VaultRepository.openForPlayback` therefore verifies once, up front,
and throws before a player is ever built. The counter arithmetic is unit-tested
(`CtrCounterTest`) — a carry bug there wouldn't crash, it would hand ExoPlayer plausible
garbage part-way into a file.

## Known gaps

- **Restore is all-or-nothing.** There's no way to pull a single file out of an archive
  without restoring the whole thing.
- **No shared-album or multi-device sync.** Drive holds one archive per account; two phones
  writing to the same account will overwrite each other's backups.
- **The watch cannot import.** It's a read-only view; adding files is phone-only.
