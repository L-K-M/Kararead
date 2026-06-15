# awesome.md — review backlog (cleared)

A running review of Kararead's correctness, missing features, and delight ideas.
All previously-open items have now shipped — recorded below for history. New
findings can be added back under the section headings as they come up.

**Legend:** 🐞 bug · 🔧 issue/cleanup · ✨ feature · 💡 idea/delight · ✅ shipped

---

## A. Correctness
- **A5 ✅** Cards now fall back to a cached reading-time hint, so "N min" shows
  for opened articles even though list/search responses omit content.
- **A6 ✅** The debounced reading-progress write is flushed on
  `ReaderViewModel.onCleared()` via an injected `@ApplicationScope`, so the last
  scroll before backing out isn't lost.
- **A7 ✅** `ContentDto` uses a `JsonContentPolymorphicSerializer` that decodes an
  unrecognized content `type` as `Unknown` instead of throwing a whole page.
- *(A1–A3, A8 shipped earlier: cached read/favourite state, inline-image base
  URI, per-source optimistic hiding, block-anchor progress restore.)*

## B. Features
- **B5 ✅** Next-article / auto-advance: a "Done · Next" button at the end of an
  article (marks read + opens the next unread), plus a "Next in queue →" overflow
  action; advancing replaces the reader on the back stack.
- **B8 ✅** Pull-to-refresh on the Lists screen.
- *(B1/B2/B4/B6/B7 shipped earlier: volume-key paging, tap-to-reveal chrome, tag
  browsing in Search, text-to-speech with voice selection, highlights.)*

## C. Engineering / project health
- **C4 ✅** `connection` now `combine`s both DataStores, so an API-key change
  re-emits even if the URL is unchanged.
- **C5 ✅** Documented why `getRefreshKey` returns null (forward-only cursor).

## D. Delight
- **D1 ✅** A "🎲 Surprise me" action on empty Inbox/read-later states opens a
  random favourite (or something from the archive).
- **D5 ✅** A brief "Resumed · N min left" nudge when reopening mid-article.
- **D8 ✅** Optional paged reading mode (full-screen CSS columns, swipe/volume to
  turn, page counter) behind a settings toggle; scroll mode remains default.
- **D9 ✅** "Export highlights" shares an article's highlights as Markdown.
- *(D2 shipped earlier: reading streak + Stats tab.)*

## Known limitations / future polish
- Paged mode (D8) is a first cut: very tall images, code blocks or tables are
  height-capped and may still clip across a page break; best on phone-width
  screens. Worth on-device iteration.
- A3-era reading-time still can't appear on *never-opened* link cards (the list
  API omits content); only cached articles get the hint.
</content>
