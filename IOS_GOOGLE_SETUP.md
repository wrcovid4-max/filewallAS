# iOS prompt — Google Sign-In + Drive Backup & Sync (sign-in only)

Hand this to the agent building the iOS app **after** the main `XCODE_PROMPT.md`. It wires the
same "Backup & Sync with Google" the Android app has, sharing one backup via the same Google
Cloud project. Read `BACKUP_FORMAT.md` first — the archive + managed-key format is the contract.

---

## Part 1 — console setup (do this once, by hand, before coding)

Use the **same Google Cloud project** as the Android app (the one that owns the Android OAuth
client). Sharing the project is what makes Drive's `appDataFolder` — and therefore the backup —
shared between iOS and Android.

1. **console.cloud.google.com → same project → APIs & Services → Credentials →
   Create credentials → OAuth client ID → Application type: iOS.**
   - Bundle ID: the iOS app's bundle identifier (e.g. `com.umer.filewall`).
   - Copy the resulting **iOS client ID** (looks like `NNN-xxxx.apps.googleusercontent.com`).
   - The **reversed client ID** (`com.googleusercontent.apps.NNN-xxxx`) is your redirect URL
     scheme.
2. **Google Drive API** is already enabled on this project (from Android). Nothing to do.
3. **OAuth consent screen** already exists (shared). If it's in *Testing*, add the tester's
   Gmail. Scope needed: `https://www.googleapis.com/auth/drive.appdata` (non-sensitive — no
   verification required).

---

## Part 2 — the prompt

```
Add "Sign in with Google" and Drive "Backup & Sync" to the FileWall iOS app. It must be
sign-in only — NO backup passphrase — and share one backup with the existing Android app.

HARD CONSTRAINTS (same as the main build): Xcode 14.2, Swift 5.7.2, iOS 16, and NO third-party
dependencies. That means do NOT add the GoogleSignIn SDK or AppAuth. Implement Google OAuth
natively with ASWebAuthenticationSession + PKCE, and talk to Drive with URLSession — mirroring
the Android app, which uses raw REST against the same endpoints.

## Configuration
- Store the iOS OAuth client ID (from the console step) in a constant / Info.plist.
- Add the reversed client ID as a URL scheme in Info.plist (CFBundleURLTypes), so the OAuth
  redirect can return to the app.
- Redirect URI: "<reversedClientID>:/oauth2redirect".

## OAuth flow (ASWebAuthenticationSession + PKCE, no SDK)
1. Generate a PKCE code_verifier (43–128 char random) and code_challenge = BASE64URL(SHA256(
   verifier)), method S256.
2. Authorization request to https://accounts.google.com/o/oauth2/v2/auth with:
   client_id, redirect_uri, response_type=code, scope=
   "openid email https://www.googleapis.com/auth/drive.appdata",
   code_challenge, code_challenge_method=S256, access_type=offline, prompt=consent.
   Present it with ASWebAuthenticationSession (prefersEphemeralWebBrowserSession = false so the
   user's Google session is reused).
3. On redirect, extract `code`, then POST to https://oauth2.googleapis.com/token with
   grant_type=authorization_code, code, code_verifier, client_id, redirect_uri. You get
   access_token, expires_in, refresh_token, id_token.
4. Persist the **refresh_token in the Keychain** (kSecAttrAccessibleAfterFirstUnlockThisDevice
   Only). Keep access_token in memory with its expiry.
5. Before any Drive call, if the access token is expired, refresh: POST /token with
   grant_type=refresh_token, refresh_token, client_id. Google iOS clients are public (no
   client secret) — do not send one.
6. Sign out = discard tokens and delete the refresh token from the Keychain.

Parse the signed-in email from the id_token payload (or the userinfo endpoint) for display.

## Drive REST — appDataFolder (identical shape to Android's DriveBackup)
Base: https://www.googleapis.com/drive/v3/files
Upload base: https://www.googleapis.com/upload/drive/v3/files
Auth header on every call: "Authorization: Bearer <access_token>".

- Find a file by name in the private space:
    GET {base}?spaces=appDataFolder&fields=files(id,name)&pageSize=100
  then match on `name`.
- Create: multipart POST to {upload}?uploadType=multipart&fields=id with a JSON metadata part
  { "name": <name>, "parents": ["appDataFolder"] } and the bytes part.
- Replace existing: PATCH {upload}/{id}?uploadType=media&fields=id with the new bytes.
- Download: GET {base}/{id}?alt=media.
- Last-backup time: GET {base}?spaces=appDataFolder&fields=files(id,name,modifiedTime).

Two files live in appDataFolder, exactly as Android writes them:
  filewall-backup.key      Base64 of 32 random bytes — the managed passphrase
  filewall-backup.fwvault  the encrypted archive, keyed from that

## Managed key — the "no passphrase" part
Backup and restore both call a managedPassphrase():
1. Look up filewall-backup.key. If present, download it; its UTF-8 text (trimmed) IS the
   passphrase.
2. If absent (first backup on this account), generate 32 random bytes, Base64-encode
   (no wrapping), upload as filewall-backup.key, and use that text.
Never prompt the user for a Drive passphrase. (Only the separate local "Save to Files" export
prompts — that file has no key beside it.)

## Backup / Restore
- Backup: p = managedPassphrase(); write the .fwvault archive (format per BACKUP_FORMAT.md,
  PBKDF2 210k → AES-256-CTR + HMAC-SHA256 over a ZIP) keyed from p to a temp file; upload it as
  filewall-backup.fwvault (create or PATCH). Then wipe p.
- Restore: download filewall-backup.fwvault (if none, "No backup found"); p = managedPassphrase()
  ; verify + decrypt with p; ingest. Then wipe p.
- Auto-backup: a BGProcessingTaskRequest that runs the same backup unattended — it needs only
  the signed-in account (refresh token in Keychain) and network, no stored passphrase.

## App Intents
Expose BackUpVaultIntent (ProvidesDialog with the outcome) and a "Sign in to Google" path.
BackUpVaultIntent requires local device authentication like the other content intents.

## Security notes to put in comments
- This is "as safe as the Google account": the managed key sits beside the data in the
  account's private appDataFolder, so whoever can reach that folder can restore. Google itself
  only ever stores the encrypted .fwvault and an opaque key file.
- No client secret in the app (public client + PKCE). Refresh token in Keychain, this-device-
  only, never synced to iCloud.

## Interop test
Make a backup on Android, then Restore on iOS (same Google account) — it must decrypt and
restore every file, folder, and the hidden/archived/deleted state. Then the reverse. If either
fails, the archive bytes or the managed-key handling diverged from BACKUP_FORMAT.md — fix to
match the spec, which is the source of truth.
```

---

## Why native OAuth instead of the GoogleSignIn SDK

The main prompt forbids third-party dependencies, and the Android app already proves the
raw-REST approach works. `ASWebAuthenticationSession` + PKCE is the Apple-blessed, dependency-
free way to do OAuth, and Google fully supports it for iOS "installed app" clients. If you
later relax the no-deps rule, the GoogleSignIn SDK would shorten the auth code — but it changes
nothing about the Drive REST or the shared backup format.
