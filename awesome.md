# awesome.md — a fresh review of Kararead

---

## A. Correctness bugs

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

### B5. ✨ Next-article / auto-advance
Finishing an article dumps you back to the list. A reader app wants a gentle
"→ Next in queue" (and an option to archive-on-finish). Great delight, medium
effort (the reader needs to know its queue). Deferred (note).

### B8. ✨ Pull-to-refresh on the Lists screen
`ListsScreen` only refreshes via the error-state "Retry" button; there's no
pull-to-refresh like the bookmark lists have. Small. Deferred (note).

---

## C. Engineering / project health

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
---

## D. Delight & quirky ideas

### D1. 💡 "Shake to surprise me" / dice everywhere
The dice is great — extend it: a tiny shake gesture to open a random unread
article, or a dice on empty states ("nothing to read? here's an old favourite").

### D5. 💡 Smart resume nudge
When you reopen something you're 60% through, a fading "Resume — 4 min left"
chip instead of a silent jump. Reassures the reader that the scroll jump was
intentional.

### D8. 💡 True paged reading mode (see B3)
CSS-column or measured-offset paging with a page counter ("3 / 18") and a
page-curl-free, instant flip. The premium reader feel. Big, but the soul of a
"reader".

### D9. 💡 "Send to Kindle" / export highlights to Markdown
Once highlights exist (B7), one-tap export of an article's highlights as Markdown
(or to a notes app) makes Kararead a research tool too.