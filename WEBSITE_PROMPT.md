# Website generation prompt

Paste the block below into v0, Lovable, Bolt, a Claude artifact, or any code assistant.
Everything factual in it is true of the app — see `MARKETING.md` for the source.

---

```
Build a marketing website for FileWall, an encrypted file vault app for Android with a
Wear OS companion. Single-page, dark-first, production quality. Ship it as a self-contained
HTML file with inline CSS and JS — no build step, no external CDNs, no frameworks required.

## The product

FileWall is a private vault for photos, videos and documents. Encryption keys are generated
inside the phone's hardware security chip and cannot be exported. There is no account, no
server, no sign-up. Cloud backup uploads a file that Google stores but cannot read.

Positioning line: "Your own private cloud, on your own phone."

Audience: privacy-minded Android users, people who hand their unlocked phone to others,
photographers and journalists. They are technically literate and allergic to marketing
fluff. Assume they will read the details and notice if something is overstated.

Tone: calm, precise, confident. Technical without being cold. This is a product that
explains its own trade-offs rather than hiding them. Never use fear.

## Visual direction

Dark-first. These are the app's real colours — match them exactly so the site and product
feel like one thing:

  Background      #0E1626   deep navy, the dominant surface
  Card            #141E31   slightly raised panels
  Border          #2A3652   1px hairline outlines
  Primary         #B4C5FF   periwinkle — buttons, links, active states
  On-primary      #24136B   dark indigo text on periwinkle fills
  Accent          #4A1391   saturated purple — media surfaces, hero moments
  Text            #EDF1FA   primary
  Text muted      #A9B4C9   secondary
  Photos          #4CAF50   category green
  Videos          #2196F3   category blue
  Documents       #FFC107   category amber

Type: heavy sans (Inter or Manrope via system stack). Headlines bold to extra-bold with
tight negative letter-spacing (-0.02em). Body 16-18px at 1.6 line height.

Shapes: 20px radius on cards, fully-rounded pills for buttons. Nothing sharp anywhere.

Logo: a quartered shield — shield silhouette with the top-left quadrant knocked out.
Draw it as inline SVG, do not use an emoji or an icon font.

Mood: spacious, restrained, confident. Generous whitespace. Subtle depth from the
navy/card contrast rather than heavy shadows.

Explicitly avoid: neon gradients, lock-and-chain clip art, binary rain, hooded figures,
green-on-black "hacker" aesthetics, stock photos of people looking at laptops. If it would
look at home on a VPN affiliate site, it is wrong.

## Page structure

1. HERO
   Headline: "Your own private cloud, on your own phone."
   Sub: one sentence on files being encrypted with keys that never leave the device.
   Two buttons: "Get FileWall" (primary) and "Read the source" (ghost, links to GitHub).
   Right side or below: a phone mockup drawn in CSS/SVG showing the vault screen — a dark
   navy app with a bold "FileWall" header, a storage line reading "269.4 MB USED", a search
   field, an Unlocked/Hidden pill toggle, coloured folder cards, and a grid of file
   thumbnails. Do not use an <img>; draw it.

2. THREE PILLARS (three cards)
   - "The keys never leave your phone" — generated inside the Android Keystore,
     hardware-backed on most devices, exportable by nobody including us. Copy the app's
     data to another phone and it is inert.
   - "Even the backup is unreadable" — cloud backup uploads a file already encrypted with a
     passphrase only you know. There is no FileWall server. There is no account.
   - "Nothing on disk says what anything is" — names, types and folders live only in the
     app's private database. Stored files have no names and no extensions.

3. FEATURES — grouped, scannable, with a small icon per group
   The vault: system-picker import with no storage permission ever requested; grid and list
   views; sort by date, name, size or type; instant search; colour-coded folders;
   multi-select; live storage breakdown; export anywhere.
   The hidden archive: a second vault behind a passcode; fingerprint and face unlock, with
   an option to disable the PIN fallback; escalating lockout after five wrong attempts;
   inactivity auto-lock from 15 seconds to never; locks the instant you leave the app.
   Viewing: pinch-to-zoom photos; video that plays straight from the encrypted file with no
   temporary decrypted copy on disk, scrubbing included; documents open in the app you
   already trust.
   Privacy controls: screenshots blocked by default, which also keeps the vault out of
   screen recordings and the app-switcher preview; device media sync can be switched off;
   system, light and dark themes.
   Backup: export the whole vault as one passphrase-protected file to anywhere; or Google
   Drive, manual or on a daily Wi-Fi schedule. Restores are verified before anything is
   written.

4. WEAR OS SECTION — visually distinct, ideally a round watch face drawn in CSS
   "It's on your wrist, not in your pocket." Browse unlocked files from the watch. Photos
   open there. Video and documents show a preview plus an Open on phone button that sends a
   notification instead of opening anything remotely.
   Lead with the strongest line: hidden files never reach the watch — they are not in the
   data the watch receives, so a lost watch cannot even ask for them.

5. HOW THE ENCRYPTION WORKS — a technical section, not a dumbed-down one
   This audience wants the detail. Include:
   - AES-256, keys generated in and confined to the Android Keystore
   - Encrypt-then-MAC with HMAC-SHA256, so tampering is detected rather than decrypted
   - Integrity verified before anything is decoded or handed to another app
   - Passcodes stored as PBKDF2-HMAC-SHA256, 120,000 iterations, random salt
   - Backup archives keyed with PBKDF2-HMAC-SHA256 at 210,000 iterations
   Render the file format as a monospace diagram:
   [ magic "FWV1" 4B ][ iv 16B ][ AES-256-CTR ciphertext … ][ HMAC-SHA256 32B ]
   Add one honest line explaining the CTR choice: GCM will not release verified plaintext
   until it has buffered the whole message, which would mean holding an entire video in
   RAM — CTR streams in constant memory and can be seeked, and the trailing HMAC supplies
   the integrity GCM would have given us.

6. WHAT WE DON'T DO — a short, punchy list. This is the emotional core of the page.
   No account. No server. No sign-up. No ads. No trackers. No analytics. No telemetry.
   No storage permissions. No subscription.

7. HONEST LIMITS — a genuinely unusual section, keep it
   - If you forget your backup passphrase, the archive is unrecoverable. That is the point.
   - No security audit has been done.
   - Android only. No iOS, no web, no desktop.
   - Google Drive backup needs your own Google Cloud OAuth client (a one-time setup).

8. FAQ — accordion, 6-8 entries
   Can FileWall staff see my files? / What happens if I lose my phone? / Is it really free? /
   Why do I need a passphrase for backup when I already have a PIN? / Does it work offline? /
   What happens to my files if I uninstall? / Why Android only?

9. FOOTER
   Logo, GitHub link, privacy policy link, "© 2026 Vault Security Lab".

## Requirements

- Fully responsive; must read well at 375px wide.
- Respect prefers-reduced-motion. Keep animation to subtle scroll reveals and nothing else.
- Semantic HTML, real heading hierarchy, keyboard-navigable accordion, visible focus rings,
  AA contrast throughout.
- Every device mockup drawn in CSS or SVG. No image files, no placeholder services.
- No external requests of any kind.

## Copy rules — these matter

Never write "military-grade encryption", "unbreakable", "hack-proof", "bank-level security",
or "NSA-proof". Say AES-256 and let it stand.

Do not claim the app is audited or certified — it is not.
Do not use the phrase "end-to-end encrypted"; say "encrypted before it leaves your phone".
Do not claim HIPAA, GDPR or SOC 2 compliance.
Do not invent testimonials, user counts, star ratings, press logos or awards.
Do not invent pricing. The app is free and open source.

Write short declarative sentences. Prefer a concrete detail over an adjective: "keys
generated inside the phone's security chip" beats "ultra-secure encryption" every time.
```

---

## Follow-up prompts

Once you have the first draft:

- *"The hero mockup doesn't look like a real Android app — make the phone frame narrower,
  add a status bar with a clock and battery, and make the folder cards tinted rectangles
  with a coloured 1.5px border and a folder icon."*
- *"Rewrite section 6 as a two-column grid of struck-through items — it should feel like a
  list of things being removed."*
- *"The technical section is too dense. Break the file-format diagram into its own card with
  a caption under each segment."*
- *"Add a comparison table against Google Drive and Google Photos. Rows: reads your files,
  needs an account, works offline, files encrypted before upload, source code readable."*
- *"Generate a matching privacy policy page in the same style. It should be short, because
  we collect nothing."*
