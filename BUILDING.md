# Building FileWall in Android Studio

Step by step, from nothing to the app running on an Android 11 phone.

> **Read this first.** This code has never been through the Android Gradle Plugin — it was
> written in an environment with no Android SDK and with Google's Maven repository blocked.
> It was parse-checked with `kotlinc`, so the structure is sound, but **expect the first
> build to surface some errors**: a wrong import, a parameter that got renamed between
> library versions, that kind of thing. Step 6 covers what to do about them. This is normal
> for a first build, not a sign anything is badly wrong.

---

## 1. Install the tools

| Thing | Version | Notes |
|---|---|---|
| Android Studio | **Ladybug (2024.2.1)** or newer | AGP 8.7.3 will refuse to load in older versions |
| JDK | 17 or newer | Android Studio bundles one; you don't need a separate install |
| Android SDK Platform | **API 35** | compileSdk. Install via SDK Manager |
| Android SDK Platform | **API 30** | matches your test phone, useful for the emulator |

Download Studio from <https://developer.android.com/studio>.

After installing, open **Settings → Languages & Frameworks → Android SDK**:
- **SDK Platforms** tab → tick *Android 15 (API 35)* and *Android 11 (API 30)*
- **SDK Tools** tab → tick *Android SDK Build-Tools*, *Android SDK Platform-Tools*, and
  *Google Play services*
- Apply, let it download.

---

## 2. Get the code

The work is on the branch `claude/i-am-irrcl6` (PR #1), not `main`.

**Option A — clone from the command line:**

```bash
git clone https://github.com/wrcovid4-max/filewallAS.git
cd filewallAS
git checkout claude/i-am-irrcl6
```

**Option B — straight from Android Studio:**
- Welcome screen → **Get from VCS**
- URL: `https://github.com/wrcovid4-max/filewallAS.git` → **Clone**
- Once open: bottom-right branch selector → **Remote** → `origin/claude/i-am-irrcl6`
  → **Checkout**

---

## 3. Open it and let Gradle sync

**File → Open** → pick the `filewallAS` folder (the one with `settings.gradle.kts` in it —
not a subfolder, not the `app` folder).

Studio will start a Gradle sync automatically. **The first one takes 5–15 minutes** — it is
downloading Gradle 8.11.1, the Android Gradle Plugin, Compose, Room, media3, Play Services
and the rest. Watch the progress bar at the bottom. Leave it alone until it finishes.

If a banner appears offering to upgrade AGP or Gradle: **decline it.** The versions are
pinned deliberately and an upgrade will introduce changes unrelated to anything you're
trying to fix.

**If sync fails:**
- *"SDK location not found"* → Studio usually offers a one-click fix. Otherwise create a
  file called `local.properties` in the project root containing
  `sdk.dir=/path/to/your/Android/Sdk`
- *"Failed to find Build Tools"* or a missing platform → the error text names exactly what
  is missing; install it in the SDK Manager and re-sync.

---

## 4. Set up your phone

On the Android 11 phone:

1. **Settings → About phone** → tap **Build number** seven times. It'll say you're now a
   developer.
2. **Settings → System → Developer options** → turn on **USB debugging**.
3. Plug it into the computer. A dialog appears on the phone asking to *Allow USB debugging*
   — tick **Always allow** and accept.
4. In Android Studio, the device dropdown in the toolbar should now show your phone by name.

If it doesn't appear: try a different USB cable (many are charge-only), and on the phone's
USB notification switch the mode from *Charging* to *File transfer*.

---

## 5. Run the phone app

1. In the toolbar, set the run configuration dropdown to **app**.
2. Set the device dropdown to your phone.
3. Press **Run** (the green ▶, or Shift+F10).

Studio will build, install and launch it.

---

## 6. When the build fails

It probably will, at least once. Here's how to work through it efficiently.

The **Build** tab at the bottom lists every error with a file and line number. Click one and
Studio jumps there.

**Fix the top error first, then rebuild.** Kotlin errors cascade: one unresolved symbol can
generate twenty downstream complaints that all disappear when you fix the first one. Don't
try to fix them all at once — you'll be fixing phantoms.

The likely categories, and what they mean:

| Error | What it usually is | Fix |
|---|---|---|
| `Unresolved reference: <something>` | An import that doesn't exist in this library version | Put the cursor on the red symbol and press **Alt+Enter** → *Import* |
| `None of the following candidates is applicable` | A function's parameters changed between versions | Ctrl+click the function to see its real signature, adjust the call |
| `Type mismatch` | A nullable/non-null mismatch | Usually a `?:` or `!!` in the right place |
| `@Composable invocations can only happen from...` | A composable called outside a composable | Move the call inside the composable body |

**If you get stuck, just paste the error text to me** — file, line and message. That's enough
for me to fix it in the repo and push. Don't spend an hour on something I can turn around in
a minute.

Handy commands if you'd rather use the terminal (the **Terminal** tab at the bottom of
Studio):

```bash
./gradlew :app:assembleDebug        # build the phone APK
./gradlew :app:testDebugUnitTest    # run the unit tests
./gradlew :wear:assembleDebug       # build the watch APK
./gradlew clean                     # if things get weird
```

---

## 7. First run — what to do in the app

1. **Open Vault** tab → tap the upload button (bottom right) → pick some photos. They get
   encrypted on the way in.
2. **Hidden** tab → it asks you to set a 4-digit PIN, then confirm it. That's the passcode
   for the hidden archive.
3. **Security** tab → turn on **Fingerprint & Face ID** if you want biometric unlock.
4. To hide a file: long-press it in the vault to select, then **Move to Hidden**.

Note that **screenshots are blocked by default** — that's `FLAG_SECURE`, deliberate. If you
want to capture the screen, Security → **Allow Screenshots**.

Also worth knowing: the hidden archive auto-locks after **15 seconds** of inactivity out of
the box, which is aggressive on purpose for testing. Security → *Inactivity Auto-Lock* to
change it.

---

## 8. The Wear OS app (optional)

The watch app only does anything useful with the phone app installed and paired.

**On a real watch:**
1. On the watch: **Settings → System → About → Build number**, tap seven times.
2. **Settings → Developer options** → enable **ADB debugging** and **Debug over Wi-Fi**.
   It'll show an IP address.
3. On your computer: `adb connect <watch-ip>:5555`
4. In Studio, set the run configuration to **wear** and the device to your watch → **Run**.

**On an emulator** (fine for testing the link):
- **Tools → Device Manager → Create device** → *Wear OS* category → pick *Wear OS Large
  Round*, API 30+.
- Pair it to a phone emulator, or to your real phone via the Wear OS companion app.

Then open FileWall on the watch — it asks the phone for a manifest and shows your unlocked
files. Hidden files never appear there by design.

---

## 9. Google Drive backup (optional, needs setup)

Everything else works without this. Drive sign-in will fail with *"This build has no Google
OAuth client"* until you do the following — that message is expected, not a bug.

1. Get your debug signing fingerprint:
   ```bash
   ./gradlew :app:signingReport
   ```
   Copy the **SHA1** line under `Variant: debug`.
2. Go to <https://console.cloud.google.com/> and create a project.
3. **APIs & Services → Library** → search *Google Drive API* → **Enable**.
4. **APIs & Services → OAuth consent screen** → configure it (External is fine) → under
   *Scopes*, add `https://www.googleapis.com/auth/drive.appdata`. Add your own Google
   account under *Test users*.
5. **APIs & Services → Credentials → Create Credentials → OAuth client ID**:
   - Application type: **Android**
   - Package name: `com.filewall`
   - SHA-1: the fingerprint from step 1
6. Rebuild and sign in from the Security tab.

No client ID goes into the code — Google matches on package name and signing certificate.
If you later build a signed release APK, register that certificate's SHA-1 too.

**Meanwhile**, the *Encrypted Archive* card on the Security tab works right now with no
setup: it exports your whole vault as one passphrase-protected `.fwvault` file anywhere you
like, and restores from it.

---

## 10. Building a release APK

```bash
./gradlew :app:assembleRelease
```

This needs a signing key. To make one:
**Build → Generate Signed App Bundle / APK → APK → Create new…**

Keep the keystore file and its password somewhere safe — losing it means you can never
update an installed app in place.

Release builds have R8 minification on. If something works in debug but breaks in release,
it's almost always a ProGuard rule; `app/proguard-rules.pro` is where that lives.
