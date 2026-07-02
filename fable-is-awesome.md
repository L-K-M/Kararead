# fable-is-awesome.md — a deep review of Kararead, by Claude Fable 5

A full-codebase review of Kararead (~11k lines of Kotlin/Compose), done in two passes:
an end-to-end personal read of every source file, plus a fan-out of six specialist
review agents (data/sync, reader core, highlights/TTS, screens/navigation,
performance, visual/a11y), each of whose findings I re-verified against the code
before it earned a place here. Security and product-gap coverage is from the
personal pass. Findings that didn't survive verification were dropped.

Where an item was already known and deferred in [`awesome.md`](awesome.md)
(A5–A7, B3–B5, C2–C4, D2–D5) it is only mentioned here if this review adds new
information or upgrades its priority.

**Legend:** 🐞 bug · 🔥 perf · 🎨 visual/a11y · 🔐 security · ✨ feature · 💡 idea · 🔧 engineering
**Status:** 🟢 implemented (branch/PR) · ⬜ open, worth doing · ⏸️ documented, deliberately not done now

First, credit where due: 4.8 built a genuinely good app. The offline outbox with
optimistic cache updates, the block-anchor scroll restore that survives image
loads, the OEM-toolbar highlight de-duplication (three layers deep!), the
failover interceptor, and the calm typography-first design are all thoughtful,
carefully-commented work. What follows is long because the review was thorough,
not because the app is bad.

---

## A. Bugs — reader core

- **A1 🐞 Font-size preference silently broken on comma-decimal locales** (major, verified)
  `ReaderHtmlBuilder.kt:124` and `:147` format the base font size with
  `"%.1f".format(...)`, which uses `Locale.getDefault()`. On a device set to
  German/French/Spanish/… this emits `--kr-font-size: 22,8px` — invalid CSS — so
  `font-size: var(--kr-font-size)` resolves to nothing and the whole typography
  scale collapses to the browser default. Every text-size slider position looks
  identical. Fix: `String.format(Locale.ROOT, "%.1f", …)`.
  **Status: 🟢 PR #39 (`fable/reader-css-fixes`)**

- **A2 🐞 Rotating the phone mid-article throws away your reading position** (major, verified)
  `MainActivity` declares no `configChanges`, so rotation (also split-screen,
  fold/unfold) recreates the activity. The `ReaderViewModel` survives, so
  `state.initialProgress`/`initialAnchor` keep their values from when the article
  was *opened* — and the rebuilt WebView's `onReady` restores to that stale point
  (`ReaderWebView.kt:265`). Read 40 minutes, rotate once, land back at page one’s
  offset. Fix: track the latest reported position in the ViewModel and restore
  from that. **Status: 🟢 PR #44 (`fable/reader-restore`)**

- **A3 🐞 Articles shorter than one screen never report progress — "Done · Next" never appears** (minor, verified)
  The only progress source is the JS `scroll` listener
  (`ReaderHtmlBuilder.kt:315`); nothing emits an initial fraction. A one-screen
  article can't scroll, so `state.progress` stays 0, the finish FAB
  (threshold ≥ 0.92, `ReaderScreen.kt:261`) never shows, and the article never
  reads as "almost done". Fix: emit one progress report at `onReady`
  (`scrollFraction()` already returns 1.0 for unscrollable documents).
  **Status: 🟢 PR #44 (`fable/reader-restore`)**

- **A4 🐞 Footnote / in-page anchor links kick you out to the browser at your server's root** (major, verified)
  `sanitize()` uses `Safelist.relaxed()` (`ReaderHtmlBuilder.kt:49`), which strips
  every `id`/`name` attribute — so the in-page anchor handler's
  `getElementById(...)` lookup can never succeed, it returns without
  `preventDefault`, the WebView resolves `#fn1` against the server-origin base
  URL, and `shouldOverrideUrlLoading` launches an external browser at your
  Karakeep root. Every footnote in every article. Fix: preserve `id` attributes
  through sanitization; also intercept unresolvable fragments as no-ops.
  **Status: 🟢 PR #45 (`fable/reader-links`)**

- **A5 🐞 `mailto:` links replace the article with a `net::ERR_UNKNOWN_URL_SCHEME` error page** (minor, verified)
  The safelist allows `mailto:`/`ftp:` hrefs, but `shouldOverrideUrlLoading`
  (`ReaderWebView.kt:317`) returns `false` for non-http(s) schemes, telling the
  WebView to navigate itself — which it can't. Fix: hand non-http(s) schemes to
  `ACTION_VIEW` and return `true`. **Status: 🟢 PR #45 (`fable/reader-links`)**

- **A6 🐞 WebView renderer crash kills the whole app** (minor, verified)
  No `onRenderProcessGone` override (`ReaderWebView.kt:303`): since Android O the
  framework kills the host app when the renderer dies (OOM on huge articles,
  system pressure while backgrounded) unless the client handles it. Fix: handle
  it — destroy the dead view and surface a "reader crashed, tap to reload" state.
  **Status: 🟢 PR #45 (`fable/reader-links`)**

- **A7 🐞 Changing font size mid-article loses your place** (minor, verified)
  `applyPrefsScript` only rewrites CSS variables; the document reflows but
  `scrollTop` stays at the old pixel offset, so at 60% of a long read a text-size
  bump dumps you paragraphs away. The block-anchor machinery already exists —
  capture the anchor before applying prefs and re-anchor after.
  **Status: ⬜** (fits naturally as a follow-up to `fable/perf-scroll`'s anchor cache)

- **A8 🐞 Rapid page turns run overlapping scroll animations that fight each other** (minor, verified)
  `krPageBy` (`ReaderHtmlBuilder.kt:392`) starts a fresh 160 ms rAF loop per call
  and never cancels the previous one — holding a volume key interleaves loops
  writing `scrollTo` toward different targets. Fix: a generation counter so a new
  page-turn cancels the old animation. **Status: 🟢 PR #45 (`fable/reader-links`)**

- **A9 🐞 "Keep screen on" leaks to the whole app** (minor, verified)
  `ReaderScreen.kt:219` sets `view.keepScreenOn` on the activity-wide compose
  view in a `LaunchedEffect` with no dispose-time reset; nothing else ever clears
  it. Enable the pref, read one article, and the screen never sleeps again on any
  screen. Fix: `DisposableEffect` that resets it on dispose.
  **Status: 🟢 PR #44 (`fable/reader-restore`)**

- **A10 🐞 Volume-key paging silently dies after "Done · Next"** (minor, verified)
  Auto-advance navigates reader→reader; the new screen registers its volume-key
  handler at transition start, then the *old* screen's `onDispose` runs at
  transition end and nulls the shared `MainActivity` slot
  (`ReaderScreen.kt:254`). Keys stop paging until you leave and re-enter. Fix:
  ownership-aware clear (only null the slot if it still holds your handler).
  **Status: 🟢 PR #44 (`fable/reader-restore`)**

- **A11 🐞 Reopening a half-read article yanks the chrome away immediately** (polish, verified)
  The restore's programmatic scroll registers as "scrolling down"
  (`ReaderScreen.kt:292`), so the top bar slides in and is snatched away ~150 ms
  later with no user gesture. Fix: suppress the scroll-direction callback during
  restore. **Status: 🟢 PR #44 (`fable/reader-restore`)**

## B. Bugs — data & sync

- **B1 🐞 A still-processing article gets cached empty and never heals** (major, verified)
  `getArticle()` is strictly cache-first and caches unconditionally
  (`KarakeepRepository.kt:111–124`). Open (or background-prefetch) a just-shared
  link before Karakeep's crawler finishes → a row with `html = null` is cached →
  every subsequent open shows "This article has no readable content yet"
  *forever* (prefetch can't heal it either — it's a cache hit). Only manual
  Refresh or the 30-day cleanup escapes. Fix: treat a cached row with null html
  as a miss, and don't cache contentless fetches.
  **Status: 🟢 PR #40 (`fable/article-cache`)**

- **B2 🐞 An offline archive/favourite queued in the outbox can revert a newer online change** (major, verified)
  `setArchived`/`setFavourited` only touch the outbox on failure. Archive
  offline (op queued) → connectivity returns → un-archive online (PATCH
  succeeds) → the still-queued op replays later and silently re-archives. Same
  window breaks Undo right after connectivity returns. Fix: a successful direct
  PATCH deletes any queued op for the same (bookmark, field).
  **Status: 🟢 PR #42 (`fable/outbox-robustness`)**

- **B3 🐞 Outbox ops are silently dropped after ~8 minutes of server unreachability** (major, verified)
  `flushPendingOps` counts every failure identically and drops ops at 5 attempts
  (`KarakeepRepository.kt:206`); the worker's exponential backoff starts at 30 s,
  so five attempts burn in minutes. A phone with internet but an unreachable
  home server (train, VPN down) permanently discards the user's archives with no
  signal. Fix: drop immediately on definitive 4xx, never count transport errors
  against the cap. **Status: 🟢 PR #42 (`fable/outbox-robustness`)**

- **B4 🐞 Closing the reader fast can lose an archive tap entirely** (minor, verified)
  Mutations run in `viewModelScope`; navigating back cancels the in-flight PATCH,
  `runCatching` mis-reads the `CancellationException` as "offline", and the
  follow-up `queueOp` Room call throws on the already-cancelled coroutine — the
  tap vanishes. Fix: run the network+queue section under
  `withContext(NonCancellable)`. **Status: 🟢 PR #42 (`fable/outbox-robustness`)**

- **B5 🐞 `refreshReadState` overwrites optimistic flags while an outbox op is still pending** (minor, verified)
  Reopening an article that was archived/favourited offline pulls stale server
  flags into the cache and UI before the outbox has flushed
  (`KarakeepRepository.kt:134`). Fix: skip fields that have a pending op.
  **Status: 🟢 PR #42 (`fable/outbox-robustness`)**

- **B6 🐞 Undoing an archive doesn't restore the offline download** (minor, verified)
  Archiving eagerly evicts the cached copy (`KarakeepRepository.kt:154`); Undo
  re-PATCHes but never re-caches, so the article returns to the queue stripped of
  its download and vanishes from "jump back in". Fix: re-cache on undo.
  **Status: 🟢 PR #42 (`fable/outbox-robustness`)**

- **B7 🐞 Cache cleanup evicts articles you're actively reading** (minor, verified)
  `cachedAt` is only written on cache misses, so it means "first cached", not
  "last used" — a long read opened yesterday but downloaded 31 days ago gets
  deleted by `CacheCleanupWorker`. Fix: touch `cachedAt` on cache-hit reads.
  **Status: 🟢 PR #40 (`fable/article-cache`)**

- **B8 🐞 A malformed server URL crashes the app during onboarding** (critical, verified)
  `testConnection()` calls `apiProvider.configure()` *before* its try/catch
  (`KarakeepRepository.kt:286`); Retrofit's `baseUrl()` throws
  `IllegalArgumentException` on unparsable URLs (e.g. a space in the host), the
  exception escapes `OnboardingViewModel.connect`'s coroutine, and the app
  crashes on the Connect tap. Fix: configure inside the try; validate in
  `normalizeBaseUrl`. **Status: 🟢 PR #38 (`fable/connection-crash`)**

- **B9 🐞 "Only on Wi-Fi" triggers an immediate download over mobile data** (major, verified)
  `OfflineSync.runNow()` hardcodes unconstrained network (`OfflineSync.kt:43`)
  and `SettingsViewModel.updateOffline` fires it after *every* offline-pref
  change — including turning ON "Only on Wi-Fi" while on mobile data, which
  immediately downloads up to 100 articles plus images over that data. Fix:
  kick an immediate sync only when enabling, with the user's constraint.
  **Status: 🟢 PR #41 (`fable/offline-wifi-only`)**

- **B10 🐞 Sign-out leaves the outbox, progress, and stats behind** (minor, verified)
  `signOut()` clears cache + connection only. Queued ops from server A replay
  against server B (`PendingOpDao` has no `clear()`), and progress/stats bleed
  across accounts. Fix: clear all local per-account state and cancel scheduled
  workers. **Status: 🟢 PR #51 (`fable/signout-share`)**

- **B11 🐞 Share-to-save blocks the host app and can silently lose the link** (minor, verified)
  `ShareActivity` runs the network save in `lifecycleScope` behind an invisible
  full-screen activity: a slow server freezes the sharing app for up to a minute,
  and leaving mid-save cancels the coroutine — link lost, no feedback. Fix: hand
  the save to the application scope (or WorkManager), finish immediately, toast
  the outcome. **Status: 🟢 PR #51 (`fable/signout-share`)**

- **B12 🐞 "Saved to Karakeep ✓" even when adding to your read-later list failed** (minor, verified)
  `saveLink` swallows `addBookmarkToList` failures (`KarakeepRepository.kt:224`),
  so the article never appears in the user's home queue despite the success
  toast. Fix: report the partial failure. **Status: 🟢 PR #51 (`fable/signout-share`)**

- **B13 🐞 Transient image errors are force-cached for a year** (minor, verified)
  `ForceCacheInterceptor` rewrites `Cache-Control` on *every* response including
  404s (`AssetLoader.kt:123`) — an asset fetched moments before it was ready
  serves a cached 404 forever. Fix: only rewrite cache headers on successful
  responses. **Status: 🟢 PR #49 (`fable/auth-scope`)**

- **B14 🐞 Paging source converts cancellation into an error state** (minor, verified)
  `BookmarksPagingSource.load` catches all `Exception` including
  `CancellationException` (`BookmarksPagingSource.kt:27`) — the documented Paging
  anti-pattern. Fix: rethrow cancellation. **Status: 🟢 PR #48 (`fable/list-fixes`)**

- **B15 🐞 Failover retries non-idempotent POSTs — duplicate saves** (minor, verified)
  `FailoverInterceptor` re-sends any request on IOException
  (`ApiProvider.kt:195`). A share-save whose response was lost (read timeout)
  gets POSTed again — with primary and fallback pointing at the same box (LAN +
  VPN, the common setup), that's a duplicate bookmark. Fix: only fail over
  idempotent methods. **Status: 🟢 PR #49 (`fable/auth-scope`)**

- **B16 🐞 Offline cache never reconciles cross-client archives** (minor, verified)
  Articles archived from the Karakeep web UI stay "unread" in the offline
  fallback for up to 30 days; `syncOffline` only fills, never reconciles.
  Fix: after fetching the authoritative top-N, evict/flag cached unread rows
  that dropped out. **Status: ⬜**

- **B17 🔧 `fallbackToDestructiveMigration` can wipe the only irreplaceable data** (minor)
  Reading progress and streaks are client-side-only; one schema bump without a
  migration silently destroys them (`DatabaseModule.kt:30`). Worth removing the
  fallback (fail loudly instead) or exporting stats. **Status: ⬜**

## C. Bugs — screens & shell

- **C1 🐞 The Library silently jumps back to the read-later tab on any settings write** (major, verified)
  `LibraryViewModel.init` flips `tab` to READ_LATER whenever
  `settings.readLaterList` emits non-null and the tab is INBOX
  (`LibraryViewModel.kt:129`) — and that flow re-emits on *every* write to the
  settings DataStore. Switch to Inbox, change the sort (or move any reader
  slider): you're teleported back to Read Later. Fix: `distinctUntilChanged()`
  and only auto-switch on the first load. **Status: 🟢 PR #47 (`fable/library-tab-guard`)**

- **C2 🐞 Double-tapping a card opens the article twice** (minor, verified)
  All reader/list-detail navigations lack `launchSingleTop`
  (`Navigation.kt:114,119,128,145,159`) — two quick taps stack two identical
  reader entries; Back shows the same article again.
  **Status: 🟢 PR #46 (`fable/nav-insets`)**

- **C3 🐞 Every tab screen pads for the status bar twice** (major, verified — visible in `screenshots/home.jpeg`)
  The outer `Scaffold`'s content padding includes the status-bar inset (no
  `topBar`), `tabComposable` applies it (`Navigation.kt:189`), then each screen's
  own `TopAppBar` pads for the status bar again — a full status-bar-height dead
  gap above every tab's title, plainly visible in the repo's own screenshots.
  Fix: `contentWindowInsets = WindowInsets(0)` on the outer Scaffold (bottom bar
  handles its own insets). **Status: 🟢 PR #46 (`fable/nav-insets`)**

- **C4 🐞 Pull-to-refresh is impossible exactly where you need it — empty states** (major, verified)
  `MessageState` is a non-scrollable Box; M3's `PullToRefreshBox` needs a
  scrollable child, so "You're all caught up ✨" — the home screen's steady state
  — cannot be refreshed by pull at all (`BookmarkList.kt:168`). Fix: make the
  empty/error branches scrollable. **Status: 🟢 PR #48 (`fable/list-fixes`)**

- **C5 🐞 Swipe actions can act on a stale bookmark after a refresh** (minor, verified)
  `rememberSwipeToDismissBoxState`'s `confirmValueChange` captures the first
  composition's `bookmark` (state is `rememberSaveable` with no keys,
  `BookmarkList.kt:346`) — after a refresh changes `favourited`, an "Unfavourite"
  swipe re-favourites. Fix: `rememberUpdatedState(bookmark)` inside the lambda.
  **Status: 🟢 PR #48 (`fable/list-fixes`)**

- **C6 🐞 Message stack renders without `key()` — dismissals reset neighbours' timers** (minor, verified)
  `MessageStackHost` uses a positional `forEach` (`MessageStack.kt:98`) despite
  `StackMessage.id`'s comment claiming otherwise; when the oldest card leaves,
  every younger card shifts composition slots, flickers, and restarts its
  auto-dismiss timer. Fix: wrap rows in `key(message.id)`.
  **Status: 🟢 PR #48 (`fable/list-fixes`)**

- **C7 🐞 List names are URI-decoded twice** (minor, verified)
  Navigation already decodes path arguments; `Navigation.kt:155` and
  `ListBookmarksViewModel.kt:32` decode again, mangling names containing `%`.
  **Status: 🟢 PR #46 (`fable/nav-insets`)**

- **C8 🐞 Toolbar "Surprise me" can crash on an emptied list** (minor, verified)
  `(0 until items.itemCount).random()` inside `onClick` (`LibraryScreen.kt:96`);
  the count guard is composition-time, so a refresh landing between recomposition
  and tap throws on an empty range. **Status: 🟢 PR #46 (`fable/nav-insets`)**

- **C9 🐞 Search results offer no archive/favourite at all** (minor, verified)
  Swipe is disabled in search *and* no callbacks are passed
  (`SearchScreen.kt:145`), so the long-press sheet — whose own comment says it
  exists precisely for search — hides those rows too. Fix: wire the mutations.
  **Status: 🟢 PR #54 (`fable/search-actions`)**

- **C10 🐞 Stats' "today" goes stale after midnight** (minor, verified)
  `LocalDate.now()` is evaluated only when the Room flow emits
  (`StatsViewModel.kt:31`); an app alive across midnight highlights the wrong bar
  and shows yesterday's minutes as "today" until the next write. Fix: merge a
  midnight ticker into the flow. **Status: 🟢 PR #57 (`fable/stats-midnight`)**

- **C11 🐞 TTS volume can't be adjusted — volume keys page instead** (major, verified; three reviewers found this independently)
  Volume-key paging stays active during narration (`ReaderScreen.kt:249`), and
  `MainActivity` swallows both key events. The page jump is even undone moments
  later by follow-narration auto-scroll. Fix: fall through to system volume
  while `speech.active`. **Status: 🟢 PR #43 (`fable/tts-fixes`)**

## D. Bugs — TTS & highlights

- **D1 🐞 TTS can hang (or die) on CJK and unpunctuated articles** (major, verified)
  `chunkText` splits only on ASCII `[.!?]` + whitespace, and Jsoup's `.text()`
  collapses the newlines its second branch needs (`ArticleSpeaker.kt:317`).
  Chinese/Japanese articles (。！？) become one giant chunk exceeding
  `getMaxSpeechInputLength()` → the engine errors → state stays "playing"
  forever, silent. Fix: split on CJK terminators and hard-wrap chunks at a safe
  length. **Status: 🟢 PR #43 (`fable/tts-fixes`)**

- **D2 🐞 After a TTS engine init failure, "Listen" is dead for the session** (minor, verified)
  `ensureEngine` early-returns on `tts != null` but init failure leaves the field
  set and `pendingStart` uncleared (`ArticleSpeaker.kt:113`); every retry is a
  no-op. Fix: reset `tts = null` on failed init so a retry re-creates the engine.
  **Status: 🟢 PR #43 (`fable/tts-fixes`)**

- **D3 🔥 Every TTS start/skip/rate-change floods thousands of binder calls** (major, verified)
  `enqueueFrom` issues one synchronous `speak()` IPC per remaining sentence
  (`ArticleSpeaker.kt:294`) — thousands on long articles, on the main thread,
  repeated for every skip tap and rate change. Fix: window the queue (~20
  utterances, refilled from `onDone`). **Status: 🟢 PR #43 (`fable/tts-fixes`)**

- **D4 🐞 Audio-focus denial is ignored — narration talks over phone calls** (minor, verified)
  The result of `requestAudioFocus` is discarded (`ArticleSpeaker.kt:152`). Fix:
  don't start when focus is denied. **Status: 🟢 PR #43 (`fable/tts-fixes`)**

- **D5 🐞 Skip while paused unexpectedly resumes playback** (polish, verified)
  `skipBy` always re-enqueues (`ArticleSpeaker.kt:276`). Fix: preserve the paused
  state, just move the index. **Status: 🟢 PR #43 (`fable/tts-fixes`)**

- **D6 🎨 The listen bar's ±10-second icons perform one-sentence skips** (polish, verified)
  `Replay10`/`Forward10` glyphs literally promise seconds
  (`ReaderScreen.kt:824`); the action is a sentence. `SkipPrevious`/`SkipNext`
  match. **Status: 🟢 PR #43 (`fable/tts-fixes`)**

- **D7 🐞 Highlight exports collide across articles with the same title** (major, verified)
  Files are named `safeFileName(title).md` and deliberately overwritten
  (`DocumentTreeWriter.kt:43`); two "Weekly Update" articles silently destroy
  each other's exports — including via the background auto-save. Fix: suffix a
  short stable hash of the bookmark id. **Status: 🟢 PR #50 (`fable/highlights-fixes`)**

- **D8 🐞 Highlight delete/note failures are silent and never reverted** (minor, verified)
  `removeHighlight` fire-and-forgets; a failed delete resurrects the highlight on
  next open, and the auto-export happily mirrors the wrong state
  (`ReaderViewModel.kt:279`). Fix: revert the optimistic update and say so.
  **Status: 🟢 PR #50 (`fable/highlights-fixes`)**

- **D9 🐞 Notes on text-less highlights vanish from every export** (minor, verified)
  `highlightsToMarkdown` skips highlights with empty text entirely, note included
  (`HighlightsExport.kt:14`) — data from other Karakeep clients is silently lost.
  **Status: 🟢 PR #50 (`fable/highlights-fixes`)**

- **D10 🐞 The Highlights screen shows stale data after editing in the reader** (minor, verified)
  The list loads only in `init` (`HighlightsViewModel.kt:55`); returning from the
  reader shows deleted highlights and wrong counts — and exports export them.
  Fix: refresh on resume. **Status: 🟢 PR #50 (`fable/highlights-fixes`)**

- **D11 🔥 Highlights screen fires an unbounded parallel request per article** (minor, verified)
  One `async { getBookmarkMeta(id) }` per highlighted article with no cap
  (`HighlightsViewModel.kt:78`) — a classic N+1 hammering small self-hosted
  servers. Fix: bound the concurrency. **Status: 🟢 PR #50 (`fable/highlights-fixes`)**

- **D12 🐞 The reader's own highlights sheet clips long lists** (minor, verified)
  A plain non-scrollable Column in a bottom sheet (`ReaderScreen.kt:684`) — a
  dozen highlights and the rest are unreachable. The voice picker two functions
  down already does it right (`LazyColumn` + `heightIn`).
  **Status: 🟢 PR #50 (`fable/highlights-fixes`)**

- **D13 🐞 Highlights are online-only** (minor, verified)
  No local cache: offline, a downloaded article renders zero of your highlights
  and creating one just fails. A Room-backed highlight cache + outbox (mirroring
  the archive outbox) is the real fix. **Status: ⬜** (larger; the outbox
  pattern to copy now exists)

- **D14 🐞 "Save/Share all highlights" silently truncates at 1000** (minor, verified)
  `getAllHighlights(max = 1000)` (`KarakeepRepository.kt:244`). At minimum, say
  so; ideally page the whole set for exports. **Status: ⬜**

- **D15 🐞 Selecting the title/byline and tapping Highlight can silently do nothing — or highlight the wrong text** (minor, verified)
  Capture returns `'nocap'` into a null callback (`ReaderWebView.kt:243`), and a
  stale `krLastRange` fallback can capture a *previous* selection instead
  (`ReaderHtmlBuilder.kt:526`). Fix: read the result string, toast on `nocap`,
  and age out the stale range. **Status: ⬜**

## E. Performance & smoothness

- **E1 🔥 The scroll listener does an O(article-length) DOM walk on every frame** (major, verified)
  `krComputeAnchor()` runs `querySelectorAll` over eleven selectors and calls
  `getBoundingClientRect()` per block *inside every scroll rAF*
  (`ReaderHtmlBuilder.kt:324`) — forced layout reads per frame that scale with
  article length. This is the likeliest source of the scroll/page-turn stutter.
  Fix: cache block offsets once (invalidate on resize/image-load/prefs), binary
  search per frame. **Status: 🟢 PR #52 (`fable/perf-scroll`)**

- **E2 🔥 Every scroll frame recomposes the whole reader** (major, verified)
  The bridge posts per frame; `ReaderUiState` is copied per frame; the top bar
  title and finish-FAB visibility read `state.progress` directly
  (`ReaderScreen.kt:261,323`). Fix: `derivedStateOf` for the minutes-left label
  and the ≥0.92 threshold so per-frame updates only touch the 3 dp progress line.
  **Status: 🟢 PR #52 (`fable/perf-scroll`)**

- **E3 🔥 Jsoup sanitization of the full article runs on the main thread, inside composition, during the open transition** (major, verified)
  `ReaderHtmlBuilder.build` (→ `Jsoup.clean`) runs in a `remember{}`
  (`ReaderWebView.kt:211`) — 20-100 ms+ for long articles, exactly while the
  280 ms slide-in animation plays. That's the page-open hitch. Fix: sanitize on
  a background dispatcher in the ViewModel. **Status: 🟢 PR #53 (`fable/perf-article-open`)**

- **E4 🔥 Opening an article parses the same HTML with Jsoup three times** (major, verified)
  `toReaderArticle` → `htmlToPlainText`, then `toDomain` does it twice more for
  the excerpt and reading time (`Mappers.kt:38,57,71`). Fix: parse once, share.
  **Status: 🟢 PR #53 (`fable/perf-article-open`)**

- **E5 🔥 Every article open pays a redundant bookmark GET before highlights load** (minor, verified)
  `refreshReadState` fires even when the article was just fetched live
  (`ReaderViewModel.kt:161`), and highlights + next-up wait behind it. Fix: only
  reconcile flags when served from cache. **Status: 🟢 PR #53 (`fable/perf-article-open`)**

- **E6 🔥 Typography sliders rewrite DataStore and reflow the document per drag step** (minor, verified)
  Each step is a full preferences-file write plus a WebView reflow round-trip
  (`ReaderControls.kt:128`). Fix: apply live from an in-memory override, persist
  debounced. **Status: ⬜**

- **E7 🔥 List keys use `get()` instead of `peek()`** (minor, verified)
  The custom `itemKey` (`BookmarkList.kt:243`) registers paging load hints during
  key computation — the exact reason the official helper uses `peek()`. Also:
  no `contentType`. **Status: 🟢 PR #48 (`fable/list-fixes`)**

- **E8 🔥 Whole-table progress maps rebuild on every save, for every backstack ViewModel** (minor, verified)
  `allProgress()` etc. are collected `Eagerly` by Library/Search while the reader
  saves progress every ~400 ms (`LibraryViewModel.kt:67`). Fine today, worth
  `WhileSubscribed` + conflation before lists grow. **Status: ⬜**

- **E9 💡 Prefetch the next-up article while the finish FAB is showing** (verified feasible)
  `nextUp` is already resolved to label the button; one cache-first `getArticle`
  + image prefetch makes "Done · Next" open instantly — and work offline.
  **Status: 🟢 PR #53 (`fable/perf-article-open`)**

## F. Visual, layout & accessibility

- **F1 🎨 Highlighted text is near-illegible in Dark/Black themes** (major, verified)
  `mark.kr-hl` composites amber at 55% under `color: inherit`
  (`ReaderHtmlBuilder.kt:256`): contrast ≈ 2.6:1 in Dark/Black — the text the
  user cared enough to mark becomes the hardest to read at night. And on
  WebViews older than Chromium 111 the `color-mix()` is dropped entirely:
  highlights are invisible. Fix: per-palette highlight color via a CSS variable
  + an rgba fallback line. **Status: 🟢 PR #39 (`fable/reader-css-fixes`)**

- **F2 🎨 Reader body text ignores the system font-size setting** (major, verified)
  `textZoom = 100` (`ReaderWebView.kt:257`) disables the WebView's font-scale
  handling and the CSS sizes in raw px — a low-vision user at 2× system font
  gets 19 px anyway and must maintain a second, app-private setting. Fix:
  multiply the base size by `fontScale`. **Status: 🟢 PR #39 (`fable/reader-css-fixes`)**

- **F3 🎨 Sepia's secondary text misses WCAG AA — and styles whole blockquotes** (minor, verified — 4.44:1)
  `#7a6a55` on `#f4ecd8` (`ReaderHtmlBuilder.kt:32`); blockquote bodies and small
  figcaptions render below AA in the classic long-reading theme. `#6f6049`
  clears it while staying warm. **Status: 🟢 PR #39 (`fable/reader-css-fixes`)**

- **F4 🎨 Status-bar icons don't follow the in-app theme override** (major, verified)
  `enableEdgeToEdge()` is called once with system-derived defaults
  (`MainActivity.kt`); App theme = Dark on a light-mode device gives dark icons
  on dark backgrounds all session. Fix: re-apply on the resolved theme.
  **Status: 🟢 PR #55 (`fable/status-bar-theme`)**

- **F5 🎨 White flash on activity recreation in dark mode** (minor, verified)
  `Theme.Kararead`'s parent is a Light platform theme with no `values-night`
  variant — rotation/process-restore blinks white before Compose draws the dark
  UI. OLED night readers feel this one. **Status: 🟢 PR #55 (`fable/status-bar-theme`)**

- **F6 🎨 Reader chrome follows the app theme, not the reader palette** (major, verified)
  Night reading with a light app theme: a bright cream top bar, FAB, and listen
  bar slide in over a pure-black page (`ReaderScreen.kt:445`). Deriving the
  reader's chrome surfaces from the active `ReaderPalette` would make the whole
  screen one calm object. **Status: 🟢 PR #61 (`fable/reader-chrome-palette`)**

- **F7 🎨 Reader-controls rows are broken for TalkBack** (minor, verified)
  Theme swatches announce "Aa" four times (the selected one announces *nothing* —
  its only content is a null-description icon), and the toggle rows' labels
  aren't tappable or merged with their switches (`ReaderControls.kt:177,222`).
  Settings' own `ToggleSetting` does it right two files away.
  **Status: 🟢 PR #56 (`fable/a11y-controls`)**

- **F8 🎨 Accent swatches: 34 dp targets, colors unlabeled** (minor, verified)
  Eight identical "button" announcements; the palette's names already exist as
  comments in `Color.kt` (`SettingsScreen.kt:225`).
  **Status: 🟢 PR #56 (`fable/a11y-controls`)**

- **F9 🎨 Search content hides behind the keyboard** (minor, verified)
  With edge-to-edge, `adjustResize` doesn't apply automatically; Onboarding uses
  `imePadding()` but Search doesn't (`SearchScreen.kt:52`).
  **Status: 🟢 PR #54 (`fable/search-actions`)**

- **F10 🎨 Offline banner's "Retry" is a bare text with a ~34×20 dp target** (polish, verified)
  Precisely the control an offline user on a moving train needs to hit
  (`BookmarkList.kt:328`). A `TextButton` is a drop-in.
  **Status: 🟢 PR #48 (`fable/list-fixes`)**

- **F11 🎨 Reader HTML hardcodes `lang="en"`** (minor, verified)
  TalkBack mispronounces every non-English article (`ReaderHtmlBuilder.kt:78`).
  Unknown beats wrong: drop the attribute. **Status: 🟢 PR #45 (`fable/reader-links`)**

- **F12 🎨 Onboarding ignores status-bar/cutout insets in landscape** (polish, verified)
  Its route gets no NavHost padding and the Column has no
  `safeDrawingPadding()` (`OnboardingScreen.kt:41`).
  **Status: 🟢 PR #38 (`fable/connection-crash`)**

- **F13 🎨 Reader top-bar subtitle clips at large system font scales** (polish, plausible)
  Two stacked text lines in the fixed 64 dp bar (`ReaderScreen.kt:316`); at
  1.5–2× the "min left" line — the progress affordance — clips first. Consider a
  single-line "site · 12 min left". **Status: ⏸️** (needs on-device verification)

## G. Security & robustness

- **G1 🔐 The bearer token is attached by host-only match — scheme and port ignored** (major, verified)
  `authHeaderForUrl` (`ApiProvider.kt:141`) compares only hosts; a saved page
  containing `<img src="http://your-host/x.png">` gets your `ak1_` key attached
  and sent over cleartext (the manifest allows it — known A7); an `https://host:8443`
  image delivers it to a different service on the same machine. Article HTML is
  attacker-supplied. Fix: require scheme+port+host to match the configured base.
  **Status: 🟢 PR #49 (`fable/auth-scope`)**

- **G2 🔐 HTTP request logging is active in release builds** (minor, verified)
  `HttpLoggingInterceptor` at BASIC is added unconditionally
  (`ApiProvider.kt:78`); URLs — including search queries — go to logcat on user
  devices (the Authorization header is redacted, credit where due). Fix: gate on
  `BuildConfig.DEBUG`. **Status: 🟢 PR #49 (`fable/auth-scope`)**

- **G3 🔐 What a malicious article can and can't do** (informational)
  Verified: the Jsoup safelist strips scripts/iframes/styles before the document
  is built, so article content cannot reach the `AndroidReader` JS bridge; the
  bridge itself only receives primitives. Images are the remaining vector (G1).
  Mixed-content COMPATIBILITY_MODE is a deliberate, documented self-hosting
  tradeoff. No change needed beyond G1/A7. **Status: ⏸️ (by design)**

- **G4 🔐 `testConnection` repoints the live singleton client even when the test fails** (minor, verified)
  `configure()` happens before the probe with no revert
  (`KarakeepRepository.kt:286`). Harmless today (onboarding only runs signed
  out), but a landmine for the edit-connection feature (H1). Fix alongside H1.
  **Status: 🟢 PR #60 (`fable/edit-connection`)** — the edit flow restores the
  live client to the saved connection on a failed test.

## H. Missing features (the reader you'd expect)

- **H1 ✨ Edit the connection without signing out** (the top gap, verified pain)
  Rotating an API key or moving a server currently requires sign-out, which now
  also (correctly) wipes local state. A "Connection" editor reusing the
  onboarding form — plus G4's fix — is the answer.
  **Status: 🟢 PR #60 (`fable/edit-connection`)**

- **H2 ✨ Tag chips on cards** (upgrade of deferred B3)
  `Bookmark.tags` is already populated; one muted `#tag` run in the metadata line
  aids triage without breaking the calm. **Status: 🟢 PR #59 (`fable/tag-chips`)**

- **H3 ✨ An "Auto" reader theme following the system** (upgrade of deferred D5)
  Every serious reader app has it; the palette machinery makes it a small enum +
  resolution change. Sepia-by-day/Black-by-night is the natural extension.
  **Status: 🟢 PR #58 (`fable/auto-theme`)**

- **H4 ✨ "Finish by ~9:42 pm"** (deferred D2, now unblocked — PR #29 merged)
  Next to "12 min left" in the reader bar. **Status: 🟢 PR #52 (`fable/perf-scroll`)**

- **H5 ✨ Jump from the Highlights screen to the highlight** (high value, cheap)
  Rows already know the bookmark; marks already carry `data-id` — a nav argument
  plus `scrollIntoView` lands the user on their own quote instead of the saved
  scroll position. **Status: 🟢 PR #63 (`fable/jump-to-highlight`)**

- **H6 ✨ Table of contents for long articles** (deferred D3) — heading blocks are
  already enumerated by the anchor machinery; a sheet listing h2/h3s with
  `scrollIntoView` is most of it. **Status: 🟢 PR #64 (`fable/reader-toc`)**

- **H7 ✨ Add/remove an article to/from lists in-app** — the API calls exist and
  are used by ShareActivity; the reader overflow and long-press sheet are the
  natural homes. **Status: ⬜**

- **H8 ✨ Media-session TTS** (deferred B5) — still the right call that it's a
  larger effort; still the single biggest listening upgrade (lock-screen
  controls, survives backgrounding). **Status: ⏸️**

- **H9 ✨ Backup/restore of local-only data** — progress, streaks and stats are
  unrecoverable by design today (see B17); a JSON export/import in Settings is
  cheap insurance. **Status: ⬜**

- **H10 ✨ Surface the outbox** — after B2/B3, a quiet "N changes waiting to sync"
  row in Settings (with retry) turns invisible best-effort into trust.
  **Status: ⬜**

## I. Delight — novel, quirky, worth-smiling-at

- **I1 💡 Hero image in the reader header** — the library already has the cover;
  articles whose lead image isn't in the body open as a wall of text. One
  `<img class="kr-hero">` with a duplicate-check restores the magazine moment.
  **Status: ⬜**

- **I2 💡 Reading-time weather report** — the Library header already knows the
  queue; "≈ 1 h 40 m of reading in your queue" (from cached reading times)
  reframes the pile as a plan, not a debt.

- **I3 💡 A GitHub-style reading heatmap** in Stats — `reading_day` has everything;
  12 weeks of little squares is the single most habit-forming stats visual.
  **Status: 🟢 PR #62 (`fable/stats-heatmap`)**

- **I4 💡 End-of-article flourish** — you already tally active reading seconds;
  "Read in 14 min · usually takes 18" under the — end — marker is a tiny,
  honest brag.

- **I5 💡 Spoken-sentence highlight** — while narrating, softly mark the current
  sentence in the WebView (the chunk index maps approximately via text search)
  so eyes and ears stay in sync. Pairs with the follow-scroll.

- **I6 💡 Quote cards** — render a highlight + title + site into a shareable
  image (Compose → bitmap); highlights are the app's soul and this is how they
  travel.

- **I7 💡 Sepia at sunset** — an optional twist on H3's Auto theme: Light → Sepia
  in the evening → Dark at night, using sunrise/sunset or plain clock hours. A
  reader app that gets warmer as your day winds down.

- **I8 💡 App shortcuts** — long-press the launcher icon: "Continue: ⟨last
  article⟩" (the recents strip already knows) and "Surprise me".

- **I9 💡 Streak forgiveness token** — one "quiet day" per week that doesn't
  break the streak (D4-adjacent). Calm apps forgive.

- **I10 💡 Pull-to-refresh haiku** — the refresh spinner occasionally shows a
  one-line reading proverb. Cheap, dumb, delightful. (Optional. But fun.)

## J. Engineering health

- **J1 🔧 The duplicated `ApiProvider` configuration (known C2) is still benign** — confirmed, no action.
- **J2 🔧 `backup_rules.xml` "encrypted DataStore" comment (known C3)** — still misleading; one-word fix bundled into `fable/auth-scope`. **Status: 🟢**
- **J3 🔧 ViewModel/flow tests remain thin (known C4)** — the fixes in this review add tests around the repository, TTS chunking, exports and HTML builder; ViewModel coverage is still the gap. **Status: ⬜**
- **J4 🔧 WorkManager init + two enqueues run in `Application.onCreate`** on the main thread — acceptable, but easy to defer if cold-start ever matters. **Status: ⏸️**
- **J5 🔧 `MONO` reader font declares 'JetBrains Mono', which isn't bundled** — falls back to Courier New. Either bundle it or re-order the stack to `monospace` first. **Status: ⬜**

---

## Implementation plan

Each branch is based on `main` and sized to review comfortably. Entries not
listed are ⬜/⏸️ as marked above. Conflict surfaces were kept small deliberately;
where two branches touch the same file they touch different functions.

| Branch | Entries | Files touched |
|---|---|---|
| `fable/connection-crash` (#38) | B8, F12 | repository, ApiProvider, onboarding |
| `fable/reader-css-fixes` (#39) | A1, F1, F2, F3 | ReaderHtmlBuilder, ReaderWebView |
| `fable/article-cache` (#40) | B1, B7 | KarakeepRepository |
| `fable/offline-wifi-only` (#41) | B9 | SettingsViewModel, OfflineSync |
| `fable/outbox-robustness` (#42) | B2, B3, B4, B5, B6 | KarakeepRepository, Daos, LibraryViewModel |
| `fable/tts-fixes` (#43) | D1–D6, C11 | ArticleSpeaker, ReaderScreen |
| `fable/reader-restore` (#44) | A2, A3, A9, A10, A11 | ReaderViewModel, ReaderScreen, ReaderWebView, ReaderHtmlBuilder |
| `fable/reader-links` (#45) | A4, A5, A6, A8, F11 | ReaderHtmlBuilder, ReaderWebView |
| `fable/nav-insets` (#46) | C2, C3, C7, C8 | Navigation, LibraryScreen, ListBookmarksViewModel |
| `fable/library-tab-guard` (#47) | C1 | LibraryViewModel |
| `fable/list-fixes` (#48) | C4, C5, C6, E7, F10, B14 | BookmarkList, MessageStack, Common, BookmarksPagingSource |
| `fable/auth-scope` (#49) | G1, G2, B13, B15, J2 | ApiProvider, AssetLoader, backup_rules |
| `fable/highlights-fixes` (#50) | D7–D12 | ReaderViewModel, HighlightsViewModel/Screen, ReaderScreen, DocumentTreeWriter, HighlightsExport |
| `fable/signout-share` (#51) | B10, B11, B12 | SettingsViewModel, ShareActivity, KarakeepRepository, Daos |
| `fable/perf-scroll` (#52) | E1, E2, H4 | ReaderHtmlBuilder (JS), ReaderScreen |
| `fable/perf-article-open` (#53) | E3, E4, E5, E9 | ReaderViewModel, Mappers, ReaderWebView |
| `fable/search-actions` (#54) | C9, F9 | SearchViewModel, SearchScreen |
| `fable/status-bar-theme` (#55) | F4, F5 | MainActivity, res/values(-night) |
| `fable/a11y-controls` (#56) | F7, F8 | ReaderControls, SettingsScreen |
| `fable/stats-midnight` (#57) | C10 | StatsViewModel |
| `fable/auto-theme` (#58) | H3 | Models, SettingsRepository, ReaderScreen, ReaderControls |
| `fable/tag-chips` (#59) | H2 | BookmarkCard |

All 22 branches above are implemented, unit-tested, and open as PRs #38–#59.
Each is based directly on `main`; the one known overlap is called out in its PR
(#44 and #43 touch the same volume-key block, #44 and #52 both edit
`onProgress` — small, mechanical conflicts for whichever merges second).
