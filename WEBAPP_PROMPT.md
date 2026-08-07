# Web app prompt — FileWall in the browser

One URL. Click it, it opens, you use it. No install, no terminal, no account — the way
x.com or youtube.com open.

## What makes this possible

The browser has grown up enough to hold a real vault:

- **OPFS** (Origin Private File System) — actual file storage, private to the origin, not
  visible in the user's Downloads or anywhere else.
- **WebCrypto** — AES-256-GCM in hardware-accelerated native code.
- **Non-extractable `CryptoKey`** — a key object you can store in IndexedDB where the
  browser persists it and JavaScript can never read its bytes. The closest web analogue to
  the Android Keystore, and it's genuinely close.
- **WebAuthn PRF** — derives a stable secret from Touch ID / Face ID / Windows Hello. This
  is what makes biometric unlock possible in a web page.
- **Service Worker + Range requests** — lets `<video>` scrub through an encrypted file with
  no decrypted copy anywhere.

No server. No backend. Nothing to run.

## The honest limits — read before building

Three things are genuinely worse than the native app, and the UI has to say so rather than
let people find out:

1. **Browser storage can be evicted.** Without `navigator.storage.persist()`, the browser
   may clear the vault under storage pressure. Safari is stricter still — non-persisted
   storage can be cleared after ~7 days of not visiting the site.
2. **"Clear browsing data" destroys the vault.** Permanently. There is no recovery and no
   copy anywhere else. Export backups matter far more here than on a phone.
3. **A local attacker with the page open can read it.** Non-extractable keys stop the *key*
   from being stolen, but devtools can still call `decrypt` on an unlocked vault. This
   protects against someone idly browsing your files. It is not a defence against a
   forensic examination of an unlocked machine.

These are not reasons to skip building it. They are reasons the app should push exports
hard and be honest in onboarding.

## Deploying it without a terminal

The output is a folder of static files. Any of these gets you a URL in about two minutes:

- **Netlify Drop** — go to `app.netlify.com/drop`, drag the folder onto the page. Done, and
  you get a URL immediately without even signing in.
- **Cloudflare Pages** — dashboard → Create → Upload assets.
- **GitHub Pages** — upload the files through github.com's web UI, then Settings → Pages.
- **Vercel** — import the repo from the web dashboard.

All four serve HTTPS, which is required: WebCrypto, OPFS and service workers all refuse to
run on plain HTTP.

---

## The prompt

```
Build FileWall as a web app: an encrypted file vault that runs entirely in the browser with
no server, no backend and no account. A user clicks one URL and starts using it immediately,
the way x.com or youtube.com open.

There are existing Android and Apple implementations. Match their behaviour and their
security posture. Where the browser cannot do something they do, say so in the UI rather
than pretending.

## Hard constraints

- Static files only. No build step, no npm, no bundler, no framework — plain HTML, CSS and
  ES modules. The output must be a folder someone can drag onto Netlify Drop.
- No backend, no API, no database server, no analytics, no third-party scripts. Zero
  network requests after the initial page load.
- Must work offline after first visit.
- Installable as a PWA so it gets a home screen icon and its own window.

## What the product is

A private vault for photos, videos and documents. Files are encrypted in the browser with
keys the page cannot read. There is a second "hidden" vault behind biometrics or a passcode.
Everything stays on the device.

## Storage architecture

**Files — OPFS**
`navigator.storage.getDirectory()`. Blobs stored under UUID filenames with no extension.
Do all file IO and crypto inside a **Web Worker** using `createSyncAccessHandle()` — it is
substantially faster than `createWritable()` and only available in workers. The main thread
should never block on a decrypt.

**Metadata — IndexedDB**
Object stores for files and folders: name, mime type, size, dateAdded, folderId, hidden,
category, thumbnail blob key. Names live only here; nothing in OPFS reveals what a blob is.

**Persistence — request it explicitly**
Call `navigator.storage.persist()` on first use and show the result. If it is denied, say
plainly in the UI that the browser may clear the vault, and that the user should keep an
exported backup. Show `navigator.storage.estimate()` in the storage breakdown so quota is
visible rather than a surprise.

## Crypto

**Key hierarchy**
- A vault data key: `crypto.subtle.generateKey({name:'AES-GCM', length:256}, false, ...)`.
  Note the `extractable: false` — this is the whole point. Store the resulting CryptoKey
  object *directly* in IndexedDB; it structured-clones, the browser persists it, and no
  script can ever read its raw bytes. This is the closest the web gets to the Android
  Keystore, and it is worth a comment saying so.
- Unlock derives a wrapping key by one of two paths:
  - **WebAuthn PRF** where available (Chrome 116+, Safari 18+): a passkey's PRF extension
    yields a stable secret from Touch ID / Face ID / Windows Hello. Run it through HKDF.
    This is the browser's biometric unlock, and it is the good path.
  - **Passphrase fallback** everywhere else: PBKDF2-HMAC-SHA256, 600,000 iterations, random
    16-byte salt. Feature-detect and choose; never assume PRF exists.

**File format — chunked AES-GCM**
1 MiB chunks, each sealed independently, nonce per chunk derived from a per-file random base
plus the chunk index. Header carries magic, version, base nonce, chunk size.

Chunking is not optional here. `crypto.subtle.encrypt` takes a whole ArrayBuffer, so a
single-shot encrypt of a 2 GB video would try to hold it all in memory and kill the tab.
Chunking also gives random access, which the video player below depends on.

**Portable archive**
Export the whole vault as one `.fwvault` file: PBKDF2 at 600,000 iterations from a
passphrase, same chunked-GCM body. Byte-compatible with the native apps' format if you can
manage it — one format across three platforms is worth real effort.
Import through a file picker or drag-and-drop.

## Video — the interesting part

Do not decrypt a whole video into a Blob URL. A 500 MB file becomes 500 MB of tab memory and
the browser will kill it.

Instead: register a **Service Worker** that intercepts `/vault-stream/<id>` requests, reads
the HTTP `Range` header, decrypts only the chunks covering that range, and responds 206
Partial Content with the right `Content-Range`. Point `<video src="/vault-stream/<id>">` at
it.

The browser's native player then does seeking, buffering and scrubbing for free, and
plaintext exists only in the response stream. This is the direct counterpart to the Android
app's custom media DataSource and the iOS AVAssetResourceLoaderDelegate.

## The interface

Three sections, matching the native apps.

**Vault** — masthead with the app name and storage used. Search field. An Unlocked/Hidden
pill toggle. A toolbar with the item count, sort (date added / name / size / type), sort
direction, grid-or-list toggle, and multi-select. A row of colour-coded folder cards, each
with an overflow menu of Rename / Colour / Delete, and a "New Folder" card. Then a grid of
file tiles with a thumbnail, name and a type badge.

Import by drag-and-drop anywhere on the page, plus a floating upload button opening a file
picker. Show real progress while encrypting — a 200 MB import is not instant and a frozen
UI reads as broken.

**Hidden** — a 4-digit passcode pad, or the WebAuthn prompt when a passkey is registered.
First use walks set-then-confirm so the hidden side can never end up open but unprotected.
Escalating lockout after five wrong attempts.

**Security** — storage breakdown with a segmented bar (photos green, videos blue, documents
amber). Toggles for the hidden vault, biometric unlock, and PIN fallback. Appearance:
system / light / dark. Inactivity auto-lock: 15s / 30s / 1m / 5m / never. Export and import
of the encrypted archive. Persistent-storage status. And an honest "Limits" panel — see
below.

**Viewer** — full-screen, pinch and scroll to zoom images, the native video player for
video, and an Item Details panel with name, type, size, date added, and Export / Move /
Rename / Delete.

## Visual design

Match the native apps exactly so all three feel like one product:

  Background      #0E1626   deep navy, dominant surface
  Card            #141E31   raised panels
  Border          #2A3652   1px hairline
  Primary         #B4C5FF   periwinkle — buttons, links, active states
  On-primary      #24136B   dark indigo on periwinkle fills
  Accent          #4A1391   saturated purple — the viewer stage
  Text            #EDF1FA   primary
  Text muted      #A9B4C9   secondary
  Photos          #4CAF50
  Videos          #2196F3
  Documents       #FFC107

Heavy sans (Inter or system stack), bold headlines with tight negative letter-spacing.
20px radius on cards, fully-rounded pills for buttons and toggles. Dark by default, light
theme available. Nothing sharp, no neon, no lock-and-chain iconography.

Logo: a quartered shield — shield silhouette with the top-left quadrant knocked out, as
inline SVG.

Fully responsive. Must be genuinely usable on a phone, since that is where people will open
the link.

## PWA

- `manifest.json`: name, short name, the shield icon at 192/512, `display: standalone`,
  `theme_color: #0E1626`, `background_color: #0E1626`.
- Service worker precaching the shell so it opens offline.
- Never cache vault content in the SW cache — that would be a plaintext copy in a place
  nothing else wipes.

## Honest limits — build this into the UI, not just the docs

A "Limits" panel in Security, in plain language, no hedging:

- This vault lives in your browser's storage. Clearing your browsing data erases it
  permanently. There is no copy and no recovery.
- If the browser denies persistent storage, it may clear the vault when the device runs low
  on space. Safari can clear it after about a week of not visiting.
- Export a backup. Show when the last one was made, and nudge if it is old or absent.
- The vault protects against someone browsing your files. It is not a defence against
  someone examining an unlocked machine with developer tools.

Say all of it. A vault that overstates itself is worse than one that does not exist, because
someone will trust it with something they shouldn't.

## First-run

No sign-up, no account, no email, no onboarding carousel. The vault is usable within one
second of the page loading. Ask for a passcode the first time the hidden section is opened,
not before — the first screen should be the empty vault with an obvious way to add files.

## Browser support

- Chrome / Edge 108+, Safari 15.2+, Firefox 111+ for OPFS.
- Feature-detect WebAuthn PRF and fall back to the passphrase without comment.
- `showSaveFilePicker` is Chromium-only; fall back to an `<a download>` blob elsewhere.
- If OPFS is missing entirely, show a clear "this browser can't run FileWall" screen naming
  what is missing — never a half-working vault.

## Code quality

Plain ES modules with real separation: crypto, storage, and UI in different files. Crypto
and file IO in a Worker, UI on the main thread, `postMessage` between them. No global state
soup. Comments explaining why, especially in the crypto, where reasoning is the only thing
that survives a refactor.
```

---

## Follow-up prompts

- *"Write the crypto worker only — chunked AES-GCM, the non-extractable key in IndexedDB,
  WebAuthn PRF unlock with the PBKDF2 fallback. Plus a test page that round-trips files at
  the chunk boundaries: 0, 1, 1 MiB − 1, 1 MiB, 1 MiB + 1 bytes."*
- *"Now the service worker that serves decrypted Range requests, and wire a `<video>` to it.
  Show me seeking working on a 500 MB file without memory growth."*
- *"The vault screen: masthead, search, Unlocked/Hidden pill, toolbar, folder row, file grid.
  Drag-and-drop import with real progress."*
- *"Make the archive format byte-compatible with the native `.fwvault` so one backup restores
  on any of the three platforms."*

## One thing worth knowing

If all you want is a working link to try, ask me to build it directly rather than running
this prompt elsewhere — a single-file version can be published straight to a URL from this
conversation. The prompt above is for when you want the source in your own hands, on your
own domain.
