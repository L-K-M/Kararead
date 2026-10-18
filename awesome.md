# awesome.md — a fresh review of Kararead

**Legend:** 🐞 bug · 🔧 issue/cleanup · ✨ feature · 💡 idea/delight
**Status:** ⬜ open · 🟢 shipped (PR) · ⏸️ deferred (documented, not done)

---

## A. Bugs & correctness

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

- **D2 💡 — "Finish by ~9:42pm" clock estimate** next to "12 min left" in the
  reader bar — a quietly motivating touch. **Status: ⏸️** (touches the reader top
  bar, which PR #29 also edits — deferred to avoid a conflict).

- **D3 💡 — Heading table-of-contents** for long articles (jump to a section).
  **Status: ⏸️**

- **D4 💡 — A daily reading-minutes goal** woven into the Stats streak. **Status: ⏸️**

- **D5 💡 — An "Auto" reader theme** that follows the system light/dark setting
  (and optionally the time of day). **Status: ⏸️** (touches the theme/prefs core).