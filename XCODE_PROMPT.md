# Xcode prompt — FileWall for Apple platforms

Targeting **Xcode 14.2 / Swift 5.7.2 / iOS 16 / iPadOS 16 / watchOS 9**.

## Read this before you start

Two things the toolchain decides for you:

**visionOS cannot be built.** The visionOS SDK first shipped in Xcode 15.2. There is no
back-port, no simulator, no conditional compilation trick. It's iOS, iPadOS and watchOS, or
you move to a newer Xcode.

**App Store submission is closed.** Since April 2025 Apple requires builds made with
Xcode 16 and the iOS 18 SDK. Xcode 14.2 is fine for developing, running on your own
registered devices and sideloading — TestFlight and the App Store will reject it.

Neither blocks building the app. Both should be a conscious choice rather than a surprise
three months in.

Also: Xcode 14.2 needs macOS 12.5+, and its maximum deployment targets are iOS 16.2 and
watchOS 9.1.

## What iOS 16 costs you

App Intents itself is fine — it *launched* in iOS 16, so Siri, Shortcuts and Spotlight are
all reachable. The losses are in the newer conveniences:

| Wanted | iOS 16 reality |
|---|---|
| SwiftData | Core Data |
| `@Observable` macro | `ObservableObject` + `@Published` |
| `IndexedEntity` (free Spotlight indexing) | hand-rolled `CSSearchableItem` + `CSSearchableIndex` |
| `isDiscoverable = false` per intent | no such flag — control exposure by what you put in `AppShortcutsProvider` |
| `ControlWidget` (Control Center) | Lock Screen accessory widgets |
| `@AssistantIntent` / Apple Intelligence | not available |
| `needsToContinueInForegroundError()` | `openAppWhenRun = true` |
| `AppIntentsPackage` (16.4+) | keep intent source in the app target |
| Swift 6 strict concurrency | Swift 5.7 `async`/`await` + actors, which is most of the value |
| visionOS | — |

None of that touches the core: chunked AES-GCM, Secure Enclave key wrapping, encrypted video
streaming and the full App Intents surface all work on iOS 16.

## Build order

Don't paste this as one shot. One prompt per stage, same conversation:

1. `FileWallKit` — crypto, storage, models. Tests green before any UI exists.
2. The iOS/iPadOS app.
3. App Intents.
4. watchOS.
5. Widgets.

Stage 1 is where to spend your patience. Everything after it is replaceable.

---

## The prompt

```
Build FileWall for Apple platforms: an encrypted file vault for iOS 16, iPadOS 16 and
watchOS 9, with App Intents, Siri, Spotlight and Shortcuts as primary surfaces rather than
bolt-ons.

HARD CONSTRAINT: Xcode 14.2, Swift 5.7.2, iOS 16.0 deployment target, watchOS 9.0. No
SwiftData, no @Observable macro, no Swift 6 concurrency, no IndexedEntity, no ControlWidget,
no visionOS, no third-party dependencies. If you are about to use an API introduced after
iOS 16.2, stop and use the iOS 16 equivalent. Where the older API is meaningfully worse,
say so in a comment rather than silently accepting it.

There is an existing Android implementation. Match its behaviour and its security posture,
but do not transliterate its APIs — where Apple's platform has a better idiom, take it, and
say why.

## What the product is

A private vault for photos, videos and documents. Encryption keys never leave the device.
No account, no server, no sign-up. Cloud backup uploads a blob Apple cannot read. There is
a second "hidden" vault behind biometrics, and a watch companion.

## Project structure

  FileWallKit/       Swift package. Crypto, storage, models, sync. No UI. Must build and
                     test on macOS so the crypto suite runs without a simulator.
  FileWall/          iOS + iPadOS app, single adaptive target
  FileWallWatch/     watchOS 9 app + extension
  FileWallWidgets/   WidgetKit — home screen and Lock Screen accessory widgets

App Intents source files live in the app target, NOT in FileWallKit. `AppIntentsPackage`
arrived in iOS 16.4 and Xcode 14.2's metadata extractor only scans the app target — intents
defined in a framework will compile and then silently fail to appear in Shortcuts, which is
a miserable thing to debug. The intents can freely *call into* FileWallKit; they just have
to be declared in the app.

## Storage and crypto

The Android build uses AES-256-CTR plus HMAC-SHA256 with keys in the Android Keystore. Do
not copy that construction — Apple's platform supports a better one.

**Key hierarchy**
- A random 256-bit AES key per vault: `SymmetricKey(size: .bits256)`.
- Wrapped by a Secure Enclave P-256 key via `SecureEnclave.P256.KeyAgreement`, then HKDF.
  The Secure Enclave holds only P-256 keys, never AES, so wrapping is the correct pattern
  rather than a workaround — note that in a comment, because it looks like indirection.
- The wrapped key goes in the Keychain with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`,
  and a `SecAccessControl` requiring `.biometryCurrentSet` for the hidden vault's key.
  `ThisDeviceOnly` keeps it out of iCloud Keychain and device backups.
  `.biometryCurrentSet` rather than `.biometryAny` so enrolling a new face or finger
  invalidates the key. That is desired behaviour for a vault, not an inconvenience.

**File format — chunked AES-GCM**
Encrypt in 1 MiB chunks, each sealed independently with `AES.GCM` (CryptoKit, iOS 13+).
Nonce per chunk derived from a per-file random base plus the chunk index. Header carries
magic, version, base nonce, chunk size.

This beats the Android CTR+HMAC design on this platform: authenticated encryption *and*
seekability, where CTR+HMAC gives seekability but can only verify integrity by reading the
whole file. Random access costs one chunk decrypt. A tampered chunk fails to open rather
than yielding plausible garbage. CryptoKit also doesn't expose CTR publicly, so CTR would
mean dropping to CommonCrypto for nothing.

Document the layout as a byte diagram in a header comment.

**Metadata — Core Data**
Entities for VaultFile and VaultFolder. Names, types and folder membership live only there.
Blobs on disk are UUID filenames with no extension and no metadata. Set
`FileProtectionType.completeUnlessOpen` on the container as defence beneath the app's own
encryption.

Use `NSPersistentContainer` with a background context for writes and
`NSFetchedResultsController` or an `@FetchRequest` for the UI. No SwiftData.

**Video playback**
Implement `AVAssetResourceLoaderDelegate` against a custom URL scheme so `AVPlayer` reads
decrypted chunks on demand. Plaintext never reaches disk, and scrubbing works because the
loader can serve any byte range by decrypting the chunks it covers. This is the direct
counterpart to the Android build's custom media DataSource.

**Portable archive**
A `.fwvault` export: PBKDF2-HMAC-SHA256, 210,000 iterations, via CommonCrypto — CryptoKit
has no PBKDF2 and no Argon2, so note that in a comment rather than leaving a reader
wondering why CommonCrypto appears. Same chunked-GCM body, keyed from the passphrase rather
than the device, so it restores on a different device. This is what cloud backup uploads.

## App Intents — the centre of gravity

### Entities

`VaultFileEntity: AppEntity`
  name, category (AppEnum: photo/video/document/other), size, dateAdded, folder.
  `DisplayRepresentation` with title, subtitle (formatted size), and an image from the
  thumbnail — but only when the user has opted into thumbnails leaving the app.

  Compatibility check: iOS 16.0's protocol requirement is `static var typeDisplayName:
  LocalizedStringResource`. `typeDisplayRepresentation` arrived in a later 16.x SDK than
  Xcode 14.2 ships. If the compiler rejects one, use the other.

`VaultFolderEntity: AppEntity`
  name, colour, item count.

### Queries

`VaultFileQuery: EntityStringQuery, EntityPropertyQuery`
- `EntityStringQuery` lets Siri resolve "the invoice one" from a spoken fragment.
- `EntityPropertyQuery` is the one that earns its keep: it generates Shortcuts' "Find Files"
  action with real filters and sorting, for free. Expose name (contains/equals), category
  (equals), size (greater/less than), dateAdded (before/after/within). Sort by date, name,
  size.

  This single conformance makes the vault composable from Shortcuts without writing a
  bespoke intent per query. It is the highest-leverage thing in the whole integration.

**Hard rule: no query ever returns a hidden-vault item.** Filter in the query, not in the
UI. A hidden item must not exist as an entity — so it cannot be resolved, indexed, suggested
or returned. Same rule the Android build applies to what reaches the watch.

### Intents

  OpenVaultIntent          opens the app. openAppWhenRun = true.
  OpenFileIntent           takes a VaultFileEntity, opens it in the app.
  ImportFilesIntent        takes [IntentFile], encrypts them in. Works from the share sheet
                           and as a Shortcuts action, so "save every photo from this album
                           into FileWall" becomes something the user can build themselves.
  FindFilesIntent          backed by EntityPropertyQuery. ReturnsValue<[VaultFileEntity]>.
  HideItemIntent           moves an item into the hidden vault.
  UnhideItemIntent         the reverse.
  LockVaultIntent          locks immediately.
  UnlockHiddenVaultIntent  requires local device authentication.
  BackUpVaultIntent        runs a backup. ProvidesDialog with the outcome.
  VaultStatusIntent        storage totals and item count. Safe: describes the vault's size,
                           never its contents.
  ExportFileIntent         decrypts one file out to a Shortcuts file output.

Use `ShowsSnippetView` on FindFilesIntent and VaultStatusIntent for a result card — that is
available in iOS 16 (static snippets; interactive ones are much later).

### Security on intents — the easy part to get wrong

Every intent that reads or moves vault content sets:

    static var authenticationPolicy: IntentAuthenticationPolicy { .requiresLocalDeviceAuthentication }

That forces Face ID / Touch ID / passcode before the intent runs, including from a
Shortcuts automation on a locked device. This exists in iOS 16 — use it.

LockVaultIntent is deliberately `.alwaysAllowed`: locking must never be gated, including
from a Lock Screen widget. VaultStatusIntent is also unauthenticated, since size is not
content.

iOS 16 has no per-intent `isDiscoverable` flag. Exposure is controlled by what you put in
`AppShortcutsProvider` — so hidden-vault intents simply do not go in it. An entry named
"Unlock Hidden Vault" appearing in Siri suggestions on a shared iPad would defeat the
feature it belongs to.

### App Shortcuts

`AppShortcutsProvider` with phrases for the highest-value actions. Every phrase must contain
`\(.applicationName)` or the build fails — this is the most common first mistake.

  "Open my vault in \(.applicationName)"
  "Lock \(.applicationName)"
  "Save this to \(.applicationName)"
  "How much is in my \(.applicationName)"
  "Find photos in \(.applicationName)"

Keep the set to five or six. A crowded App Shortcuts list makes Siri worse at picking, not
better. Note that `AppShortcut`'s `shortTitle:` parameter may not exist in the 16.0 SDK — if
the compiler rejects it, use the `intent:phrases:systemImageName:` initialiser.

## Spotlight — hand-rolled on iOS 16

`IndexedEntity` doesn't exist until iOS 18, so index explicitly:

- `CSSearchableItem` with a `CSSearchableItemAttributeSet`, pushed through
  `CSSearchableIndex.default().indexSearchableItems(_:)`.
- Use the file's UUID as `uniqueIdentifier` and a stable `domainIdentifier` per vault
  section, so deletions can be done by domain in one call.
- Handle `CSSearchableItemActionType` in `onContinueUserActivity` to open the right file.

Index only non-hidden items, and only when the user has switched indexing on. **Off by
default**, with a plain sentence in Settings: your file names become searchable from the
home screen, which is convenient and is also a disclosure.

Attach thumbnails behind a second, separate toggle — a thumbnail in the Spotlight index is
a decrypted copy of vault content living outside the vault, and that deserves its own
decision rather than riding along with the first.

Delete from the index on: item deleted, item moved to hidden, indexing switched off, vault
reset. Test the "moved to hidden" path specifically — it is the one that gets missed and the
one that matters.

## Widgets

- Home screen widget: storage totals by category. No names, no thumbnails.
- Lock Screen accessory widgets (`.accessoryCircular`, `.accessoryRectangular`) showing
  vault size and lock state. These are the iOS 16 stand-in for Control Center controls.
- A Live Activity for backup progress (iOS 16.1+, so guard it) — long backups are exactly
  what the Dynamic Island is for.
- Widget tap targets use `Link`/`widgetURL` to deep-link into the app.

## iOS and iPadOS

One adaptive target. Three sections matching the Android build: Vault, Hidden, Security.

- iPhone: `TabView`.
- iPad: `NavigationSplitView` (iOS 16) — folder sidebar, file grid, inspector detail. Not a
  stretched phone layout.
- `NavigationStack` for push navigation, not the deprecated NavigationView.
- Drag and drop in and out, including between windows.
- Multiple scenes, Stage Manager aware.
- Keyboard shortcuts: ⌘F search, ⌘L lock, space for Quick Look, ⌫ delete.
- `ShareLink` for export (iOS 16).
- Swift Charts for the storage breakdown (iOS 16).
- `.presentationDetents` for the item details sheet (iOS 16).
- `NSUserActivity` for Handoff so an open file continues on another device.

**Screenshots — an honest platform difference.** iOS has no equivalent of Android's
FLAG_SECURE. You cannot block screenshots. What you can do, and should:
- Blur content when `scenePhase == .inactive`, so the app-switcher snapshot shows nothing.
- Observe `UIScreen.capturedDidChangeNotification` and blur while recording or mirroring.
- Observe `userDidTakeScreenshotNotification` and tell the user plainly that a screenshot of
  vault content now exists in their photo library.

Do not use the `isSecureTextEntry` layer trick to fake it. It depends on private behaviour,
it breaks between releases, and it is a review risk.

## watchOS 9

A viewfinder, not a second vault. Hidden items are never transmitted, so a lost watch cannot
even ask for them.

- `WCSession` for the phone link. The watch requests a manifest; the phone answers. Nothing
  is pushed speculatively — a watch out of range costs nothing.
- Photos open on the watch. Video and documents show the thumbnail plus "Open on iPhone",
  which posts a notification rather than opening anything remotely.
- WidgetKit complication for vault size (watchOS 9 moved complications to WidgetKit).
- Digital Crown to zoom a photo.

## Cross-cutting privacy rules

Constraints, not preferences. Anything violating one is a bug:

1. A hidden item is never an AppEntity, never indexed, never in a query result, never sent
   to the watch, never in a widget.
2. Plaintext exists on disk in exactly one place — a preview cache for Quick Look of formats
   the app cannot render — wiped on lock, on backgrounding, and at launch.
3. No analytics, no telemetry, no crash reporter that transmits file names.
4. Every intent that reads content requires local authentication.
5. The vault locks on backgrounding regardless of the inactivity timer.

## Configuration

- `ITSAppUsesNonExemptEncryption` in Info.plist. The app uses standard OS cryptography and
  qualifies for the exemption, but undeclared it stalls every build on export compliance.
- App Group so widgets can read metadata — never keys.
- Keychain sharing across targets for the wrapped key, access group set explicitly.
- `BGProcessingTaskRequest` for scheduled backup: `requiresExternalPower = false`,
  `requiresNetworkConnectivity = true`.
- Cloud backup to the CloudKit private database as a `CKAsset` carrying the already-
  encrypted archive. Also offer "Save to Files" so nobody is forced into iCloud.

## Testing

- Crypto round-trip across chunk boundaries: files of exactly 0, 1, 1 MiB − 1, 1 MiB and
  1 MiB + 1 bytes. Off-by-one at the boundary is the bug that will bite you.
- Tampering: flip one byte in the header, the first chunk, and the last chunk. All three
  must fail to open.
- Random access: decrypt a middle chunk without touching earlier ones; assert it matches the
  whole-file decrypt.
- Key invalidation: a Keychain item guarded by `.biometryCurrentSet` must fail after
  biometric re-enrolment.
- App Intents: assert a hidden entity is unresolvable through every query path.

## Style

Swift 5.7. `async`/`await` and actors for crypto and storage — no completion handlers.
`ObservableObject` with `@Published` for view models. No force-unwraps outside tests.
Comments explain why, not what, especially in the crypto, where the reasoning is the only
thing that survives a refactor.
```

---

## If you can upgrade Xcode later

Roughly what each step buys, so the decision is concrete rather than "newer is better":

- **Xcode 15.2** → visionOS becomes possible. Also SwiftData, `@Observable`, and
  `AppIntentsPackage` (intents can move into the framework).
- **Xcode 16** → App Store submission unblocks. `IndexedEntity` deletes your hand-rolled
  Spotlight code, `ControlWidget` puts Lock Vault in Control Center, and `isDiscoverable`
  gives per-intent control instead of the all-or-nothing `AppShortcutsProvider` approach.

The iOS 16 code doesn't get thrown away by any of that — those are additions on top, not
replacements. Building on 14.2 now is a real path, not a dead end.

## Follow-up prompts

- *"Write FileWallKit's crypto layer only — the chunked-GCM format, Secure Enclave key
  wrapping, and the full test suite. Nothing else. Swift 5.7, iOS 16."*
- *"Now the AVAssetResourceLoaderDelegate for encrypted video playback, with the byte-range
  to chunk-index mapping."*
- *"The App Intents layer. Start with VaultFileEntity, VaultFileQuery and FindFilesIntent,
  and show me the Shortcuts action they generate."*
- *"Audit every code path that could surface a hidden item outside the app: queries,
  Spotlight, widgets, WatchConnectivity, Handoff. List them and prove each is blocked."*
