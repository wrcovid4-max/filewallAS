# Google Drive backup — one-time setup

FileWall's "Backup & Sync with Google" uses Google Sign-In + the Drive **`appDataFolder`**
(an app-private folder Google can't browse and you can't see in the Drive UI). The code is
already wired (`DriveBackup.kt`); it only needs an OAuth client registered so Google trusts
this build. This is a console task — no code required.

## The situation

- Rebuilt app package (this repo): **`com.filewall`**
- Original app in your Firebase project: **`com.umer.filewall`**
- Drive's `appDataFolder` is scoped **per Google Cloud project, not per package** — so
  reusing the existing `FIREWALL` project means any old backup is still reachable, and a
  future iOS app added to the same project shares the same folder.

Because the packages differ, just register the new one alongside the old — a project can hold
many Android apps.

## Steps (reuse the existing `FIREWALL` project)

1. **Get the signing fingerprint** of the build. In the project root:
   ```
   ./gradlew signingReport
   ```
   Under `Variant: debug`, copy the **SHA1** line (and **SHA-256** if it asks for one).

2. **Firebase console → your `FIREWALL` project → Add app → Android:**
   - Android package name: **`com.filewall`**
   - Debug signing certificate SHA-1: paste from step 1
   - (You can skip downloading `google-services.json` — this app uses plain Google Sign-In,
     not the Firebase SDK. The registration is what matters.)

3. **Enable the Drive API** for the project:
   Google Cloud Console → same project → **APIs & Services → Library → Google Drive API →
   Enable**. (Firebase projects *are* Google Cloud projects; use the same project.)

4. **OAuth consent screen** (Cloud Console → APIs & Services → OAuth consent screen):
   - If the app is in **Testing**, add your Gmail under **Test users**, or sign-in returns
     "access denied".
   - Scope needed: `https://www.googleapis.com/auth/drive.appdata` (non-sensitive; no
     verification required for personal/testing use).

5. **Run the app → Security tab → Sign in with Google → Backup.** If it fails with a
   developer-config error (status 10), the SHA-1/package pair doesn't match a client in the
   project — recheck step 1–2.

## ⚠️ If you wipe/reinstall the Mac

The debug key at `~/.android/debug.keystore` is regenerated on a fresh machine, so its SHA-1
**changes** and the one you registered stops matching. Either:
- do this setup **after** the wipe with the new SHA-1, or
- copy `~/.android/debug.keystore` off the Mac first and restore it, so the SHA-1 never
  changes.

For a release build, register the **release** keystore's SHA-1 too (get it from
`signingReport` under `Variant: release`, or `keytool -list -v -keystore <your.jks>`).

## Cross-platform with the iOS app

Add the iOS app's OAuth client under **this same project** (Cloud Console → Credentials →
Create OAuth client → iOS, bundle ID of the iOS app). Same project → same `appDataFolder` →
one backup shared between Android and iOS. The archive format both must read/write is in
`BACKUP_FORMAT.md`.

## Alternative: match the original package exactly

If you'd rather the rebuilt app *be* `com.umer.filewall` (reusing the existing registration
directly), change `applicationId` in `app/build.gradle.kts` to `com.umer.filewall` — the code
namespace stays `com.filewall.*`. Note it then installs as a **different app** on the phone,
so the current test vault stored under `com.filewall` won't carry over.
