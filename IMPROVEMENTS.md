# Kararead — post-build review & improvement roadmap

After completing the first full build (a buildable, lint-clean, tested app), I
reviewed the result for correctness bugs, missing requirements, rough edges, and
opportunities for delight. This document records that review and tracks what was
acted on.

Legend: ✅ done in this pass · 🔜 planned/future · 💡 idea

---

## A. Correctness & bugs

1. **Cold-start connection race.** ✅
   `ApiProvider` was configured asynchronously from the settings flow in
   `Application.onCreate`. The Library's paging load could fire before the client
   was configured, throwing `NotConnectedException` and showing a spurious error
   on first launch. Fixed by configuring the client *synchronously* from the
   persisted settings in `RootViewModel` before the UI is marked ready.

2. **Edge-to-edge insets.** ✅
   With `enableEdgeToEdge()`, the bottom reading-progress line and list content
   could sit under the system navigation bar. Added navigation-bar insets where
   it matters (reader progress line, list bottoms).

3. **Swipe affordance in the Archive tab.** ✅
   In the Archive tab, swiping "archive" actually un-archives; the background
   still said "Archive". The action is now labelled correctly per context.

4. **Reading-progress restore accuracy.** 🔜
   Restore is by scroll *fraction*, computed once on `onReady`. Late-loading
   images can shift layout, so the restored position drifts slightly. A more
   robust approach anchors to the nearest block element. Acceptable for v1.

5. **`PATCH` returns a partial bookmark.** ✅ (by design)
   We never deserialize the PATCH response into a full bookmark expecting
   content/tags; mutations are fire-and-forget with optimistic local state.

## B. Missing reader-app features

6. **Save links to Karakeep via the Android share sheet.** ✅
   A reader that can't *capture* is half a reader. Added a share-target activity:
   share a URL from any app → Kararead saves it to Karakeep (optionally to your
   read-later list) with a confirmation toast.

7. **"Time left" in the reader.** ✅
   The reader now shows estimated minutes remaining based on progress and the
   article's reading time — a small but beloved Instapaper touch.

8. **Highlights.** ✅
   Select text in the reader to create a highlight (synced to Karakeep via the
   API); tap a highlight to delete it. Implemented with a WebView text-selection
   action plus a JS offset-mapping bridge.

9. **Bulk actions / "mark all read".** 💡 Future.

10. **Text-to-speech ("listen to article").** ✅
    A "Listen" mini-player narrates the parsed text with Android `TextToSpeech`
    — play/pause, sentence skip, and a voice picker.

## C. Delight & polish

11. **Haptic feedback on swipe actions.** ✅
    A light tick when an archive/favourite swipe commits.

12. **Publish date on cards.** ✅
    When known, the article's publish date joins the card meta line.

13. **"Surprise me".** ✅
    A shuffle action on the Library that opens a random article from the current
    queue — for when you can't decide what to read.

14. **Caught-up celebration.** ✅ (already present)
    The empty inbox shows an encouraging "You're all caught up ✨".

15. **Pleasant transitions.** ✅ (already present)
    Slide transitions into the reader/list; crossfade on images.

## D. Engineering / project health

16. **Synchronous SDK setup for Claude Code web sessions.** ✅
    Added a `SessionStart` hook + setup script so future cloud sessions have the
    Android SDK ready to build and test.

17. **Trim compiler warnings.** ✅
    Removed unused imports/vars flagged by the Kotlin compiler & lint.

18. **More tests.** ✅
    Added tests for the new "time-left" calculation and share-intent URL
    extraction.

19. **Baseline profile / macrobenchmark.** 💡 Future performance work.

20. **Instrumented UI tests.** 💡 Need an emulator in CI (deferred; unit tests
    cover logic today).
