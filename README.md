<div align="center">

# 📖 Kararead

**A calm, Instapaper-style reader for your [Karakeep](https://karakeep.app) library.**

*Read-it-later, done right — beautiful typography, resume-where-you-left-off,
and a focused queue, all on top of your own self-hosted Karakeep server.*

[![CI](https://github.com/L-K-M/Kararead/actions/workflows/ci.yml/badge.svg)](https://github.com/L-K-M/Kararead/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

</div>

---

## Why?

[Karakeep](https://karakeep.app) (formerly Hoarder) is a wonderful self-hosted
bookmark-everything app, and its official app is a great *manager*. But when you
just want to **read** the articles you saved for a calm moment, a dedicated
reader — like Instapaper or Pocket — is a nicer place to be.

Kararead is that reader. It talks to your existing Karakeep server and turns your
"read it later" pile into a focused, beautiful reading experience.

## Features

- **A focused queue.** Your unread inbox, a chosen "read-it-later" list,
  favourites, and your archive — switchable with a tap.
- **A gorgeous reader.** Article HTML rendered with a hand-tuned typographic
  stylesheet: comfortable measure, real blockquotes, code blocks, figures and
  images.
- **Reading themes.** Light, Sepia, Dark, and a true-black OLED mode.
- **Typography you control.** Ten typefaces — eight OFL fonts bundled for
  offline reading (Literata, Lora, Source Serif 4, Newsreader, Crimson Pro,
  Bitter, Inter, Atkinson Hyperlegible) plus System and Mono — text size, line
  spacing, margins, and justification, applied live with a preview in the
  settings sheet.
- **Resume where you left off.** Per-article scroll position is saved locally and
  restored automatically, with a progress indicator on every card.
- **Frictionless triage.** Swipe to archive (= mark read) or favourite; undo from
  a snackbar. Mark read/unread and favourite from the reader too.
- **Listen to articles.** Built-in text-to-speech reads any article aloud, with
  play/pause, sentence skip, and a choice of voice.
- **Highlights.** Select text in the reader to highlight it (synced to Karakeep);
  tap a highlight to remove it.
- **Reading stats & streaks.** A Stats tab with your reading streak, minutes read
  today and this week, and a 14-day chart — gentle, not gamified.
- **Jump back in.** A "recently opened" strip at the top of the library.
- **Volume-key paging.** Optionally turn the page with the hardware volume keys.
- **Offline-friendly.** Opened articles are cached so they reopen instantly and
  survive going offline; the top of your queue can be prefetched on Wi-Fi.
- **Search & tags.** Karakeep's qualifier syntax (`#tag`, `is:fav`, `url:…`), plus
  a browse-by-tag chip cloud when the search box is empty.
- **Calm by default.** Auto-hiding chrome while reading (tap to reveal), Material 3
  design, dynamic color on Android 12+ (or a manual accent color), light/dark app
  theme.

> **Read state mapping:** Karakeep has no separate read/unread flag, so Kararead
> uses Karakeep's `archived` state as "done reading". Reading *progress* is
> tracked locally on the device (the API has no field for it).

## Screens

| Library (queue) | Reader | Reading settings |
|---|---|---|
| Calm cards with site, excerpt, reading time and a progress ring; swipe to archive/favourite. | Distraction-free article with a thin progress line and auto-hiding top bar. | Themes, typeface, size, spacing, margins, justify — all live. |

*(Build & run to see them — see below.)*

## Getting started

You need a running **Karakeep** server and an **API key**:

1. In Karakeep, go to **Settings → API Keys** and create a key (looks like
   `ak1_…`).
2. Install Kararead (build it yourself, or grab an APK from
   [Releases](https://github.com/L-K-M/Kararead/releases)).
3. On first launch, enter your **server URL** (e.g.
   `https://bookmarks.example.com`) and the **API key**, then tap **Connect**.
4. Optional: open the **Lists** tab and tap the bookmark icon next to a list to
   set it as your "read it later" home.

## Building from source

Requirements: **JDK 17**, the **Android SDK** (API 35), and an Android device or
emulator (min SDK 26 / Android 8.0).

```bash
git clone https://github.com/L-K-M/Kararead.git
cd Kararead

# Point Gradle at your SDK (or rely on the ANDROID_HOME / ANDROID_SDK_ROOT env var)
cp local.properties.example local.properties   # then edit sdk.dir

# Build a debug APK
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Run the checks
./gradlew lintDebug testDebugUnitTest
```

Install on a connected device with `./gradlew installDebug`.

## Architecture

Single-module app, MVVM with unidirectional state and Jetpack Compose.

```
ch.lkmc.kararead
├── data/
│   ├── remote/   Retrofit KarakeepApi, DTOs (polymorphic content union),
│   │             runtime-configured client + auth, DTO→domain mappers
│   ├── local/    Room: reading progress, offline article cache, reading stats
│   ├── prefs/    DataStore settings (API key kept in a backup-excluded store)
│   ├── paging/   Cursor-based Paging 3 source
│   ├── repository/ KarakeepRepository — the single source of truth
│   └── model/    Domain models
├── reader/       HTML → themed reader document; authenticated asset loading
├── tts/          Text-to-speech narration (ArticleSpeaker)
├── work/         WorkManager: offline prefetch + cache cleanup
└── ui/           Compose screens (onboarding, library, reader, lists, search,
                  stats, settings), theme, navigation, shared components
```

**Stack:** Kotlin · Jetpack Compose / Material 3 · Hilt · Retrofit + OkHttp +
kotlinx.serialization · Room · DataStore · Paging 3 · Coil · Jsoup · WorkManager.

The reader renders article HTML in a `WebView` with an injected stylesheet
(better than `AnnotatedString` for real-world article markup), a JS bridge for
scroll progress, and a `WebViewClient` that injects the bearer token for
server-hosted images.

See [`PLAN.md`](PLAN.md) for the full design rationale and the Karakeep API
reference, and [`awesome.md`](awesome.md) for the post-build review and roadmap.

## CI/CD

- **CI** (`.github/workflows/ci.yml`): on every push/PR, runs lint, unit tests,
  and builds a debug APK, uploading the APK and reports as artifacts.
- **Release** (`.github/workflows/release.yml`): push a `v*` tag to build a
  release APK and publish a GitHub Release with auto-generated notes.
- **Dependabot** keeps Gradle and Actions dependencies fresh.

A debug keystore is checked in so CI builds are reproducibly signed. For a real
release signing key, wire your keystore into the release workflow via repository
secrets.

## License

[MIT](LICENSE) © Kararead contributors.

Kararead is an independent, unofficial client and is not affiliated with the
Karakeep project.
