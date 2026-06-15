# Dependabot toolchain PRs — status note

_Last checked: 2026-06-15_

There are five open Dependabot PRs that form **one entangled toolchain upgrade**.
**Do not merge any of them yet — individually or together they break the build.**

| PR | Bump |
|----|------|
| #9 | `com.android.application` (AGP) 8.7.3 → 9.2.1 |
| #8 | gradle-wrapper 8.14.3 → 9.5.1 |
| #7 | hilt 2.52 → 2.59.2 |
| #6 | kotlin group → Kotlin 2.4.0 (+ KSP, serialization 1.11, coroutines-test 1.11) |
| #5 | androidx group (core 1.19.0, compose-bom 2026.05.01, lifecycle 2.10, room 2.8.4, …) |

## Why they're blocked (the deadlock)

- **Hilt 2.59.2 (#7) requires AGP ≥ 9.0** — it won't apply on AGP 8.x.
- **AGP 9.x (#9)** has *built-in* Kotlin and forces **Kotlin 2.4**; **Gradle 9.5.1 (#8)** is required by AGP 9 (and is incompatible with the current AGP 8.7.3).
- **Hilt 2.59.2 cannot read Kotlin 2.4 metadata** — its bundled `kotlin-metadata-jvm`
  maxes out at Kotlin metadata **2.3.0**, so the Hilt annotation processor fails:
  `Provided Metadata instance has version 2.4.0, while maximum supported version is 2.3.0`.
- **androidx `core` 1.19.0 (#5)** requires **compileSdk 37**, which needs AGP 9.

So every PR depends on the AGP-9 jump, and the AGP-9 jump (→ Kotlin 2.4) breaks Hilt.
Each PR also fails on its own: Hilt 2.59.2 needs AGP 9; Kotlin 2.4 breaks the current
Hilt 2.52 the same way; Gradle 9.5.1 is incompatible with AGP 8.7.3.

This was confirmed by a real build of the combined version set (failed at the Hilt step),
and again on 2026-06-15 by re-reading the diffs (versions unchanged) and checking Maven:
**Hilt 2.59.2 is still the latest release** — no Hilt yet supports Kotlin 2.4.

## The unblock condition

When Dagger/Hilt ships a release that supports **Kotlin 2.4**, Dependabot will bump #7 to
it, and the whole stack becomes mergeable in one shot:

1. Merge AGP 9 (#9) + Gradle 9 (#8) + Kotlin 2.4 (#6) + Hilt (#7) + androidx (#5) together.
2. Raise `compileSdk`/`targetSdk` to **37** (for androidx `core` 1.19).
3. AGP 9 removed the `kotlinOptions { … }` DSL and the standalone `kotlin-android`
   plugin (Kotlin is built into AGP 9). Migrate `app/build.gradle.kts`:
   - drop `alias(libs.plugins.kotlin.android)` from the plugins blocks;
   - move `kotlinOptions { jvmTarget; freeCompilerArgs }` to a top-level
     `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17); freeCompilerArgs.addAll(…) } }`.
4. Install SDK platform 37 + build-tools before building.

Until then: leave all five open.
