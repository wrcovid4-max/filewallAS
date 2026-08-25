# FileWall — Firebase multi-platform blueprint

Production blueprint for connecting the FileWall ecosystem — **new Android, iOS, Web, watchOS,
Wear OS** — to the **existing** Firebase project (the `FIREWALL` project), preserving all legacy
data, with real-time bidirectional sync and zero duplication. No Google Drive, no iCloud, no
external file backup: **Firebase Auth + Cloud Firestore + Firebase Storage** are the whole
backend.

> This supersedes the Drive `appDataFolder` backup path for *sync*. The on-device encryption
> stays (see §5); Firebase replaces the transport, not the threat model.

---

## 0. Principles (the rules every target obeys)

1. **One project, one UID.** Every client points at the *same* Firebase project. Google
   Sign-In through Firebase Auth resolves the same account to the **same `uid`** on every
   platform — that `uid` is the key to the user's existing data partition.
2. **Deterministic IDs, upserts only.** Every synced record has a stable, client-generated
   ID (a UUID minted once at creation). Writes are always `set(..., merge)` — **never** `add()`
   (which mints a random ID and is the #1 cause of duplicates).
3. **`updatedAt` is the referee.** Every doc carries a server `updatedAt`. Merge/conflict
   resolution is last-write-wins per doc, field-level for settings.
4. **Soft-delete with tombstones.** Deletes set `deletedAt`; they never hard-remove. This is
   what stops a deleted item resurrecting from another device's cache.
5. **Offline-first.** The Firestore SDK's local cache is the source of truth for the UI;
   listeners reconcile it with the server. The app works offline and converges on reconnect.
6. **Legacy is read-first, migrate-in-place.** Never wipe or re-key old data; map it to the
   canonical schema idempotently, preserving original IDs.

---

## 1. Firebase Auth & legacy data access

### 1.1 Same UID across all targets

Because Firebase Auth's Google provider is keyed to the **Firebase project**, the same Google
account yields the same `uid` on Android, iOS, Web, watchOS and Wear OS — automatically — as
long as every client uses this project's config:

| Target | Config artifact | Google Sign-In path |
|---|---|---|
| Android (new) | `google-services.json` (add app `com.filewall` to the project) | `GoogleSignIn` → `GoogleAuthProvider.getCredential(idToken)` → `FirebaseAuth.signInWithCredential` |
| iOS | `GoogleService-Info.plist` + reversed-client-ID URL scheme | `ASWebAuthenticationSession`/GoogleSignIn → same `signInWithCredential` |
| Web | `firebaseConfig` object | `signInWithPopup(GoogleAuthProvider)` |
| watchOS | shares the iOS bundle/keychain group | prefer companion-mediated (see §4) |
| Wear OS | shares credentials via Data Layer | prefer companion-mediated (see §4) |

> The current Android build deliberately used raw Google Sign-In + Drive REST with **no**
> Firebase SDK. This blueprint reintroduces the Firebase SDK and `google-services.json`. Add
> `com.filewall` as an Android app in the existing project (same SHA-1 flow as `GOOGLE_SETUP.md`).

**Continuity guarantee:** the *old* app authenticated the same user with the same Google
provider in the same project → the returning user gets the **same `uid`** → their existing
Firestore/Storage partition is reachable with no migration of identity. Do not change the
partitioning key (`uid`).

### 1.2 Discover the legacy schema first (do this before writing any client code)

The old source is gone but the data is live. Inventory it, don't guess:

- **Firestore:** in the console, record every top-level collection, the shape of a few docs
  in each, the field names/types, timestamps, and **how records are scoped to a user**
  (top-level collection with an `ownerUid`/`userId` field? a subcollection under
  `users/{uid}`? a per-user top-level collection?).
- **Storage:** record the path convention (`{uid}/...`? `images/{docId}`? content type,
  whether files are plaintext or already encrypted, whether download tokens exist).
- Capture it in a checked-in `legacy-schema.md`. Everything in §2 maps *onto* what you find.

### 1.3 Legacy → canonical mapping (adapter + lazy migration)

Introduce a `LegacyMapper` that reads an old document and returns a canonical model, and
migrate **on read, idempotently**:

```kotlin
// Android – runs when a legacy doc is first seen; safe to run repeatedly.
suspend fun migrateFileDocIfNeeded(legacy: DocumentSnapshot) {
    if (legacy.getLong("schemaVersion") == CURRENT_SCHEMA_VERSION) return
    val canonical = LegacyMapper.toFile(legacy)          // map old field names -> canonical
    db.collection("users").document(uid)
      .collection("files").document(canonical.id)        // SAME id as legacy -> no dupe
      .set(canonical.toMap() + mapOf("schemaVersion" to CURRENT_SCHEMA_VERSION),
           SetOptions.merge())                            // upsert, never add()
}
```

Rules that keep it duplication-proof and lossless:
- **Preserve the legacy document ID** as the canonical ID. Re-running migration re-writes the
  same doc (merge), never a new one.
- **Never drop unknown legacy fields** — carry them under a `legacy` map until you've verified
  they're unused, so no historical information is lost.
- Stamp `schemaVersion`; migrated docs are skipped next time.
- For a one-shot backfill of everything (optional), a **Cloud Function** iterating the legacy
  collection with the same `set(merge)` keyed by original ID is idempotent and re-runnable.

---

## 2. Firestore & Storage schema

### 2.1 Partitioning

Everything hangs off the user, so security rules are trivial and queries are naturally scoped:

```
users/{uid}                                  (profile / lastSyncAt)
users/{uid}/folders/{folderId}               folder tree
users/{uid}/files/{fileId}                    file + image metadata
users/{uid}/meta/settings                     the single settings doc
```

(If the *legacy* layout scopes differently — e.g. a top-level `files` collection with an
`ownerUid` field — keep reading from there via the mapper and write canonical under
`users/{uid}/...`; or keep the legacy layout and just add the canonical fields. Match legacy
first, normalize second.)

### 2.2 File / image document — `users/{uid}/files/{fileId}`

```jsonc
{
  "id": "b1f2…-uuid",            // == document ID, minted once at creation (deterministic)
  "ownerUid": "…",
  "name": "invoice.pdf",
  "mimeType": "application/pdf",
  "category": "DOC",             // PHOTO | VIDEO | DOC | OTHER (mirrors the app model)
  "sizeBytes": 48213,
  "width": 1200, "height": 1600, // 0 for non-visual
  "folderId": "…-uuid | null",
  "storagePath": "users/{uid}/files/b1f2…",   // where the bytes live (see §2.5)
  "thumbPath":   "users/{uid}/thumbs/b1f2…",  // optional
  "checksum": "sha256:…",        // content hash — secondary dedup key (see §3.5)
  "hidden": false,               // hidden vault
  "archived": false,             // Archive
  "deletedAt": null,             // tombstone (epoch ms or serverTimestamp); null = live
  "status": "ready",             // uploading | ready  (see §3.3)
  "createdAt": <serverTimestamp>,
  "updatedAt": <serverTimestamp> // referee for merges/delta sync
}
```

The app already models `id, name, mimeType, sizeBytes, folderId, hidden, archived, deletedAt`
on `VaultItem` — this schema is a superset, so mapping is 1:1 plus `ownerUid/storagePath/
checksum/updatedAt/status`.

### 2.3 Folder document — `users/{uid}/folders/{folderId}`

```jsonc
{
  "id": "…-uuid",
  "name": "Iran",
  "parentId": "…-uuid | null",   // null = root
  "path": "/root/Iran",          // materialized path -> cheap subtree queries & move-safety
  "colorIndex": 3,
  "hidden": false,
  "deletedAt": null,
  "createdAt": <serverTimestamp>,
  "updatedAt": <serverTimestamp>
}
```

Store **both** `parentId` (the edge) and `path` (materialized). `parentId` makes moves a single
field write; `path` (or an array `ancestors: [id,…]`) lets you fetch a whole subtree with one
`where("ancestors","array-contains", folderId)` query instead of recursing.

### 2.4 Settings document — `users/{uid}/meta/settings`

One doc holding the app's `VaultSettings` (theme, autoLock, biometricEnabled, gridView,
showDocPreviews, …) plus `updatedAt`. For settings, prefer **field-level merge** so two devices
changing *different* prefs don't clobber each other:

```kotlin
settingsRef.set(mapOf("gridView" to true, "updatedAt" to FieldValue.serverTimestamp()),
                SetOptions.merge())
```

### 2.5 Storage layout

```
users/{uid}/files/{fileId}          the file bytes (ciphertext if §5 Tier A)
users/{uid}/thumbs/{fileId}         optional preview
```

Path is **derived from `fileId`**, so re-uploading the same logical file overwrites the same
object — no orphan duplicates. Do **not** rely on Storage's random download tokens as the
identity of a file; the Firestore doc (keyed by `fileId`) is the identity.

### 2.6 Security rules (scope everything to the owner)

Firestore:
```
rules_version = '2';
service cloud.firestore {
  match /databases/{db}/documents {
    match /users/{uid}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
  }
}
```
Storage:
```
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    match /users/{uid}/{allPaths=**} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
  }
}
```
These alone make files private to the owner **without** any Drive/iCloud layer — access is
gated by auth + rules, and Storage encrypts at rest server-side. (For zero-knowledge on top of
that, see §5.)

### 2.7 Composite indexes

Create indexes for the real queries: `files` on `(folderId asc, updatedAt desc)`,
`(deletedAt asc, updatedAt desc)`, `(hidden asc, archived asc, updatedAt desc)`; delta sync on
`(updatedAt asc)`. The console will also prompt you with the exact index on first run.

---

## 3. Real-time file & state sync

### 3.1 Offline-first cache

Turn on SDK persistence everywhere; the UI reads the cache, listeners reconcile:
- **Android/iOS:** Firestore offline persistence is **on by default**; Storage uploads use
  resumable `UploadTask` (survive process death).
- **Web:** `initializeFirestore(app, { localCache: persistentLocalCache() })` (IndexedDB).

### 3.2 Listener architecture

One snapshot listener per collection per user; apply changes by **document ID** into the local
store (upsert), and treat `deletedAt != null` as "remove from active views" — never delete the
row (tombstone stays for convergence).

```kotlin
// Android
db.collection("users").document(uid).collection("files")
  .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
      if (err != null || snap == null) return@addSnapshotListener
      for (change in snap.documentChanges) {
          val file = change.document.toObject(FileDoc::class.java)
          when (change.type) {
              ADDED, MODIFIED -> localCache.upsert(file)     // keyed by file.id
              REMOVED         -> localCache.remove(file.id)  // rare; tombstones handle the norm
          }
      }
  }
```

```swift
// iOS
db.collection("users").document(uid).collection("files")
  .addSnapshotListener { snap, _ in
      snap?.documentChanges.forEach { change in
          let file = try? change.document.data(as: FileDoc.self)
          // upsert into local store keyed by file.id
      }
  }
```

```js
// Web (modular v9)
onSnapshot(collection(db, `users/${uid}/files`), (snap) => {
  snap.docChanges().forEach((c) => cache.upsert({ id: c.doc.id, ...c.doc.data() }));
});
```

### 3.3 Idempotent upload pipeline (the anti-duplication core)

```
1. On capture, mint fileId = UUID once. Compute checksum = sha256(bytes).
2. (Dedup check) Query files where checksum == … && deletedAt == null.
     If a live doc already exists -> reuse it; do NOT upload again.
3. set(files/{fileId}) { status:"uploading", …metadata…, updatedAt: serverTimestamp } (merge)
4. Upload bytes to Storage at users/{uid}/files/{fileId} (resumable).
5. On success: set(files/{fileId}) { status:"ready", storagePath, updatedAt } (merge)
```

Because step 3/5 are `set(merge)` on a **deterministic** ID, running this twice (manual sync,
retry, two devices racing) converges to **one** doc and **one** Storage object. A crash between
3 and 4 leaves a `status:"uploading"` doc that the next run finishes — not a duplicate.

### 3.4 Conflict resolution

- **Files/folders:** last-write-wins per document via server `updatedAt`. Metadata edits are
  small; whole-doc LWW is fine.
- **Settings:** field-level `merge` (§2.4) so concurrent edits to different prefs both survive.
- **Moves/renames:** single-field writes (`folderId`/`path`/`name`) — no content re-upload.

### 3.5 Deduplication rules (idempotency)

1. **Primary key = deterministic doc ID.** Always `set(merge)`; never `add()`. Re-sync can't
   duplicate because it writes the same ID.
2. **Secondary = content checksum.** Prevents the *same bytes* being stored twice under two IDs
   (e.g. the same photo shared from two apps). The `checksum` field + the §3.3 step-2 query is
   the guard.
3. **Storage path = f(fileId).** One logical file → one object; re-upload overwrites.

### 3.6 Delta / background sync

Foreground uses live listeners; background/manual sync is a bounded delta query:

```kotlin
val cursor = localCache.lastSyncAt()          // stored per device
db.collection("users").document(uid).collection("files")
  .whereGreaterThan("updatedAt", cursor)
  .get(Source.SERVER)
  .addOnSuccessListener { it.forEach { d -> localCache.upsert(d.toObject()) } ; localCache.setLastSyncAt(now) }
```

Deterministic IDs make this safe to run any number of times. On Android use `WorkManager`
(constraints: network) for periodic delta sync; on iOS a `BGProcessingTaskRequest`.

### 3.7 Deletion & tombstones

Delete = `set(merge){ deletedAt: serverTimestamp, updatedAt: serverTimestamp }`. All clients
hide `deletedAt != null`. A Cloud Function (scheduled) hard-purges docs + Storage objects whose
`deletedAt` is older than the retention window (e.g. 30 days, matching the app's Recently
Deleted). Tombstones guarantee a delete propagates and a stale device can't resurrect the file.

---

## 4. Per-target notes

- **Android (new):** Firebase BoM + `firestore-ktx`, `storage-ktx`, `auth-ktx`. Keep the
  existing Room DB as the offline mirror *or* rely on Firestore's cache directly; wire a
  `FirebaseSyncManager` behind the existing `VaultRepository` interface (see §6).
- **iOS:** `FirebaseFirestore` + `FirebaseStorage` + `FirebaseAuth`; `@DocumentID` Codable
  models; Combine/async listeners. Same schema, same rules.
- **Web:** modular SDK v9, `persistentLocalCache`, `onSnapshot`, resumable uploads via
  `uploadBytesResumable`. A PWA gives the "click a link, it opens" experience with no plugins.
- **watchOS:** don't run a full Firebase client on the watch. Use **WatchConnectivity** — the
  paired iPhone is the Firestore client and pushes a slim manifest / requested file to the
  watch. Standalone fallback: a minimal read-only Firestore query gated by the same auth.
- **Wear OS:** mirror that — the phone is the Firestore client; the watch talks to it over the
  **Data Layer** (`DataClient`/`MessageClient`), exactly as the current FileWall watch handoff
  already works. Standalone fallback: Firestore + `WorkManager`. This keeps watch battery and
  auth complexity down while still being "bidirectional" (the phone commits, the watch reads/
  requests).

---

## 5. Encryption — because this is a vault (read this)

Firebase rules + at-rest encryption make files **private to the account**, but Google (and
anyone with project admin) *can* read them. FileWall is an encrypted vault, so the honest
recommendation is client-side encryption on top:

- **Tier A — zero-knowledge (recommended).** Encrypt bytes on-device before upload with the
  app's existing AES-GCM/CTR crypto. Storage holds **ciphertext**; Firestore holds metadata +
  the wrapped data key. **Do not store public `downloadUrl`s** — fetch with authenticated
  `getData()`/`getBytes()` gated by rules, and decrypt locally. Cross-device key availability:
  derive the vault key from the user's passphrase (PBKDF2, as in `BACKUP_FORMAT.md`) or store a
  **passphrase-wrapped** data key in Firestore so any device that knows the passphrase can
  unwrap it. Google never sees plaintext or the key.
- **Tier B — server-readable (simpler, matches "store download URLs").** Files stored as-is,
  access controlled purely by rules; you may keep `downloadUrl`s. Seamless, but Google can read
  the files and a leaked URL is a leak. Acceptable only if you consciously drop the
  zero-knowledge promise.

Pick per product positioning. For a vault marketed on privacy, **Tier A**. Either way the sync
architecture in §2–§3 is identical — only whether the bytes are ciphertext changes.

---

## 6. Mapping onto the existing FileWall Android codebase

- The canonical schema is a **superset** of `VaultItem`/`VaultFolder`, so the model change is
  additive: add `ownerUid`, `storagePath`, `checksum`, `updatedAt`, `status`.
- Introduce `FirebaseSyncManager` next to `VaultRepository`. Room stays as the local cache; the
  manager mirrors Firestore→Room (listeners) and Room→Firestore (upserts on local mutation),
  both keyed by the existing UUIDs — so the app's UI/Repository API is untouched.
- The existing soft-delete (`deletedAt`), Archive (`archived`), Hidden (`hidden`) fields map
  straight to the tombstone/flag model here — no new concepts.
- This **replaces** the Drive `appDataFolder` backup as the sync mechanism. Keep the local
  encryption (it becomes Tier A above); retire the Drive code once Firestore sync is live, or
  leave it as an export-only convenience.

---

## 7. Guardrails (the ways this goes wrong)

- ❌ `add()` / auto-IDs for synced entities → duplicates. ✅ `set(deterministicId, merge)`.
- ❌ hard-delete on one device → resurrection from another's cache. ✅ `deletedAt` tombstones.
- ❌ a second Firebase project or per-platform projects → different `uid`s, split data. ✅ one
  project, all targets.
- ❌ plaintext bytes in Storage for a "vault" → silent privacy regression. ✅ Tier A.
- ❌ changing the user-partition key → orphaned legacy data. ✅ keep `uid` partitioning; map,
  don't move.
- ❌ trusting Storage download tokens as identity → orphans. ✅ Firestore doc (by `fileId`) is
  identity; Storage path is `f(fileId)`.
