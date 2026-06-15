# awesome.md — a fresh review of Kararead

A second-pass review of the whole codebase, by a new set of eyes. The first
review lives in [`IMPROVEMENTS.md`](IMPROVEMENTS.md); this document goes deeper
into correctness bugs hiding in the data/reader layers, calls out a few promised
features that aren't actually wired up, and collects a pile of ideas — some
practical, some just delightful.

The code is genuinely good: clean MVVM, a tasteful reader, sensible offline
story, real tests. Most of what follows is about the gap between *"compiles and
looks right"* and *"behaves right on a real device with real Karakeep data"*.

**Legend:** 🐞 bug · 🔧 issue/cleanup · ✨ missing feature · 💡 idea/delight
· status: ⬜ not started · 🟢 implementing in a branch · ✅ shipped · ⏭️ deferred (noted only)

> **Update:** the branches below have since merged, and a number of the
> deferred items have shipped too — see the ✅ markers. Notably: highlights
> (B7), text-to-speech with voice selection (B6), reading streaks & a Stats
> tab (D2), tag browsing (B4, folded into Search as a browse-by-tag chip
> cloud), plus a "recently opened" strip, more typefaces, a manual accent
> colour, and a live preview in the reading-settings sheet.

---

## A. Correctness bugs

### A1. 🐞✅ The reader shows the wrong favourite / read state for any cached article
`KarakeepRepository.getArticle()` is **cache-first**: if a `CachedArticleEntity`
exists it returns immediately without hitting the network. But
`CachedArticleEntity.toReaderArticle()` (in `Mappers.kt`) **hard-codes**
`archived = false, favourited = false, tags = emptyList()`. The cache schema
doesn't even store those fields.

`ReaderViewModel.load()` then seeds its UI state from that bookmark:
```kotlin
favourited = article.bookmark.favourited,  // always false for cached articles
archived = article.bookmark.archived,      // always false for cached articles
```
So the moment you reopen *any* article you've read before, the star and the
read/unread toggle in the reader's top bar are **wrong** — they reset to
empty/unread even if the item is favourited and archived on the server. Tapping
them then toggles to the opposite of reality.

**Fix:** overlay authoritative `archived`/`favourited` onto the (possibly cached)
article by fetching the lightweight bookmark (`includeContent=false`), and fall
back to the cached values when offline. Implemented on a branch.

### A2. 🐞✅ Inline server-hosted images can be stripped before they ever load
`ReaderHtmlBuilder.sanitize()` calls `Jsoup.clean(html, safelist)` **without a
base URI**. Jsoup resolves protocol-restricted attributes (`img[src]`) against
the base URI; with an empty base, a *relative* Karakeep asset path like
`/api/assets/abc` resolves to nothing and the `<img>` is dropped during
sanitization — so `AssetLoader` (which injects the bearer token) never even gets
a chance to serve it. The whole auth-injection machinery only helps if the URL
survives sanitization.

**Fix:** sanitize with the server origin as the base URI
(`Jsoup.clean(html, baseUri, safelist)`) so relative asset URLs are preserved and
absolutized, matching the WebView's `baseUrl`. Implemented on a branch.

### A3. 🐞✅ `hiddenIds` is global across tabs, so archiving leaks between queues
`LibraryViewModel` keeps one `hiddenIds: Set<String>` shared by **all** tabs
(Inbox / Favourites / Archive / Read-later). Archive an item in the Inbox, switch
to the Archive tab — the very item you archived is now *hidden* there, because
the filter `data.filter { it.id !in hidden }` applies the same set everywhere.
`clearHidden()` exists but is never called. Optimistic hiding should be scoped to
the source it happened in (and cleared on refresh). Implemented on a branch.

### A4. 🐞✅ "Surprise me" only rolls the dice over *loaded* pages
In `LibraryScreen`, the dice action does
`(0 until items.itemCount).random()` then `items[index]`. `itemCount` is only the
number of **currently-materialized** paging items, so "random" is biased toward
the top of the queue, and a freshly-picked index can land on a not-yet-loaded
placeholder (`items[index]` returns `null`) and silently do nothing. Minor, but
it undercuts the feature's whole charm. (Folded into the reader/library polish
branch as a small guard + retry.)

### A5. 🐞 Reading time / progress almost never appears on library cards
`BookmarkDto.toDomain()` derives `readingTimeMinutes` from
`htmlContent`/`text`, but list/search endpoints are called with
`includeContent=false`, so for the common "link" bookmark the body is null and
**reading time is null on every card** until you've opened (and cached) it. The
README and cards advertise "reading-time" prominently, but in practice the label
and the progress ring rarely show. Options: (a) accept it; (b) store a
word-count/reading-time hint when we cache; (c) show reading time from cache when
available. Noted; partially addressed by A1's cache work.

### A6. 🔧 Debounced progress save is lost when you leave the reader quickly
`ReaderViewModel.onProgress()` debounces the Room write by 400 ms inside
`viewModelScope`. Backing out of the reader cancels the scope and drops the
pending save, so the last scroll movement before exit may not persist — exactly
when "resume where you left off" matters most. A flush on `onCleared()` (or a
`NonCancellable` final write) would close the gap. Deferred (small, low-risk).

### A7. 🔧 Unknown `content.type` can throw and fail a whole page
`ignoreUnknownKeys` doesn't cover an unknown *discriminator value*. If Karakeep
ever returns a `content.type` outside `link|text|asset|unknown`, kotlinx
serialization throws and the entire paging load fails with an error state rather
than degrading gracefully. A custom serializer defaulting to `Unknown` would make
the client forward-compatible. Deferred.

---

## B. Missing features (the ones you asked about)

### B1. ✨✅ Volume-key page navigation
A beloved e-reader affordance that's entirely absent. Hardware volume up/down
should page the article up/down (a screen-height minus a little overlap), without
changing the media volume. Implemented on a branch (key handling in the reader +
a `krPageBy` JS helper in the reader document).

### B2. ✨✅ Tap-to-toggle reading chrome
`PLAN.md` promises "immersive (chrome hides on scroll, **taps reveal**)", but the
only way to bring the top bar back is to scroll up — taps do nothing because the
WebView swallows them. A tap in the central reading column should toggle the
chrome. Implemented alongside B1 (a tap signal from the reader document's JS).

### B3. ✨ "Pagination" — paged reading mode
"Pagination" can mean two things and both are worth a mention:
- **List pagination** already exists and is solid (Paging 3, cursor-backed). ✅
- **Paged *reading*** (tap/swipe to turn pages instead of continuous scroll, à la
  Kindle/iBooks) does **not** exist. It's a big, delightful feature (CSS columns
  or measured page offsets + a page indicator). Deferred as an idea — see D8 —
  but volume keys (B1) get you most of the "turn the page" feel cheaply.

### B4. ✨✅ Tag browsing
`BookmarkSource.TagSource`, `KarakeepApi.getTagBookmarks`, `getTags()`, the `Tag`
model and a `toDomain()` mapper all existed — but nothing in the UI let you browse
by tag. **Done:** the empty Search view now shows a browse-by-tag chip cloud (the
most-used tags); tapping a chip fills the search box with `#tag` and runs the
search. (A short-lived standalone Tags tab was folded into Search.)

### B5. ✨ Next-article / auto-advance
Finishing an article dumps you back to the list. A reader app wants a gentle
"→ Next in queue" (and an option to archive-on-finish). Great delight, medium
effort (the reader needs to know its queue). Deferred (note).

### B6. ✨✅ Text-to-speech ("listen to this")
We already extract clean plain text (`textContent`). **Done:** a "Listen"
mini-player narrates the article with Android `TextToSpeech` (`tts/ArticleSpeaker`)
— play/pause, sentence skip, and a voice picker; the chosen voice is persisted.

### B7. ✨✅ Highlights
The API + DTOs + `Highlight` domain model + repository read path existed. **Done:**
selecting text in the reader adds a "Highlight" action that creates a highlight
(synced to Karakeep) via a JS selection/offset-mapping bridge; tap a highlight to
delete it.

### B8. ✨ Pull-to-refresh on the Lists screen
`ListsScreen` only refreshes via the error-state "Retry" button; there's no
pull-to-refresh like the bookmark lists have. Small. Deferred (note).

---

## C. Engineering / project health

### C1. 🔧✅ The offline cache grows forever
`CachedArticleDao.deleteOlderThan()` exists but is **never called**, and there's
a `WorkManager` dependency + Hilt worker factory wired up with **no worker**.
Caches grow unbounded. A tiny periodic (or on-launch) trim of articles older than
N days closes the loop and finally uses the WorkManager scaffold for its stated
purpose. Implementing on a branch.

### C2. 🔧✅ Unused `POST_NOTIFICATIONS` permission
Declared in the manifest but nothing ever posts a notification. Permissions you
don't use are a (small) trust and Play-listing cost. Remove it (or earn it with a
real notification later). Folded into the cache/cleanup branch.

### C3. 🔧 API key is stored in plaintext DataStore
`SettingsRepository` keeps the key in a "secrets" Preferences DataStore that's
excluded from backups — good — but it's still **plaintext on disk**. For a token
that grants full access to someone's bookmark server, `EncryptedSharedPreferences`
/ Tink (or the Jetpack security-crypto lib) would be a meaningful hardening.
Deferred (worth doing, but wants device testing).

### C4. 🔧 `connection` flow only re-emits on the settings store, not the secrets store
`SettingsRepository.combineStores()` maps `settingsStore.data` and reads
`secretsStore.data.first()` *inside* the map. If the API key ever changes without
the server URL changing, the flow won't re-emit. It happens to work because
`saveConnection` writes both, but it's fragile and does an extra `first()` read on
every settings emission. A `combine(settingsStore.data, secretsStore.data)` is
cleaner. Deferred (low risk, low urgency).

### C5. 🔧 `getRefreshKey` always returns null
Fine for forward-only cursor pagination (refresh restarts at the top), but worth a
one-line comment so it doesn't read like an oversight. Trivial.

### C6. 🔧 No instrumented/UI tests; logic tests are good but thin on the data layer
Unit coverage is nice on utils/mappers/serialization, but the repository's
cache-first logic (where A1 hid) and the paging source have none. A couple of
fakes would have caught A1. Deferred.

---

## D. Delight & quirky ideas

### D1. 💡 "Shake to surprise me" / dice everywhere
The dice is great — extend it: a tiny shake gesture to open a random unread
article, or a dice on empty states ("nothing to read? here's an old favourite").

### D2. 💡✅ Reading streak & "minutes read today"
We persist reading time per day (`reading_day`). **Done:** a Stats tab shows the
current streak, minutes read today/this week, and a 14-day chart; the streak also
appears on the empty-inbox celebration. Gentle, non-gamified.

### D3. 💡 "Tonight's queue" / estimated time to clear the inbox
Sum reading-time across the unread queue: "≈ 38 min to reach inbox zero." Pairs
beautifully with the "all caught up" moment.

### D4. 💡 Auto reading theme by time of day / ambient light
Optionally switch the reader to Dark/Black after sunset (or via the light sensor)
so late-night reading is easy on the eyes — with a one-tap override.

### D5. 💡 Smart resume nudge
When you reopen something you're 60% through, a fading "Resume — 4 min left"
chip instead of a silent jump. Reassures the reader that the scroll jump was
intentional.

### D6. 💡 Swipe-down-to-dismiss the reader
A natural gesture to "put the article down" — swipe the whole reader down to pop
back to the queue, mirroring how share sheets feel.

### D7. 💡 Per-article typography memory
Some articles read better in mono (code-heavy) or larger (dense). Optionally
remember per-article overrides on top of the global defaults.

### D8. 💡 True paged reading mode (see B3)
CSS-column or measured-offset paging with a page counter ("3 / 18") and a
page-curl-free, instant flip. The premium reader feel. Big, but the soul of a
"reader".

### D9. 💡 "Send to Kindle" / export highlights to Markdown
Once highlights exist (B7), one-tap export of an article's highlights as Markdown
(or to a notes app) makes Kararead a research tool too.

### D10. 💡 Offline-first prefetch of the top of the queue
Quietly cache the next few unread articles on Wi-Fi (the WorkManager scaffold is
right there) so the queue is readable on the subway with zero spinners.

### D11. 💡 A "focus" reading timer / Pomodoro-for-reading
Optional, opt-in: a calm 20-minute reading session with a soft end chime. Fits
the "calm moment" north star.

---

## What was implemented

The high-confidence, high-value items were each given their own branch (reviewed
and merged independently), and all have since shipped:

1. **A1** — correct favourite/read state for cached articles *(data/reader)*
2. **A3** — per-source optimistic hiding in the library *(library VM)*
3. **A2** — preserve server-hosted inline images in the reader sanitizer *(reader)*
4. **B1 + B2** — volume-key paging + tap-to-toggle chrome *(reader)*
5. **C1 + C2** — offline-cache trimming via WorkManager + drop the unused
   notifications permission *(data/app/manifest)*
6. **B4** — tag browsing *(folded into Search as a browse-by-tag chip cloud)*

Subsequent passes also shipped highlights (B7), text-to-speech with voice
selection (B6), reading streaks and a Stats tab (D2), plus polish not in this
list: a "recently opened" strip on the library, more typefaces with a live
preview, and a manual accent colour. The remaining items above stand as
deliberate, scoped follow-ups.
