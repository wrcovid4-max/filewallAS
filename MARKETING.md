# FileWall — marketing reference

Source material for a product site, store listing, or press kit. Everything in the
**Features** and **Claims you can make** sections is true of the code in this repo. The
**Do not claim** section exists because security marketing is where honest products lose
their credibility fastest.

---

## Positioning

**One-liner:** Your own private cloud, on your own phone.

**Elevator:** FileWall is an encrypted vault for the files you don't want in anyone else's
cloud. Photos, videos and documents are encrypted with keys that never leave your phone's
security chip. There's a hidden archive behind a passcode, a companion app for your watch,
and backup that Google stores but cannot read.

**Category:** Private file vault / offline-first personal cloud.

**Who it's for:**
- People who want Google Drive's convenience without Google reading the contents
- Anyone who hands their unlocked phone to other people — kids, colleagues, repair shops
- Photographers and journalists carrying material that shouldn't be casually browsable
- Privacy-minded users who'd rather own their storage than rent it

**Tone:** Calm, precise, technical without being cold. This is a product that explains its
own trade-offs. It does not shout, and it does not use fear.

---

## The three claims that carry the product

1. **The keys never leave your phone.** Encryption keys are generated inside the Android
   Keystore — hardware-backed on most devices — and cannot be exported by anyone, including
   FileWall. Copy the app's data folder to another phone and it's inert.

2. **Even the backup is unreadable to us and to Google.** Cloud backup uploads a single
   file that was already encrypted with a passphrase only you know, into Drive's private
   app folder. There is no FileWall server. There is no account to create.

3. **Nothing on disk says what anything is.** File names, types and folders live only in
   the app's private database. The stored blobs have no names, no extensions, no metadata.

---

## Features

### The vault
- Import photos, videos and documents through the system picker — no storage permission
  ever requested
- Grid and list views, with sort by date added, name, size or type
- Instant search by name
- Colour-coded folders with rename, recolour, delete
- Multi-select for batch move, hide and delete
- Live storage breakdown by category
- Export anything back out, anywhere you like

### The hidden archive
- A second vault behind a 4-digit passcode, invisible until unlocked
- Set-then-confirm on first use — it can't end up open but unprotected
- Fingerprint and face unlock, with an option to disable the PIN fallback entirely
- Escalating lockout after five wrong attempts (30s, 60s, 120s… to five minutes)
- Inactivity auto-lock: 15s, 30s, 1m, 5m, or never
- Locks the moment you leave the app, whatever the timer says

### Viewing
- Pinch-to-zoom photo viewer
- **Video plays straight from the encrypted file** — no temporary decrypted copy on disk,
  and scrubbing works normally
- Item details: name, type, size, date added
- Documents and other formats open in the app you already trust, through a temporary grant
  that's wiped on lock

### Privacy controls
- **Screenshots blocked by default** — vault contents stay out of screenshots, screen
  recordings, and the app-switcher preview
- Device media sync can be switched off entirely
- System / light / dark themes

### Backup
- **Encrypted Archive:** export the whole vault as one passphrase-protected `.fwvault` file,
  to anywhere — SD card, USB, another cloud. Restore the same way.
- **Google Drive:** the same encrypted file, in Drive's private app folder. Manual, or a
  daily schedule that runs on Wi-Fi.
- Restore is verified end-to-end before a single file is written

### Wear OS companion
- Browse your unlocked files from your wrist
- Photos open on the watch
- Video and documents show a preview plus **Open on phone**, which sends a notification to
  your phone rather than opening anything remotely
- **Hidden files never reach the watch** — they aren't in the data the watch receives, so a
  lost watch can't even ask

---

## Claims you can make

Technical, and all verifiable in the source:

- AES-256 encryption, keys generated in and confined to the Android Keystore
- Encrypt-then-MAC construction with HMAC-SHA256 — tampering is detected, not decrypted
- Integrity verified before any content is decoded or handed to another app
- Passcodes stored as PBKDF2-HMAC-SHA256 with 120,000 iterations and a random salt
- Backup archives keyed with PBKDF2-HMAC-SHA256 at 210,000 iterations
- **No account. No FileWall server. No sign-up.**
- **No ads, no trackers, no analytics, no telemetry** — the app makes no network request
  except to Google Drive, and only when you ask it to
- **No storage permissions requested** — everything comes in through the system picker
- Works completely offline
- Excluded from Android cloud backup and device-transfer, so the vault can't leak that way
- Open source — the encryption is there to be read

---

## Do not claim

Security marketing is where credible products lose credibility. Avoid:

- ❌ "Military-grade encryption" — meaningless, and a tell that the writer doesn't know the
  subject. Say AES-256 and let it stand.
- ❌ "Unbreakable" / "hack-proof" / "NSA-proof"
- ❌ "Audited" or "certified" — no audit has happened. Say so if asked.
- ❌ "End-to-end encrypted" — that phrase means something specific about data moving between
  people. Say "encrypted before it leaves your phone."
- ❌ "Zero-knowledge" as a formal claim — describe the property instead: nobody but you has
  the passphrase.
- ❌ HIPAA / GDPR / SOC 2 compliance
- ❌ Anything about iOS, a web app, or a desktop client
- ❌ Fear-based copy about hackers and surveillance. The product's appeal is control, not
  anxiety.

**Also worth stating plainly somewhere:** if you forget your backup passphrase, the archive
is unrecoverable. That's the point, and users should learn it from the website rather than
from experience.

---

## Visual identity

Pulled from the app itself, so the site and the product match.

| Role | Hex | Use |
|---|---|---|
| Background | `#0E1626` | Deep navy. The dominant surface. |
| Card | `#141E31` | Slightly raised panels |
| Border | `#2A3652` | Hairline outlines, 1px |
| Primary | `#B4C5FF` | Periwinkle. Buttons, links, active states. |
| On-primary | `#24136B` | Dark indigo text on periwinkle fills |
| Accent | `#4A1391` | Saturated purple. Media surfaces, hero moments. |
| Text | `#EDF1FA` | Primary |
| Text muted | `#A9B4C9` | Secondary |
| Photos | `#4CAF50` | Category green |
| Videos | `#2196F3` | Category blue |
| Documents | `#FFC107` | Category amber |

**Type:** heavy sans (Inter, Manrope or system). Headlines bold to extra-bold with tight
negative letter-spacing. Body at a generous line height.

**Shape language:** 20px radius on cards, fully-rounded pills for buttons and toggles.
Nothing sharp.

**Logo:** a quartered shield — the crest on the Security tab. Shield silhouette with the
top-left quadrant knocked out.

**Mood:** dark-first, spacious, confident. Nothing neon, no lock-and-chain clip art, no
binary-rain backgrounds, no hooded figures.
