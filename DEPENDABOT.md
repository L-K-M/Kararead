# Dependabot toolchain PRs — status note

_Last checked: 2026-08-05 — **resolved**, see below._

Five Dependabot PRs (#6 kotlin group, #7 hilt, #8 gradle-wrapper, #9 AGP,
#75 androidx group — #75 is the recreation of the original #5) formed **one
entangled toolchain upgrade** that could not land piecemeal.

## The deadlock (June 2026), for the record

- **Hilt 2.59.2 (#7) required AGP ≥ 9.0** — it won't apply on AGP 8.x.
- **AGP 9.x (#9)** has *built-in* Kotlin and forces **Kotlin 2.4**; **Gradle
  9.5.1 (#8)** is required by AGP 9 (and incompatible with AGP 8.7.3).
- **Hilt 2.59.2 could not read Kotlin 2.4 metadata** — its bundled
  `kotlin-metadata-jvm` maxed out at metadata 2.3.0, so its annotation
  processor failed:
  `Provided Metadata instance has version 2.4.0, while maximum supported version is 2.3.0`.
- **androidx `core` 1.19.0 (#75)** requires **compileSdk 37**, which needs AGP 9.

Every PR depended on the AGP-9 jump, and the AGP-9 jump (→ Kotlin 2.4) broke
Hilt. The stated unblock condition: a Hilt release that reads Kotlin 2.4
metadata.

## Resolution (August 2026)

**The unblock condition was met: Hilt 2.60.1** bundles `kotlin-metadata-jvm`
2.3.21, which reads metadata one minor version ahead — i.e. Kotlin 2.4
(verified from the published POMs, then by an actual build: the June
metadata error does not reproduce).

The combined upgrade landed as one PR (branch
`claude/kpt-goo-android-app-4ex35l`), following the migration steps
previously recorded here:

1. AGP 9.2.1 + Gradle 9.5.1 + Kotlin 2.4.10 (+ KSP 2.3.10) + androidx
   (core 1.19.0, compose-bom 2026.06.01, lifecycle 2.11.0, room 2.8.4,
   paging 3.5.0, …) — with **Hilt 2.60.1 substituted for #7's 2.59.2**
   (2.59.2 stays broken under Kotlin 2.4; 2.60.1 is why this works).
2. `compileSdk`/`targetSdk` 35 → **37** (androidx `core` 1.19 requirement).
3. AGP 9 DSL migration in `app/build.gradle.kts`: dropped the removed
   `kotlin-android` plugin (Kotlin is built into AGP 9) and moved
   `kotlinOptions { … }` to top-level `kotlin { compilerOptions { … } }`.
4. Verified locally against SDK platform 37:
   `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease`.

Once that PR is on `main`, all five Dependabot PRs propose versions at or
below what `main` already has, and Dependabot closes them as superseded on
its next pass (any residual newer-patch bumps it opens afterwards are
ordinary single-dependency PRs, mergeable on their own green CI).

**Normal Dependabot service is resumed** — this note is history, not policy.
