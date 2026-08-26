# Notification Log

**Never lose a dismissed notification again.**

Notification Log is a 100% offline Android app that quietly saves every
notification your phone receives — title, body, source app, time — into a
private, searchable history. When you swipe away a message or a one-time code
and need it five minutes later, just search for any word it contained.

> *"What was that code / link / address I just dismissed?"* — solved.

---

## Features

**Free — everything you need, forever:**

- **Automatic background capture** — grant "Notification access" once; every
  notification is recorded from then on, even while the app is closed.
- **Instant search** — find any entry by a word in its title or text,
  newest first, with relative times ("5m ago").
- **Noise filtering** — persistent status notifications (downloads, media
  players, VPN icons) and duplicate group summaries are skipped on purpose,
  so your history holds real messages, not machine chatter.
- **Data ownership** — swipe left to delete an entry; clear everything from
  the ⋮ menu (with confirmation).
- **Reliable capture** — an optional, clearly-explained battery-optimization
  exemption keeps recording alive through Doze and battery saver.
- **No ads. No account. No tracking. Ever.**

**Pro (one-time purchase):**

- **CSV export** — save your entire history as a spreadsheet-friendly CSV
  file through Android's standard file picker.

---

## Privacy

This app cannot phone home because it has **no internet permission at all**:

- All data stays in a local Room database in the app's private storage.
- Cloud backup of that data is **disabled** — nothing ever leaves your device.
- No analytics, no ads SDKs, no accounts, no network libraries.
- Deleting entries in-app (or uninstalling) removes data permanently — there
  are no copies anywhere else.

See our full privacy policy: https://github.com/sunnydev07/notification-log#privacy
*(also published standalone for the Play Store listing)*

---

## Tech Stack

| Layer | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM (ViewModel + StateFlow, unidirectional data flow) |
| Storage | Room (SQLite) |
| Capture | `NotificationListenerService` |
| Background | Coroutines + application-scoped writes |
| Billing | Google Play Billing (one-time non-consumable) |

Minimum supported Android: **8.0 (API 26)** · Target: **API 36**

---

## Building & Running

### Prerequisites

- **Android Studio** (latest stable)
- JDK 17 (bundled with recent Android Studio versions)
- An emulator or device running Android 8.0+

### Option A — Android Studio (easiest)

1. Open Android Studio → **File > Open…** → select this `NotificationLog` folder.
2. Let Gradle sync finish (first sync downloads dependencies).
3. Press **Run ▶** with an emulator or connected device selected.

### Option B — Command line

From this folder (`NotificationLog/`):

```bash
# Windows (PowerShell or Git Bash)
gradlew.bat installDebug          # builds + installs on connected device/emulator

# macOS / Linux
./gradlew installDebug
```

Then launch **Notification Log** from the launcher.

> First builds may take several minutes while Gradle warms up.

### Run the tests

```bash
gradlew.bat testDebugUnitTest     # full JVM test suite (~52 tests)
gradlew.bat lint                  # static analysis
```

---

## Using the App

### 1. Grant notification access (first launch only)

The app opens with an explanation screen. Tap **Open Notification Access
settings**, flip the toggle for *Notification Log*, and confirm Android's
warning dialog. Return to the app — you'll land on the empty history screen.

*Why a system screen instead of a popup?* Android treats notification access
as a sensitive permission; the OS itself requires you to enable it manually.

### 2. That's it — capture starts immediately

Receive any notification and it appears in the list within a second,
even with the app closed. A small banner may offer a battery-optimization
exemption — recommended if you want capture to survive aggressive battery
savers.

### 3. Search your history

Type in the search bar — results filter by words in titles or bodies
(case-insensitive). Tap ✕ to clear.

### 4. Manage entries

- **Delete one**: swipe a card left.
- **Clear all**: ⋮ menu → *Clear all history* (asks first — permanent!).

### 5. Export as CSV (Pro)

⋮ menu → *Export CSV…* → pick where to save. Free users see the one-time
upgrade offer here instead.

---

## Known Limitations (by design)

- Persistent notifications (music players, downloads, VPNs) aren't logged —
  they're status indicators, not messages, and would drown out real content.
- If someone uses Android's **Force Stop** on the app, capture pauses until
  the app is opened once more. Normal swiping away does *not* pause it.
- History isn't backed up anywhere — by design, not accident.

---

## Project Structure

```
app/src/main/java/com/sunny/notificationlog/
├── service/      # NotificationListenerService + pure filter rules
├── data/         # Room @Entity, @Dao, @Database, Repository
├── viewmodel/    # One ViewModel per screen; StateFlow-driven UI states
├── ui/           # Compose screens (stateless where possible)
├── billing/      # Play Billing wrapper for the one-time Pro purchase
└── util/         # Relative timestamps, CSV builder
```

Development followed a 7-phase plan (capture → storage → search UI → edge cases
→ QA → Play prep → Pro upgrade), including a manual QA checklist and a Play
Store submission pack. Docs live outside this repo.

---

## Status

v1.1 — feature-complete for v1 scope: capture → search → manage → export.
Prepared for Google Play submission.
