# awesome.md — a fresh review of Kararead

A from-scratch read of the whole codebase (2026-06), looking for bugs, rough
edges, missing features, and a few delightful ideas. The earlier backlog had all
shipped and was cleared; this is a new pass.

Overall: this is a genuinely lovely, well-structured app. Clean MVVM, a
thoughtful WebView reader, careful offline/caching, good tests, and a calm,
coherent design. The findings below are mostly polish and edge cases rather than
anything broken — which is a compliment.

**Legend:** 🐞 bug · 🔧 issue/cleanup · ✨ feature · 💡 idea/delight
**Status:** ⬜ open · 🟢 shipped (PR) · ⏸️ deferred (documented, not done)

---

## A. Bugs & correctness

- **A1 🐞 — Plain-text bookmarks aren't HTML-escaped.** `BookmarkDto.toReaderArticle`
  builds a body for `text`-type content as `"<p>${it.replace("\n\n", "</p><p>")}</p>"`.
  The raw note text is **not escaped**, so any `<`, `&`, or tag-like sequence
  (`I love <html> tags`, `a < b`) is parsed as markup and then *stripped* by the
  Jsoup sanitizer — silently dropping the user's words. Single newlines are also
  lost. Fix: escape first, then convert blank lines to paragraphs and single
  newlines to `<br>`. **Status: 🟢 PR #27**

- **A2 🐞 — In-article anchor / footnote links open a browser.** The reader doc is
  loaded with the server origin as the base URL, so a same-page link like
  `<a href="#cite_note-1">` resolves to `https://server/#cite_note-1`. Clicking it
  hits `shouldOverrideUrlLoading`, which sends *every* http(s) URL to an external
  browser — so footnotes, "back to top" links, and Wikipedia-style references kick
  the reader out to a blank server page instead of jumping within the article.
  Fix: intercept clicks on same-document `#` anchors in JS and `scrollIntoView`.
  **Status: 🟢 PR #28**

- **A3 🐞 — `StatsScreen` streak label has a dead conditional.**
  `if (days == 1) "day streak" else "day streak"` — both branches are identical,
  so "1 day streak" never becomes singular. Fix pluralization, and show an
  encouraging line instead of a deflating "0" when there's no active streak.
  **Status: 🟢 PR #31**

- **A4 🔧 — TTS init failure is invisible.** `ArticleSpeaker` sets
  `SpeechState.failed = true` when the engine can't initialize, but nothing in the
  UI ever reads `failed`. Tapping "Listen" on a device with no TTS engine does
  nothing, with no feedback. Fix: surface a toast. **Status: 🟢 (folded into PR #29)**

- **A5 🐞 — Auto-advance ("Done · Next" / "Next in queue") always pulls from the
  Inbox**, ignoring both the source the article was opened from *and* the chosen
  read-later list (which is often the home queue) *and* the queue sort order. So
  finishing an article opened from your read-later list jumps to a different pile.
  A proper fix threads the opening `BookmarkSource` through the reader route.
  **Status: ⏸️ deferred** (touches navigation + every screen that opens the
  reader → high merge-conflict surface; documented for a focused follow-up).

- **A6 🔧 — Cross-client highlight fidelity.** Highlight offsets are character
  indices into Kararead's *sanitized* article text, computed the same way on
  capture and render so our own highlights round-trip exactly. Highlights created
  in the official Karakeep web/app (offsets against the original DOM) may land at
  the wrong place here, and vice-versa. Hard to fully fix without matching
  Karakeep's exact offset basis; documented as a known limitation. **Status: ⏸️**

- **A7 🔧 — `usesCleartextTraffic="true"` is app-wide.** Needed for http
  self-hosted / intranet servers (and the http fallback), but global. A
  network-security-config scoped to the configured host(s) would be tighter.
  Pragmatic as-is since the server is arbitrary and user-supplied. **Status: ⏸️**

## B. Features & gaps

- **B1 ✨ — Text-to-speech polish.** The "Listen" feature is great but didn't
  request **audio focus** (it talked over music/podcasts and wouldn't duck or
  pause for calls), didn't pause when **headphones were unplugged**, and had **no
  speed control** (fixed 1.0×). Fix: AudioManager focus (pause on transient loss,
  stop on permanent loss, resume on regain), a becoming-noisy receiver, a
  persisted speech-rate cycled from the Listen bar, plus the failure toast (A4).
  **Status: 🟢 PR #29**

- **B2 ✨/♿ — Reader can't pinch-zoom, and http images may be blocked.** Zoom was
  disabled (`builtInZoomControls = false`), so a small diagram or a wide table
  couldn't be enlarged — at odds with the care taken over legible fonts. And on an
  https reader doc, http-hosted images (common on self-hosted/intranet servers,
  esp. via the http fallback) can be blocked as mixed content. Fix: enable
  pinch-zoom (no on-screen buttons) and set `mixedContentMode = COMPATIBILITY`.
  **Status: 🟢 PR #30**

- **B3 ✨ — Tags are fetched but never shown on cards.** `Bookmark.tags` is
  populated from the list response yet isn't surfaced anywhere in the library.
  A couple of muted tag chips on a card would aid triage without shouting.
  **Status: ⏸️** (nice, but watch the calm aesthetic; left as an idea).

- **B4 ✨ — No notes/tag editing from the reader.** Intentional non-goal (the
  official app manages); noted for completeness. **Status: ⏸️**

- **B5 ✨ — TTS has no lock-screen / media-notification controls** and can be
  killed in the background (no foreground service + MediaSession). A real
  "listen while the screen is off" experience would want both. Larger effort.
  **Status: ⏸️**

## C. Engineering / project health

- **C1 🔧 — README links to `awesome.md`** which didn't exist on `main` (the link
  was dead). This document fixes that. **Status: 🟢 (this file)**

- **C2 🔧 — Redundant API-client configuration.** Both `KararreadApp.onCreate`
  and `RootViewModel.init` configure `ApiProvider` from settings. Harmless
  (`configure` is synchronized and no-ops when unchanged), but duplicated.
  **Status: ⏸️**

- **C3 🔧 — `backup_rules.xml` comment says the API key is in "encrypted
  DataStore."** It's a plain Preferences DataStore (correctly excluded from
  backups, but not encrypted). Comment is slightly misleading. **Status: ⏸️**

- **C4 🔧 — ViewModel tests are thin.** `turbine` is a dependency but there are no
  flow/ViewModel tests; logic is covered at the util/repository level. Adding a
  few would harden state handling. **Status: ⏸️**

## D. Delight

- **D1 💡 — "Shuffle my typeface" 🎲.** A tiny dice in the reading-settings sheet
  that picks a random reading face — a fun way to discover the bundled fonts.
  **Status: 🟢 PR #32**

- **D2 💡 — "Finish by ~9:42pm" clock estimate** next to "12 min left" in the
  reader bar — a quietly motivating touch. **Status: ⏸️** (touches the reader top
  bar, which PR #29 also edits — deferred to avoid a conflict).

- **D3 💡 — Heading table-of-contents** for long articles (jump to a section).
  **Status: ⏸️**

- **D4 💡 — A daily reading-minutes goal** woven into the Stats streak. **Status: ⏸️**

- **D5 💡 — An "Auto" reader theme** that follows the system light/dark setting
  (and optionally the time of day). **Status: ⏸️** (touches the theme/prefs core).

---

## Shipped this round

Each item below is its own branch off `main`, opened as a PR, with deliberately
**disjoint file sets** so they merge without conflicting. `awesome.md` lives only
on `main`.

| PR | Branch | What | Files |
|---|---|---|---|
| [#27](https://github.com/L-K-M/Kararead/pull/27) | `claude/fix-plaintext-escaping` | A1: escape + paragraph-ify plain-text bodies | `data/remote/Mappers.kt` (+test) |
| [#28](https://github.com/L-K-M/Kararead/pull/28) | `claude/reader-anchor-links` | A2: in-page anchors scroll instead of opening a browser | `reader/ReaderHtmlBuilder.kt` (+test) |
| [#29](https://github.com/L-K-M/Kararead/pull/29) | `claude/tts-polish` | B1/A4: audio focus, headset-unplug, speech rate, failure toast | `tts/ArticleSpeaker.kt`, `ui/reader/ReaderViewModel.kt`, `ui/reader/ReaderScreen.kt`, `data/prefs/SettingsRepository.kt` |
| [#30](https://github.com/L-K-M/Kararead/pull/30) | `claude/reader-zoom-mixedcontent` | B2: pinch-zoom + mixed-content compatibility | `ui/reader/ReaderWebView.kt` |
| [#31](https://github.com/L-K-M/Kararead/pull/31) | `claude/stats-polish` | A3: streak pluralization + friendlier zero-streak | `ui/stats/StatsScreen.kt` |
| [#32](https://github.com/L-K-M/Kararead/pull/32) | `claude/reader-typeface-shuffle` | D1: shuffle-typeface dice | `ui/reader/ReaderControls.kt` |

All six compile; #27 and #28 also add passing unit tests; #29 is lint-clean.

> **Note:** while this review was in flight, `main` concurrently removed "paged
> reading mode" and refactored the reading-settings sheet. All six branches were
> rebased onto that newer `main` and adapted (e.g. the anchor handler dropped its
> paged-mode branch), so the PRs apply cleanly to current `main`.
