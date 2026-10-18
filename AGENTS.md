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

<!-- shared-rules:start -->

## Working practices

- Follow explicit task instructions over the default workflow below.
- Writing the code is not finishing the task. A task is finished when
  its changes are merged to main through a PR that passed CI and review,
  or when the user explicitly accepts a different end state.
- Start every task on current code. Fetch first, then cut the task
  branch from origin/main — never from a stale local branch or an old
  checkout. To continue existing work, rebase or merge the latest
  origin/main into it before editing. Never overwrite existing work to
  update.
- Resolve ambiguity before making consequential changes. State low-risk
  assumptions; ask when scope, safety, or expected behavior is unclear.
- Keep changes focused. Do not modify unrelated code, formatting, or comments.
- Prefer surgical edits over whole-file rewrites when the result is equivalent.
- Stage only intended files. Inspect the diff before committing.

## Communication

- Be concise, factual, and direct. Preserve necessary context and uncertainty.
- Avoid praise, motivational filler, emojis, and em dashes in new prose.
- Address the reader directly in user-facing copy.
- Report what was verified and what remains unverified. Never imply that an
  unavailable check passed.

## Code design

- Prefer early returns and shallow nesting. Separate logical blocks with
  blank lines.
- Use descriptive constants or enums for meaningful or repeated values.
  Use existing standard definitions for protocol/specification constants.
  Keep obvious, one-off values inline.
- Use enums for behavioral modes that would otherwise require ambiguous
  boolean arguments.
- Default members to private. Widen visibility only for required consumers,
  and review the change as an API design decision.
- Follow the repository's declared dependency boundaries. UI and controllers
  must use application services rather than directly accessing databases,
  subprocesses, sockets, or other low-level mechanisms.
- Encapsulate low-level mechanics behind domain-oriented interfaces.
- Reuse genuinely shared logic. Avoid speculative abstractions and layers
  that only forward calls.
- Prefer pure functions for business rules and immutable data where practical.
  Isolate side effects; document non-obvious state ownership or synchronization.
- Explain non-obvious intent, constraints, and tradeoffs in comments.
  Do not narrate obvious code. Add examples or diagrams when they clarify it.

## Validation and errors

- Validate untrusted input at entry points. Where practical, represent valid
  states in types and enforce persistent invariants in database schemas.
- Represent absence and failure explicitly.
- Use assertions for internal programming invariants, not external-input
  validation or required runtime error handling.
- Prefer explicit, actionable errors over silent failure or undocumented
  fallback. Document intentional recovery behavior.
- Never report a skipped or failed operation as successful.

## Bug fixes

1. Identify the root cause and define an observable success criterion.
2. Add a regression test and observe the relevant failure before fixing it.
3. Implement the fix and observe the test passing.
4. Check surrounding behavior for regressions and architectural consistency.

If an automated regression test is impractical, document the reproduction
and verification procedure. State any inability to reproduce the failure.

## Verification

- Run relevant tests and lint after changes.
- Choose coverage by affected behavior and risk, not patch size.
- Use integration or end-to-end tests for critical workflows and boundaries;
  test isolated business rules at the lowest effective level.
- Run broader suites for cross-cutting or high-risk changes, and the full
  required release checks before releasing.
- Validate the requested command, options, platform, and configuration.
  Unrelated green CI is not proof that the reported problem is fixed.
- Recheck after the final edit. Distinguish local checks from CI results.

## Commit messages

- Use a capitalized, imperative subject without a final period.
- Target 50 characters; never exceed 72.
- Separate the subject and body with one blank line.
- Wrap body text at 72 characters.
- Explain what changed and why. Leave implementation mechanics to the code.

## Implementation and review

Unless explicitly instructed otherwise:

1. Work on a focused branch cut from the latest origin/main and open a PR
   against main before reporting the task as done.
2. Inspect CI results and completed review feedback for the latest commit.
   A successful reviewer job does not mean the review found no problems.
3. Address important findings or explain why they do not apply. Handle minor
   findings according to the stopping rules below.
4. Evaluate each fix in the surrounding project, add regression coverage,
   and rerun affected checks before pushing.
5. Repeat until a stopping criterion is met.
6. Merge without asking again once the stopping criterion is met, required
   checks pass on the latest commit, and no unresolved blockers or required
   human review requests remain.

### Reviewer context limits

The automated PR reviewer does not see the user's original prompt or
conversation. It may suggest changes that go against or beyond what the
user asked for. Do not implement such suggestions. Note each conflict and
report it to the user at the end of the thread.

### Automated review stopping rules

Judge findings by verified impact, not the reviewer's severity label.
Important findings concern correctness, security, data loss, broken builds,
or materially degraded behavior/performance.

Track completed review rounds and consecutive rounds without important
findings. Reruns of the same revision and integration failures do not count.

- No applicable actionable feedback: finish immediately.
- First minor-only round: optionally fix worthwhile, low-risk findings.
  Do not manufacture another push merely to obtain another review.
- Two consecutive rounds without important findings: stop responding to
  automated nitpicks, even if actionable minor suggestions remain.
  Defer worthwhile leftovers rather than continuing the cycle.
- A confirmed important finding resets the minor-only streak. Address it
  and verify the fix before continuing.

After ten completed rounds, enter stabilization:

- Stop optional cleanup, refactoring, and nitpick fixes.
- One completed review without confirmed important findings is sufficient
  to finish, even if minor suggestions remain.
- Continue only for confirmed important defects. If resolving them stalls,
  report the blockers rather than continuing indefinitely.

These limits end optional automated-feedback work. They do not waive
confirmed blockers, unresolved human review requests, or required checks.

### Reviewer integration failures

After two consecutive reviewer-integration failures, stop and report the
review gap. Do not treat failures as approval. An explicit user instruction
may waive review; report that waiver rather than claiming review passed.

## Ending a task

- A task ends with its changes merged to main — not with code written,
  and not with a PR merely opened. An open PR is work in progress:
  monitor CI on the latest commit, address review findings per the
  stopping rules, and merge once the criteria are met.
- Never finish with uncommitted changes or unpushed commits in the
  worktree. Commit, push, and open or update the PR first.
- If a step is impossible (missing push access, CI failure, reviewer
  outage), report the exact blocker instead. Never present unreviewed or
  unmerged work as finished.
- Before finishing, confirm: the requested behavior is implemented
  without unrelated changes; relevant checks pass on the latest code;
  important review findings are addressed or rejected with reasons;
  deferred suggestions, remaining risks, and validation gaps are
  disclosed.
- The final response states where the work stands: branch, PR, CI
  status, review rounds completed, and whether it is merged.

<!-- shared-rules:end -->

