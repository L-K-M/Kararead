# Kararead — a calm reader for Karakeep

> A native Android "read-it-later" reader for a self-hosted [Karakeep](https://karakeep.app)
> server, designed for the *reading* experience the way Instapaper / Pocket /
> Readwise Reader are — not for bookmark management, which the official app
> already does well.

This document is the comprehensive plan. It captures the research, the product
decisions, the technical architecture, and the execution checklist. A companion
[`awesome.md`](awesome.md) tracks the post-build review and follow-up ideas.

---

## 1. Goal & product framing

The user keeps a "read it later" list inside Karakeep. The official Karakeep app
is a capable *manager* but a mediocre *reader*. Kararead's north star is the
**calm reading moment**: open the app, see what's waiting, tap an article, and
drop into clean, beautifully typeset text with the scroll position remembered so
you can put it down and pick it up later.

### What great read-later readers get right (Instapaper / Pocket / Reader)
- **A focused queue**, not a database. The home screen is the unread queue.
- **Reader view first.** Distraction-free, typographically excellent text.
- **Typography controls** — font family, size, line height, margins, theme
  (light / sepia / dark), justification. These are the soul of the experience.
- **Resume where you left off.** Per-article scroll position, persisted.
- **Progress affordances** — a progress bar, "X min left", read/unread state.
- **Frictionless triage** — swipe to archive (= "done/read"), favourite, share.
- **Offline-friendly** — articles you opened are readable without signal.
- **Quiet, content-forward chrome** — generous whitespace, restrained color,
  hide UI while reading and reveal on tap.
- **Delight** — estimated reading time, a tasteful progress ring, a "you're all
  caught up" moment, optional text-to-speech.

### Non-goals (v1)
- Full bookmark *management* (bulk tag editing, RSS feed config, AI re-summarize
  triggers) — Kararead reads; the official app manages.
- Saving new links from a share sheet is a *nice-to-have* (implemented as a v1.1
  improvement — see awesome.md), not the core.

---

## 2. Karakeep API — the facts that shape the design

(Full reference compiled from the official OpenAPI spec, docs, and the RN mobile
client. Summarized here; the authoritative source is the spec at
`packages/open-api/karakeep-openapi-spec.json` in `karakeep-app/karakeep`.)

- **Base URL:** `{server}/api/v1`. **Auth:** `Authorization: Bearer <api-key>`
  (created in Karakeep → Settings → API Keys; opaque `ak1_..` token).
- **List bookmarks:** `GET /bookmarks?archived=&favourited=&sortOrder=&limit=&cursor=&includeContent=`.
  Cursor pagination → `{ bookmarks: [...], nextCursor: string|null }`.
- **One bookmark (reader):** `GET /bookmarks/{id}?includeContent=true` →
  the readable article HTML is `content.htmlContent`. **There is no separate
  "render" endpoint** and **no server reading-progress field** (client-side only).
- **Lists:** `GET /lists` (wrapper `{lists:[...]}`), `GET /lists/{id}/bookmarks`.
  The user's "read it later" is a *manual* list, matched by name.
- **Search:** `GET /bookmarks/search?q=` (Karakeep qualifier syntax: `is:fav`,
  `#tag`, …).
- **Mutations:** `PATCH /bookmarks/{id}` with `{archived}` (= **mark read/done**),
  `{favourited}`, `{note}`, `{title}`. `DELETE /bookmarks/{id}`. Add/remove from
  list: `PUT`/`DELETE /lists/{listId}/bookmarks/{bookmarkId}`. **PATCH returns a
  partial bookmark** (no content/tags/assets).
- **Assets (binary):** `GET /api/v1/assets/{assetId}` with the Bearer header —
  banner image (`content.imageAssetId`), screenshot, favicon, etc. Inside
  `htmlContent`, `/api/assets/..` image URLs need the auth header injected by the
  WebView (`shouldInterceptRequest`).
- **Tags:** `GET /tags`, attach/detach on a bookmark. **Highlights:** full CRUD
  at `/highlights` and `/bookmarks/{id}/highlights` (offsets + color + note).
- **Modeling gotchas:** `content` is a discriminated union on `type`
  (`link`/`text`/`asset`/`unknown`); British spelling `favourited`; most fields
  nullable; status enums include `null`; `includeContent` is opt-in.

### Mapping Karakeep concepts → reader concepts
| Reader concept | Karakeep |
|---|---|
| Unread queue | `archived = false` (optionally within the read-later list) |
| Archive / "Done reading" | `PATCH {archived:true}` |
| Favourite / star | `PATCH {favourited:true}` |
| Article body | `content.htmlContent` (`includeContent=true`) |
| Hero image | `content.imageAssetId` → asset URL, fallback `content.imageUrl` |
| Reading progress | **local only** (Room) |

---

## 3. Architecture

Single-module app, clean-ish layering, MVVM + unidirectional state.

```
ch.lkmc.kararead
├── KararreadApp / MainActivity            App + single Compose activity
├── di/                                    Hilt modules (network, db, prefs)
├── data/
│   ├── remote/  KarakeepApi (Retrofit), DTOs, AuthInterceptor, ApiProvider
│   ├── local/   Room db: ReadingProgress, CachedArticle, ReadingDay (stats), dao
│   ├── prefs/   SettingsRepository (DataStore: connection + reader prefs)
│   ├── paging/  BookmarksPagingSource (cursor → Paging 3)
│   ├── repository/ KarakeepRepository(+Impl) — the single source of truth
│   └── model/   Domain models (Bookmark, ReaderArticle, ReaderPrefs, …)
├── reader/      HTML → styled reader document (CSS themes, asset auth)
├── tts/         Text-to-speech narration (ArticleSpeaker)
├── work/        WorkManager: offline prefetch + cache cleanup
└── ui/
    ├── theme/        Material 3 + reader palettes (light/sepia/dark) + accent
    ├── navigation/   NavHost + type-safe routes
    ├── components/   BookmarkCard, EmptyState, ProgressRing, …
    ├── onboarding/   Connect to server (URL + API key, validated)
    ├── library/      The queue (Paging) + filters/sort + swipe + recents strip
    ├── lists/        Lists browser
    ├── search/       Search + browse-by-tag
    ├── stats/        Reading streak + minutes + chart
    ├── reader/       The reader (WebView + typography controls + progress + TTS)
    └── settings/     Connection, default reader prefs, about
```

### Tech choices
- **Kotlin + Jetpack Compose + Material 3** (dynamic color on Android 12+).
- **Hilt** for DI; **Retrofit + OkHttp + kotlinx.serialization** for the API
  (custom polymorphic deserializer for the `content` union).
- **Paging 3** for the infinite bookmark queue (cursor-backed `PagingSource`).
- **Room** for local reading progress + a small offline article cache.
- **DataStore (Preferences)** for connection settings and reader preferences.
- **Coil** for images (with an auth-header fetcher for Karakeep assets).
- **WebView reader**: arbitrary article HTML (code, blockquotes, images, tables)
  renders far better in a WebView with a hand-tuned reader stylesheet than via
  `AnnotatedString`. A JS bridge reports/restores scroll progress and the
  `WebViewClient` injects the Bearer header for `/api/assets` requests.
- **WorkManager** scaffold for future background prefetch/sync.

### Key design decisions
1. **Runtime-configured Retrofit.** Server URL/key are user-provided at runtime,
   so the API client is rebuilt when settings change (not a static `@Provides`
   singleton baseUrl). `ApiProvider` holds an `@Volatile` instance.
2. **Reading progress is ours.** Stored in Room keyed by bookmark id as a 0..1
   scroll fraction + timestamp; surfaced as a progress bar and "resume".
3. **`archived` is "read".** "Mark as read" / swipe-archive maps to it; the queue
   defaults to `archived=false`.
4. **Offline cache.** When an article is opened, its processed HTML + metadata is
   cached in Room so it reopens instantly and survives going offline.
5. **Security.** API key stored in a separate DataStore file excluded from
   backups; OkHttp logging redacts the `Authorization` header.

---

## 4. Screens

1. **Onboarding** — server URL + API key, "Test & connect" (calls `/users/me`),
   helpful errors. Paste-friendly.
2. **Library (the queue)** — Paging list of unread bookmarks as calm cards
   (title, site + favicon, excerpt, hero thumb, reading-time, progress). Top bar:
   source switch (All unread / a chosen list / Favourites / Archive), sort.
   Swipe → archive (read); swipe → favourite. Pull-to-refresh. Empty state =
   "You're all caught up ✨".
3. **Reader** — the heart. WebView with reader CSS; immersive (chrome hides on
   scroll, taps reveal). Typography sheet: theme (light/sepia/dark/black-OLED),
   font family (eight typefaces), size, line height, margins, justify — with a
   live preview. Thin top progress bar; resume scroll. Volume keys turn pages;
   text selection can create highlights. Actions: favourite, archive (done),
   open original, share, mark unread, listen (text-to-speech). Reading-time + %
   in the bar.
4. **Lists** — browse Karakeep lists, open one as a queue.
5. **Search** — query box → results list (reuses card + reader); the empty state
   offers a browse-by-tag chip cloud.
6. **Stats** — reading streak, minutes read today/this week, and a 14-day chart.
7. **Settings** — connection (with sign-out), default reader prefs, theme +
   manual accent colour, "read-later list" picker, about/version, licenses.

---

## 5. CI/CD

GitHub Actions:
- **`ci.yml`** on push/PR: set up JDK 17 + Android SDK, Gradle cache, run
  `./gradlew lint testDebugUnitTest assembleDebug`, upload the debug APK + lint
  & test reports as artifacts.
- **`release.yml`** on tag `v*`: build the (debug-signed) release APK, generate
  release notes, create a GitHub Release with the APK attached.
- Dependabot for Gradle + Actions; a debug keystore is checked in for
  reproducible CI signing.

---

## 6. Quality

- Unit tests (JVM) for: HTML reader-document building, `content` polymorphic
  deserialization, settings repository logic, reading-time estimation, the
  bookmark domain mapping, and the paging source. `mockk` + coroutines-test +
  turbine.
- `./gradlew lint` clean; warnings-as-signal.
- README with screenshots-of-intent, setup, and build instructions.

---

## 7. Execution checklist

- [x] Research Karakeep API, Instapaper/read-later UX
- [x] Gradle project, version catalog, wrapper, CI-ready signing
- [x] Theme, navigation, app scaffold
- [x] Data layer: DTOs + polymorphic content, Retrofit API, repository, paging
- [x] Local: Room (progress + cache + reading stats), DataStore settings
- [x] DI wiring
- [x] Onboarding screen
- [x] Library queue (paging, swipe actions, filters)
- [x] Reader (WebView, CSS themes, typography controls, progress persistence)
- [x] Lists, Search, Settings, Stats
- [x] Unit tests
- [x] CI/CD + Dependabot + README
- [x] Build green (lint + tests + assembleDebug)
- [x] Review → awesome.md → execute worthwhile items
- [x] Follow-ups shipped: highlights, text-to-speech (voice picker), volume-key
      paging, tap-to-reveal chrome, reading streaks + Stats tab, tag browsing in
      Search, "recently opened" strip, more typefaces + live preview, manual
      accent colour
