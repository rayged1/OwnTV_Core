# OwnTV Core — Changelog

> Release notes: two parts per version — New features (by name) and Fixes. This file feeds the GitHub
> release body. Tags are prefixed `core-`; a core version never lines up with a TV or mobile app version.
>
> **Rule: bullet points only — no descriptions.** Each line is a short bolded feature/fix title and
> nothing more. The only extra detail ever allowed is a contribution credit for community work. A
> release that changes the database, backup format or anything a consuming app must adapt to says so
> in one bolded line of its own (e.g. **Database v46 · Backup v24 · Breaking**). The detail — what,
> why, files and verification — belongs in the commit message, never here.

## core-1.0.72-rayge.16 — 2026-11-21

### 🐛 Fixes

- **The engine chip no longer says MPV before the stream starts on ExoPlayer**
- **A decoder that gives up no longer rewinds the saved copy over and over**

## core-1.0.72-rayge.15 — 2026-11-20

### ✨ New features

- **Daily sync window — refresh every source together, once a day**

## core-1.0.71 — 2026-11-09

### ✨ New features

- **🔎 Sports: competitions are found by every name they go by — F1, EPL, La Liga, Formel 1 and more**
- **🎚️ "Show likely channels" in a match page's filter**
- **🎬 Player: title art for the film or show playing, and a 4K check for the badge**
- **⏺️ Recordings: every "Record every showing" rule, with its channel logo, for a list with Stop**

### 🐛 Fixes

- **🏁 Sports: Formula 1 no longer matches Formula E, Ligue 1 no longer Ligue 2, Bundesliga no longer 2. Bundesliga**
- **🏆 Sports: a competition inside a longer name no longer counts (Indian Premier League, CAF Champions League)**
- **📺 Sports: a broadcaster's unrelated channels are no longer "likely" (Apple TV+ Series)**
- **🏎️ Sports: a finished race is matched by its competition, not its first two drivers**
- **📥 Xtream import no longer fails with "Expected a string but was BOOLEAN" (TV #349)**
- **📐 Fit: films use the display shape on both engines, so anamorphic wide films are no longer stretched (TV #347)**

## core-1.0.70 — 2026-11-07

**Database v53 · Backup v26 · Backup: two new playlist fields, each profile's last categories, Settings lock PINs · Moved items keep their place**

### ✨ New features

- **🏆 Sports: 54 more leagues, with Volleyball and Lacrosse**
- **📅 Sports calendar: every followed league's month, saved on the device**
- **🏁 Driver, constructor and tennis rankings**
- **👕 Team pages: fixtures and results, cups included**
- **🗂️ League cards: leader and next match**
- **🔄 Calendar refresh setting**
- **✨ Clean channel names, per playlist** (community PR #46 by @Kevsosmooth)
- **🔢 M3U playlists without channel numbers are numbered in playlist order** (community PR #43 by @Kevsosmooth)
- **🎬 M3U entries that point at a video file go to Movies** (community PR #45 by @Kevsosmooth)
- **📋 Guide priority follows the EPG Sources order** (community PR #38 by @Kevsosmooth)
- **🔎 A guide whose channels match none of the playlist's says so** (community PR #58 by @arch-fan)
- **🎛️ Player buttons: hold OK, short OK, swap channel up/down, number keys** (community PR #48 by @Kevsosmooth)
- **🚀 Start on the last channel in the TV Guide or Live TV** (community PR #49 by @Kevsosmooth)
- **🏷️ Menu labels: off, when focused or always** (community PR #41 by @Kevsosmooth)
- **🎤 Voice search** (community PR #42 by @Kevsosmooth)
- **🔒 Lock a category with its own category lock PIN** (community PR #37 by @Kevsosmooth)
- **🔐 Settings lock: a PIN of its own per profile, optionally for long-press changes too** (community PR #47 by @Kevsosmooth)
- **🛡️ Protected profiles: new profiles, backups, restores and profile edits respect profile and Settings locks**
- **🔀 Restore: a profile with the same name asks Merge, Replace or Keep both**
- **👤 Local sync syncs the profile in use with the one in use on the other device**
- **🔁 Local auto-sync: paired devices keep each other in step by themselves, with what each one allows** (community PR #56 by @igebara)
- **👂 Keep auto-sync listening, as a switch (off by default)**
- **🪟 Picture-in-picture when leaving the app** (community PR #39 by @Kevsosmooth)
- **🔑 Stalker portals: an optional username and password**
- **⌨️ Key hints for the Sports Calendar and Leagues tabs**
- **📚 Home says the library is still loading while a large playlist fills in** (community PR #72 by @DevenP)
- **👁️ Long-press menus: hide actions, and the default order matches the menus**
- **📺 Hide All channels or Catch-up from the Live TV categories** (community PR #73 by @sarmadsoomro)
- **⬇️ Allow downloads per profile; a whole show or season asks first with the episode count**
- **📅 Catch-up channels get their past days in the guide from the provider's archive** (community PRs #64 by @Zangetsu-Sama and TV #324)
- **⏮️ "Start of the programme" first in "Go back to…"** (community PRs #65 by @Zangetsu-Sama and TV #326)
- **🎞️ Auto frame rate: separate switches for films and series, and Live TV (TV #318)**
- **🔓 Ignore connection limit, per playlist (TV #244)**
- **🔀 Sources: the other versions of the channel playing** (idea from community PR TV #317 by @Aaronnn17)
- **🔁 Auto start on boot and on wake-up: strings in every language** (community PR #40 by @Kevsosmooth)
- **📱 Edit a playlist from a browser on another device (TV #250)**
- **👤 Each profile remembers its own last category and channel**
- **🧭 TV Guide follows Live TV's category, as a switch (off by default)** (community PR TV #325 by @Zangetsu-Sama)
- **📺 Live TV restores the remembered category once its list has loaded, and only a category you pick is remembered** (community PR TV #325 by @Zangetsu-Sama)
- **👆 OK previews first: one switch for Live TV, one for the TV Guide (TV #344)**
- **📄 Program details: schedule rows carry their programme (TV #345)**
- **📄 Program details page: a channel's day with whole descriptions and details**

### 🐛 Fixes

- **Cups no longer fetch a table they never have**
- **🗑️ Delete confirmation strings for downloads and recordings**
- **↕️ A Move keeps only the moved items' positions; A–Z sorts everything else (TV #312)**
- **🔁 Older saved list orders are dropped once, Favorites kept**
- **TV guide sync up to 14× faster: adding a guide takes minutes, not a quarter of an hour**
- **M3U entries the playlist now tags as films or series leave Live TV** (community PR #36 by @Kevsosmooth)
- **M3U download retried once after a dropped connection** (community PR #44 by @Kevsosmooth)
- **Playlist re-sync keeps titles in the provider's order; an unchanged re-sync is about 4× faster**
- **Smaller database: seven redundant catalogue indexes dropped**
- **Faster playlist import: title tags read only when present**
- **Guide re-sync looks up what it already has in one read**
- **Catch-up works with `${(b)…}` / `${(e)…}` templates** (community PR #53 by @sarmadsoomro)
- **Xtream passwords with special characters play; short Xtream links and Stalker MACs masked in logs** (community PR #54 by @sarmadsoomro)
- **Settings search: a hint while nothing is typed**
- **Stalker re-sync no longer rewrites every live channel**
- **An Xtream playlist with a refused password says so instead of syncing empty**
- **A refused login answered with HTTP 513 shows the wrong-password message**
- **A changed password, server, username or MAC re-syncs the playlist on save**
- **Restore leaves Xtream playlists without a password unsynced and names them**
- **A new playlist with a name already in use is numbered: "My IPTV 2"**
- **Renamed channels, films and series keep their name on Home and in Continue**
- **Stalker portals behind a redirect to another host stay signed in**
- **Stalker posters sent as server paths load**
- **Stalker sync slows down on portals that refuse parallel requests, remembers it per playlist and loses no page; fast portals keep full speed**
- **Stalker series listed among the films go to Series when the portal has no series section**
- **Playlist rows show the full catalogue once the background download finishes**
- **📞 Phone: a notification sound only turns the sound down; calls, WhatsApp included, pause and resume**
- **🔁 Live TV: a decoder that fails to start is rebuilt up to three times within "Give up after" (TV #321)**
- **🍎 FairPlay entries: inline keys play as ClearKey; real FairPlay says it cannot play on Android**
- **🎨 Dolby Vision profile 5 on mpv opens the device's Dolby Vision decoder (libmpv 2026.11.0, TV #313)**
- **🏀 League cards in basketball, NFL and baseball lead with the best record**
- **Background catalogue download keeps going after a pause; an unfinished category loads when opened** (community PR #68 by @DevenP)
- **Background catalogue download resumes a minute after playback stops (#70)**
- **Films tagged " - CAM" find their poster** (community PR #69 by @DevenP)
- **Stalker series filed under films get their episodes** (community PR #67 by @DevenP)
- **The loading note counts only the playlist Home shows**
- **A phone with no app to save a backup file is told so**
- **Re-test measures the connection limit on Xtream, and the next sync keeps it (TV #244)**
- **Series whose episodes carry two dates load their episodes again (TV #342)** (community PR #78 by @maikelmj94-cpu)
- **A guide download cut off midway no longer deletes the rest of the guide**
- **An offline channel no longer stops a redirecting panel from opening its other channels as HLS** (community PR #76 by @SirKitboard)
- **mpv opens a channel whose .ts address is really an HLS playlist in seconds, not after the 10 s timeout** (community PR #77 by @SirKitboard)
- **Live comes back at the live edge when the TV is switched off and on, and after a pause of over a minute (TV #343)**

## core-1.0.69 — 2026-10-21

### ✨ New features

- **🎚️ FFmpeg audio for ExoPlayer, as a switch (off by default)**
- **💡 A hint to try MPV when a live channel keeps dropping frames**
- **🌗 Prefer SDR when a stream offers both (replaces the HDR switch)**
- **🎧 Audio delay for each output: HDMI, soundbar, Bluetooth, speakers, headphones**
- **🎬 mpv engine 2026.10.4**

### 🐛 Fixes

- **A saved channel plays again after the TV wakes from standby**
- **A live channel ExoPlayer can only show as a slideshow moves to mpv by itself**
- **Full frame rate on mpv after zapping from a 25 fps channel**
- **H.264 catch-up stays on hardware decoding on mpv**
- **Catch-up no longer stays on software decoding for two weeks after one stalled open**
- **Settings hints point to the right menu, in every language**
- **Live H.264 channels no longer freeze or drop frames on some TVs**
- **FFmpeg decodes audio only when the TV has no decoder for it**
- **A frozen live picture gets a new decoder and surface before mpv takes over**
- **Live video never starts on a stand-in surface**
- **Auto frame rate tells 59.94, 29.97 and 23.976 from 60, 30 and 24**
- **Auto frame rate works for films that do not state their frame rate**
- **Hardware decoding changed in Settings reaches Live TV without a restart**
- **No sound or picture left playing under a live error screen**
- **"Software decoding above 1080p" is obeyed on Live TV**
- **mpv resumes a film in one step, to the millisecond**
- **mpv waits for 2 seconds of live video before resuming after a stall**
- **TV speakers get stereo from mpv on Android 12 and older**
- **ExoPlayer plays its audio as a movie, like the rest of the app**
- **Lighter playback logging, with mpv's frame counters in the report**
- **Switching channels on mpv no longer holds up the screen**
- **Each playlist keeps its own category order**
- **A re-sync with many custom-category items finishes, instead of locking the database**

## core-1.0.68 — 2026-10-17

**Database v49 · Backup v25 · Breaking (glass settings)**

### ✨ New features

- **🏆 Sports Hub: live scores, fixtures and match details from ESPN, with the channels in your guide that show each match**
- **🔎 Find channels for a match, from 30 minutes before kick-off to 30 minutes after the end**
- **📺 Broadcasts: who shows a match, from your guide and from ESPN**
- **🔁 Sports data rebuilt on a schedule you choose, or by hand**
- **⭐ My teams, followed leagues, and your own order of sports and leagues**
- **🔔 Goal alerts and match reminders**
- **🏠 Sports row on Home**
- **🕒 Catch-up time zone "Automatic": the Xtream panel's own zone**
- **▶️ External player starts a film or episode where you left it**
- **🎞️ Film buffer follows the seconds you choose (#31)**
- **⚡ Faster zapping on one-session panels (#32)**

### 🐛 Fixes

- **🌍 Channel names written "UK: …" or "IT: …" are read as their country, like "UK| …"**
- **🧹 Sports leaves out playlist divider rows and other matches' event channels**
- **📺 Xtream series no longer lose every episode over one oddly written field**
- **💬 A series whose episode list the provider refused says why instead of "No episodes"**
- **📡 Pause and rewind live TV: a channel comes back by itself after a network outage**
- **▶️ External player picker offers Always again, so one player can be the default**
- **📺 Live TV falls back from mpv to ExoPlayer and gives up on time again**
- **🔊 Opening a previewed channel keeps its volume; unmute returns to the level before**
- **🔈 Changing sound settings during a film on ExoPlayer no longer starts a second copy**
- **🗣️ Audio language and the episode queue survive a reload and the screensaver**
- **🚫 A dead film link shows its error in seconds**
- **📱 Films on mpv keep playing with the phone's screen off**
- **⏪ M3U catch-up: every Kodi/TiviMate placeholder, catch-up-correction and timeshift="N"**
- **⏱️ Xtream rewind counter matches the clock in the picture**
- **🔁 Stalker catch-up Retry gets a fresh link; catch-up remembers player and zoom per channel**
- **🧩 Pause-and-rewind no longer skips segments with signed links**
- **🧠 Memory-pressure trim works on Android 14 and newer**
- **🏷️ Categories keep their country ("UK|") when a list mixes countries**
- **🔁 Retry works after a live channel gives up**
- **👤 "Account already in use" says so, instead of loading forever on a film**
- **🔢 First Xtream import counts a film listed in two categories once**
- **🪟 Glass keeps only on/off and opacity; the old fine-tuning settings are gone**

## core-1.0.67 — 2026-10-12

**Database v48**

### ✨ New features

- **🔐 ClearKey channels whose playlist carries the key itself play**
- **🔎 Search filter buttons: Live TV, TV Shows, Movies, Series**
- **🎞️ Software decoding above 1080p (Video player switch)**
- **⚡ Faster downloads over several connections**
- **💬 A failed download says why**
- **📺 Watch or keep downloading on a one-stream playlist**
- **🗂️ Downloads and recordings whose file is gone say "File missing"**
- **🌍 Basque**

### 🐛 Fixes

- **🔑 A backup that can't be opened no longer asks again for a password that was right**
- **📲 A backup cut off on its way from the phone is refused instead of failing as a wrong password**
- **💾 Opening a large backup needs less memory**
- **🔗 A provider's get.php M3U link is added as Xtream, so movies and series land in their own sections (#238)**
- **🧽 M3U links copied from a web page with "&amp;" work**
- **📺 Shows with a Season 0 load all their episodes on Xtream**
- **📂 Xtream seasons sent in other layouts no longer lose their episodes**
- **🔢 Xtream episodes with no number are numbered in order instead of all showing E0**
- **🔁 A show's episode list retries once when the provider drops the connection**
- **▶️ Play on a new show starts at Season 1 Episode 1; Specials come after the last season**
- **🔤 M3U episodes named "Season 1 Episode 5", "S2019E05" or with four-digit episode numbers are recognised**
- **🧾 One translation-readiness issue per language instead of a new one each day**
- **💾 Big downloads to a FAT32 USB stick fail with a clear reason**

## core-1.0.66 — 2026-10-10

### ✨ New features

- **🔊 ExoPlayer plays DTS, TrueHD, MP2 and other sound the TV can't decode (FFmpeg from the mpv engine)**

### 🐛 Fixes

- **🎧 "Stereo only" now sends two channels on ExoPlayer too**
- **📺 Channels whose sound exceeds what the decoder advertises stay on ExoPlayer instead of moving to mpv**
- **🔇 A film whose sound ExoPlayer can't play goes to mpv instead of playing silent**
- **🔈 Sound switches between passthrough and decoding when a soundbar wakes up mid-play**
- **📊 Stream info shows a film's real bitrate on ExoPlayer instead of 0 Mbps**
- **⏱️ Live latency Low / Stable / Custom now takes effect on mpv**
- **🔊 mpv sends only the surround layouts the TV or receiver really plays**
- **⏳ "Pre-buffer" no longer makes mpv restart a channel that is still filling up**
- **📡 mpv gives up on a dead live connection in 10 s instead of 60, and retries a busy panel (458) without a black screen**
- **🌙 Night mode's limiter on mpv stops just below full scale**
- **📺 Live DASH channels keep their picture through a short outage**
- **🎬 ExoPlayer finds DTS in Blu-ray (.m2ts) files and plays TS channels without access-unit markers**
- **📱 Background playback keeps Wi-Fi awake while ExoPlayer plays**
- **⏯️ The remote's Play/Pause key works when the TV sends only the key release**
- **🔎 Search finds "Spider-Man" from "Spider-M" and lists every match, not the first 40**

## core-1.0.65 — 2026-10-09

### ✨ New features

- **🔄 "Stream interrupted. Reconnecting…" while a live channel recovers by itself** (community PR #13 by @tvdev-android)
- **↩️ Strings for restoring a category, or the playlists shown, to the playlist default** (community PR #11 by @tvdev-android)
- **⏭️ Strings for the phone player's next / previous channel in list buttons**
- **🔄 Auto refresh of playlists and guides shared by both apps**
- **⚠️ String for the first-run restore warning when Sources is unticked**
- **📊 Latest episode progress per series, for series poster progress bars**
- **🎞️ Guide video preview setting, kept in backups**
- **📋 Strings and the Catch-up list for the player's Stage channel, history and category lists**
- **⭐ Favourite movies and series rows for Home**
- **🔎 Programme search by title, the On TV group in Search**
- **⏰ Schedule a recording by day and time on any channel, guide or not (#2)**
- **🎞️ mpv engine 2026.10.2**

### 🐛 Fixes

- **⏺️ Recordings of channels served as a quality list (master playlist) or AES-128 HLS now record the programme (#243)**
- **📡 Live channels keep the last picture through a network outage and come back by themselves** (community PRs #12, #13, #17 by @tvdev-android)
- **⏱️ A live reconnect no longer stalls behind a slow connection or DNS lookup** (community PR #14 by @tvdev-android)
- **🎞️ A live channel ExoPlayer can't decode after it started playing moves to the other player** (community PR #20 by @tvdev-android)
- **🔌 A live channel whose server stops answering mid-stream reconnects in seconds** (community PR #20 by @tvdev-android)
- **📺 4K channels saved by Pause and rewind play on ExoPlayer again instead of losing the picture**
- **⚡ Opening a channel from its preview no longer shows a black screen first** (community PR #19 by @tvdev-android)
- **⏩ Channels with a pre-buffer start faster** (community PR #19 by @tvdev-android)
- **🔊 Surround sound is no longer turned off by a muted preview** (community PR #21 by @tvdev-android)
- **🔐 Stalker portals that reject a device as a "Device conflict" are retried with the box model** (community PR #22 by @atlasafford)
- **🎬 An automatic playlist or guide refresh waits until playback stops**
- **💾 A first-run backup restore opens the app only once everything is restored**
- **🔄 Restored playlists download straight away after a restore**
- **⏪ H.264 catch-up and films are no longer mistaken for video the hardware decoder can't play**
- **🔁 Retrying a catch-up, or coming back to the app during one, keeps playing it as catch-up**
- **🖼️ A catch-up that loses its picture recovers in seconds instead of staying black**
- **🧊 Catch-up no longer freezes on its first frame on TVs with a Realtek chip**
- **🎞️ Catch-up uses the hardware decoder again instead of falling back to software (#229)**
- **📡 A catch-up of a programme still on air switches to live when the provider stops sending**
- **⏱️ A short rewind on a catch-up channel no longer asks for a recording that doesn't exist yet**
- **🏷️ Film and series headlines without a leading provider tag such as "|MULTI|"**

## core-1.0.64 — 2026-10-04

### 🐛 Fixes

- **🧭 The TV rail no longer widens on focus unless you turn it on**

## core-1.0.63 — 2026-10-04

### 🐛 Fixes

- **▶️ Play in external player finds VLC and MX Player on Android 11 and newer**

## core-1.0.62 — 2026-10-03

**Database v47 · Backup v24 · Breaking**

### ✨ New features

- **🎨 Everything the new TV interface needs: settings, texts in every language, backgrounds, the Pixel icon and the owntv wordmark**
- **⏪ Setting for resuming a saved channel: ask, always or never**
- **🗂️ Remove a channel from a custom category**
- **🎞️ mpv engine 2026.10.0**

### 🐛 Fixes

- **⚡ Faster channel switching on providers that mix HLS and TS channels**
- **📼 Catch-up that shows no picture tries the second player instead of a wrong fast-start error (#229)**
- **🗂️ Hiding a provider category no longer empties custom categories, Favorites or History**

## core-1.0.61 — 2026-09-26

### 🐛 Fixes

- **📐 Category panel can go down to 10% when there are only two columns**
- **🌐 French low-zoom warning synced with Weblate**

## core-1.0.60 — 2026-09-26

### 🐛 Fixes

- **🔒 Playlists on servers with the newest Let's Encrypt certificates import again (#208)**

## core-1.0.59 — 2026-09-26

**Breaking**

### ✨ New features

- **🖼️ Match EPG brings the guide's logo, with an "Include guide logos" choice**
- **⚙️ Video player settings in smaller categories**

### 🐛 Fixes

- **💾 A removed USB stick no longer stops downloads**
- **📺 Channel logos on the Android TV home row are no longer cropped** (community PR #6 by @quangtruongnb)
- **📃 Playlists saved with a byte-order mark keep their EPG address** (community PR #7 by @Sekator778)
- **🔤 The EPG logo setting works on the phone**

## core-1.0.58 — 2026-09-26

**Database v46 · Backup v24 · Breaking**

### ✨ New features

- **📺 One live tuner shared by both apps**
- **⏸️ Pause and rewind live TV on channels without catch-up**
- **⏮️ Previous channel**
- **🎬 OwnTV's own mpv engine**
- **🔊 One volume scale on every player, night mode, volume leveling and a passthrough switch**
- **🖼️ Maximum video quality and tunneled playback**
- **🌐 Preferred audio and subtitle language per profile, 50 languages and "Original language"**
- **📶 Film buffer, network timeout and reconnect attempts**
- **😴 Sleep timer**
- **🧠 The player remembers engine choices, sound-only channels and audio delays per stream**
- **🎨 Eight app icons and logos, one switcher**

### 🐛 Fixes

- **🔄 "Restart now" reopens the app after an icon change**

## core-1.0.57 — 2026-09-21

### 🐛 Fixes

- **🗓️ An empty guide source says so instead of spinning for minutes**

## core-1.0.56 — 2026-09-21

### 🐛 Fixes

- **💥 The app would not open after upgrading from database v40**

## core-1.0.55 — 2026-09-21

### ✨ New features

- **⏺️ DASH channels can be recorded**
- **🎞️ Films and episodes show a Format row**

### 🐛 Fixes

- **🔁 An unprotected DASH channel no longer loops into a reconnect storm**
- **⏺️ An interrupted recording is finished on the next run**

## core-1.0.54 — 2026-09-21

### ✨ New features

- **▶️ DASH channels play**
- **🔗 A last-resort address for an Xtream channel that will not open**

### 🐛 Fixes

- **ℹ️ Stream info no longer reports DASH channels as MPEG-TS**
- **🔒 Recording a DRM channel is refused instead of attempted**

## core-1.0.53 — 2026-09-20

### ✨ New features

- **🎬 A layout choice for Movies & Series**
- **📐 The Cinematic detail block's height is its own setting**

## core-1.0.52 — 2026-09-20

### 🐛 Fixes

- **🗓️ A guide sync can finish with the screen off, and no longer restarts from zero**
- **🔄 Local sync hands out a key that opens its own backup**
- **💾 The first-run restore reports what it restored**

## core-1.0.51 — 2026-09-20

### 🐛 Fixes

- **💥 EPG sync, unfavourite, clear history and backup restore work again**
- **💾 Restoring a backup is no longer treated as a sync merge**

## core-1.0.50 — 2026-09-19

### ✨ New features

- **👁️ A watch session says when it opens and closes**

## core-1.0.49 — 2026-09-19

### ✨ New features

- **📦 SQLite now ships inside the app**

### 🐛 Fixes

- **⚡ A re-sync no longer rewrites rows that only moved**
- **⏩ The catalogue sync yields to playback**
- **📶 The stream-limit measurement no longer slows setup**
- **⚠️ A truncated bulk fetch no longer looks like a clean success**

## core-1.0.47 — 2026-09-18

### 🐛 Fixes

- **🎞️ The measured frame rate no longer lands on the wrong standard rate**

## core-1.0.46 — 2026-09-18

**Database v41 · Breaking**

### ✨ New features

- **🗓️ Guide days to keep is the user's choice, not 48 hours**
- **🔄 EPG auto-refresh every N days**

### 🐛 Fixes

- **⚡ The guide no longer reads the whole database to draw one screen**
- **🔎 "Match EPG" lists what the guide really has**
- **🔗 Manual matches survive a deleted and re-added playlist**
- **✅ Auto-match no longer claims success it cannot deliver**
- **🧹 Duplicate programmes are collapsed when stored**

## core-1.0.45 — 2026-09-16

### ✨ New features

- **⚡ Xtream sync fetches all three sections at once**

### 🐛 Fixes

- **📂 A category the provider lists no longer arrives empty**

## core-1.0.44 — 2026-09-15

### ✨ New features

- **📱 A new device can be set up from the one you already have**

## core-1.0.43 — 2026-09-14

### 🐛 Fixes

- **⏪ Stalker catch-up plays**

## core-1.0.42 — 2026-09-14

### ✨ New features

- **📄 The documentation, rewritten to the point**

## core-1.0.41 — 2026-09-14

### ✨ New features

- **⬆️ The playback engine, the database and the build toolchain move up**

## core-1.0.40 — 2026-09-14

### 🐛 Fixes

- **⬇️ The updater asks the right repository**

## core-1.0.39 — 2026-09-13

### ✨ New features

- **🔠 A first-run step that sets how big everything is**

## core-1.0.38 — 2026-09-13

### 🐛 Fixes

- **🔢 One category order instead of two that had to agree**

## core-1.0.37 — 2026-09-13

**Database v40**

### ✨ New features

- **📁 Downloads and recordings can be saved to a folder you pick**
- **📶 How many streams a provider allows, measured rather than assumed**
- **⏺️ Live TV recording**
- **🧪 A playlist's Test button became Info**

### 🐛 Fixes

- **🗓️ The guide, the channel row and the preview pane agree**
- **🐶 One live-engine watchdog, shared by both apps**
- **⬇️ An episode download appears in the Series list**
- **🚫 HTTP 407 is treated as a session limit**

## core-1.0.36 — 2026-09-12

**Database v39**

### ✨ New features

- **📂 A download row says where the file went**
- **⏺️ The Live TV recording engine**

## core-1.0.35 — 2026-09-12

### 🐛 Fixes

- **🔢 The Multiview refusals are real plurals**

## core-1.0.34 — 2026-09-12

### ✨ New features

- **🔲 Multiview's rules, and one owner for the three folder names**

## core-1.0.33 — 2026-09-12

### ✨ New features

- **⬇️ One answer to "is this downloading?", and a download line for the status pill**

## core-1.0.32 — 2026-09-11

### 🐛 Fixes

- **🗓️ Two guides in one playlist header are two guides again** (TV #171)

## core-1.0.31 — 2026-09-11

### ✨ New features

- **📺 The Stalker portal's own guide, so Stalker has EPG and catch-up**
- **📅 When an episode first aired**
- **👤 A picture of your own for a profile**

### 🐛 Fixes

- **🔑 A portal that said "slow down" is no longer read as "logged out"**
- **⏪ Catch-up works on a Stalker portal**

## core-1.0.30 — 2026-09-11

### 🐛 Fixes

- **📃 M3U titles keep their commas, and keep their favourites**
- **🔤 Alphabetical sort reaches the items inside a folder**

## core-1.0.29 — 2026-09-11

### 🐛 Fixes

- **🔄 Local sync keeps the newer of two facts**
- **🔑 Local sync no longer asks for a password it should not**
- **📱 Local sync no longer lists the same device twice**

## core-1.0.28 — 2026-09-11

### ✨ New features

- **💾 The last backup is recorded**
- **⋯ Text for the new More screen**

## core-1.0.27 — 2026-09-07

### ✨ New features

- **⋯ A More section in the main menu**

### 🐛 Fixes

- **🗑️ Nine unused strings removed**

## core-1.0.26 — 2026-09-06

**Database v36**

### ✨ New features

- **🔄 Local sync between devices**
- **🪦 Deletions that survive a merge**
- **👁️ A dry run before anything is applied**

## core-1.0.25 — 2026-09-06

### ✨ New features

- **📡 A player failure for a receiver that cannot play the stream**
- **🔈 The player can say the sound is not coming out of this device**

## core-1.0.24 — 2026-09-06

### ✨ New features

- **✨ The glass arrival shine is a setting**

## core-1.0.23 — 2026-09-06

### ✨ New features

- **📄 The README says how the apps get core**

## core-1.0.22 — 2026-09-06

### ✨ New features

- **👤 One implementation of what a profile is**
- **🔒 The profile gate at launch, decided once**

## core-1.0.21 — 2026-09-06

### ✨ New features

- **🎛️ Now Trending in two shapes**

## core-1.0.20 — 2026-09-06

### ✨ New features

- **🔎 Why the Now Trending row is, or is not, on Home**

## core-1.0.19 — 2026-09-05

### ✨ New features

- **🌍 Three new strings in every packaged language**

## core-1.0.18 — 2026-09-05

### ✨ New features

- **🎨 The shell's region colours moved to core**

### 🐛 Fixes

- **🗓️ The EPG separator lost its spaces**

## core-1.0.17 — 2026-09-05

### ✨ New features

- **🪟 Two more surfaces the glass can be scoped to**

## core-1.0.16 — 2026-09-04

### ✨ New features

- **📱 Settings for a player you can carry around**
- **🎵 Which channels were watched without a picture**

## core-1.0.15 — 2026-09-04

### ✨ New features

- **🪟 One more glass preset**

## core-1.0.14 — 2026-09-04

### ✨ New features

- **✂️ Span selection and bulk rename, shared by both apps**
- **📺 A Stalker portal's expiry date, read in one place**

## core-1.0.13 — 2026-09-03

### ✨ New features

- **🔎 One search for both apps**
- **📱 The settings a phone has and a television does not**
- **📶 Downloads can wait for Wi-Fi**

## core-1.0.12 — 2026-09-02

### ✨ New features

- **🧱 The parts a second app needs, taken out of the TV app**
- **⚙️ Settings for a touch screen**

## core-1.0.11 — 2026-09-02

### ✨ New features

- **🖼️ Cached TMDB posters fill the tiles a provider left blank**

## core-1.0.10 — 2026-09-02

### ✨ New features

- **📱 The playback session can behave like a phone**

### 🐛 Fixes

- **🔊 Audio focus is no longer lost on every pause**

## core-1.0.9 — 2026-09-02

### 🐛 Fixes

- **⚡ EPG auto-match finishes on TV hardware**
- **🌍 EPG auto-match works outside the Latin alphabet**

## core-1.0.8 — 2026-09-02

### ✨ New features

- **🗂️ The content menus and Live TV queries moved to core**
- **▶️ The player pieces both apps need**

## core-1.0.7 — 2026-09-01

### ✨ New features

- **🎨 The colour values moved to core**
- **🧭 The main-menu sections moved to core**

## core-1.0.6 — 2026-09-01

### ✨ New features

- **📱 A non-TV app can use core**
- **🤖 Release automation**

## core-1.0.5 — 2026-08-31

### ✨ New features

- **🧪 A playlist can be tested**
- **🔄 Playlist auto-refresh takes a custom number of days**

## core-1.0.4 — 2026-08-30

### ✨ New features

- **📄 The README describes the release pipeline**

## core-1.0.3 — 2026-08-30

### ✨ New features

- **🏷️ Every core version gets a GitHub Release, which tells the apps to update**

## core-1.0.2 — 2026-08-30

### ✨ New features

- **🌍 Hungarian is a fully translated language**

## core-1.0.1 — 2026-08-29

### 🐛 Fixes

- **©️ The About screen's copyright line reads "© 2026 OwnTV"**

## core-1.0.0 — 2026-08-29

### ✨ New features

- **📦 First release as a standalone library**
