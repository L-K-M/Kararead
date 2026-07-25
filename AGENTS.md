# Kararead agent and contributor notes

Kararead is an Instapaper-style reader for a self-hosted [Karakeep](https://karakeep.app)
library — an independent, unofficial client. Product rationale and roadmap live in
[PLAN.md](PLAN.md); user-facing docs in [README.md](README.md).

## Toolchain

- **JDK 17+** (CI uses Temurin 17). `sourceCompatibility`/`targetCompatibility` and the
  Kotlin `jvmTarget` are all 17.
- **AGP 8.7.3 / Kotlin 2.0.21 / KSP 2.0.21-1.0.28**, all pinned in
  `gradle/libs.versions.toml`. Gradle comes from the committed wrapper.
- **compileSdk / targetSdk 35, minSdk 26.**
- Android SDK via `local.properties` (copy `local.properties.example`) or `ANDROID_HOME`.
  In a Claude Code web session the `SessionStart` hook in `.claude/settings.json` runs
  `.claude/setup-android.sh`, which installs the command-line tools and writes
  `local.properties` — idempotent and safe to re-run.

> **Do not bump the toolchain piecemeal.** [DEPENDABOT.md](DEPENDABOT.md) documents a
> five-PR deadlock (AGP 9, Gradle 9, Hilt 2.59, Kotlin 2.4, the androidx group) in which
> each bump requires another and Hilt cannot read Kotlin 2.4 metadata. Read that file
> before touching any version in `libs.versions.toml`, and land the set together or not
> at all.

## Build / test / lint

```bash
./gradlew testDebugUnitTest    # unit tests (12 files: data/remote, data/repository, reader, tts, util)
./gradlew lintDebug            # Android lint — a CI gate, keep it clean
./gradlew assembleDebug        # debug APK
scripts/install.sh             # build + adb install + launch the debug build on a device
scripts/install.sh --release   # …the (debug-signed) release build instead
```

There are no instrumentation tests (`app/src/androidTest` does not exist), so
`testDebugUnitTest` is the whole automated suite. Keep decision logic in plain classes
that a JVM test can reach rather than inside composables or ViewModels that need a device.

`scripts/install.sh --help` prints its own header block; it is the friendliest entry point
when working from IntelliJ IDEA, which has no Android Studio Run button.

## Architecture

Single-module (`:app`) Kotlin + Jetpack Compose (Material 3), MVVM with Hilt injection.

- **Networking** — Retrofit + OkHttp + `kotlinx.serialization` against the user's own
  Karakeep server. There is no Kararead backend and no analytics.
- **Persistence** — Room (KSP-generated) for cached content, DataStore Preferences for
  settings, Paging 3 between them and the UI.
- **Content** — Jsoup extracts and cleans article HTML for the reader view.
- **Background work** — WorkManager, wired to Hilt through `hilt-work`.
- **UI** — one package per screen under `ui/`: `library`, `reader`, `search`, `lists`,
  `highlights`, `stats`, `settings`, `onboarding`, plus `components`, `navigation`, `theme`.

Three Compose/coroutine opt-ins are enabled globally in `app/build.gradle.kts`
(`ExperimentalMaterial3Api`, `ExperimentalFoundationApi`, `ExperimentalCoroutinesApi`) —
prefer those over scattering per-file `@OptIn`.

## Signing — read this before cutting a release

**Release builds are signed with the committed debug keystore** (`app/debug.keystore`,
the standard `android`/`androiddebugkey` credentials). `app/build.gradle.kts` points the
`release` build type at `signingConfigs.getByName("debug")` deliberately, so CI can produce
an installable APK with no secrets — but it means published releases are *not* signed with
a private key, and the checked-in keystore is public.

Two consequences:

- Anyone can produce an APK that upgrades an installed Kararead. That is acceptable for a
  self-hosted client distributed outside any store, but it is a real property of the
  current setup, not an oversight to be discovered later.
- If a real signing key is ever introduced, the first release under it **cannot** upgrade
  installs of the debug-signed builds — Android identifies an app by its signing
  certificate. Users would have to uninstall first. Plan that as a deliberate break.

The debug build additionally carries `applicationIdSuffix = ".debug"` and
`versionNameSuffix = "-debug"`, so debug and release installs coexist on one device.

## CI/CD

- [`ci.yml`](.github/workflows/ci.yml) — pushes to any branch, PRs, manual dispatch:
  `lintDebug`, `testDebugUnitTest`, `assembleDebug`, then uploads the debug APK and the
  lint/test reports (`if: always()`, so a failing run still yields its reports).
- [`release.yml`](.github/workflows/release.yml) — tag push `v*` (or manual dispatch):
  `assembleRelease`, copy to `dist/kararead-<tag>.apk`, publish a GitHub Release with
  generated notes.
- [`zai-code-review.yml`](.github/workflows/zai-code-review.yml) — GLM 5.2 reviews every
  non-draft PR **from this repository** when `ZAI_API_KEY` is set. Fork PRs are excluded
  by design: `pull_request_target` hands repository secrets and a write-capable token to a
  workflow an outside contributor triggered. The action is pinned to an immutable commit;
  verify before bumping with
  `git ls-remote https://github.com/L-K-M/zai-code-review refs/tags/v0.0.9`.
- Dependabot: weekly `gradle` (grouped `androidx.*` and `kotlin`/`ksp`) + `github-actions`.

## Releasing

```bash
scripts/release.sh 0.4.0 --push
```

A ~29-line stub over the shared `lkm-release` engine
(<https://github.com/L-K-M/release-tool>, kind `gradle-android`): bumps `versionName`,
auto-increments `versionCode`, rewrites the README `<!-- version -->` marker, commits, tags
`v0.4.0`, and pushes branch + tag; the tag triggers `release.yml`.

CI builds from the **committed** `app/build.gradle.kts` at the tagged commit, so the
committed `versionName`/`versionCode` decide what the APK reports and the tag only names
the Release and the asset. **Never hand-edit `versionCode`, and never create a `v*` tag by
hand** — Android refuses to install a release without a new code, and the two drifting
apart ships a release named for a version it doesn't contain.

## Conventions

- Room schema changes need a migration; don't rely on destructive fallback.
- Release builds minify with R8 (`isMinifyEnabled` + `isShrinkResources`), so new
  reflection or serialization entry points may need `app/proguard-rules.pro` entries.
  A feature that works in debug and breaks in release is almost always this.
- Kararead is an **unofficial** Karakeep client. Don't imply affiliation in UI strings,
  the README, or release notes.
- Never commit `local.properties`, real server URLs, API tokens, or a user's library
  contents — including in test fixtures and issue reports.
- Helper scripts follow the family house style: a long `#` header that doubles as
  `--help` via the self-terminating awk renderer, `set -euo pipefail`, and
  `==>` / `--` / `!!` log prefixes.
