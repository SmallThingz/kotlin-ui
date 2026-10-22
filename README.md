# Native UI

Reusable Kotlin primitives and an inline construction DSL for ordinary Android
Views. The library supplies geometry, text/font layout, animation math and
coordination, and native View construction helpers. It has no Reverb recorder or
storage dependency and no Compose or Material3 UI runtime.

The DSL constructs actual Views directly. It does not retain a second UI tree or
run a reconciler. This is an implementation boundary, not a measured zero-cost
claim: text layout, animation, allocation and View operations still have costs.

## Build

Use JDK 21 and an Android SDK with platform 37 installed. Set `ANDROID_HOME` or an
untracked `local.properties` containing `sdk.dir`. Java and Kotlin target JVM 17;
the minimum Android API is 28.

```sh
./gradlew assembleRelease testDebugUnitTest
```

The wrapper pins Gradle 9.7.1; the standalone build pins Android Gradle Plugin
9.4.0 and Kotlin 2.3.0. Reverb includes this checkout as `:native-ui` and shares
its matching toolchain classloader. The namespace remains
`app.smallthingz.nativeui` in either build.

## Integration and provenance

This is an independent Git repository, extracted with its module-only history
from Reverb. `ORIGIN.json` records the exact source snapshot and split commit.
Reverb pins a Git submodule commit and supplies a compact offline Git bundle plus
bootstrap script; no remote publication or network checkout is required.

`SOURCE-foundation.json`, `SOURCE-motion.json`, `SOURCE-motion.md`,
`SOURCE-text.json`, and `src/main/resource-provenance/` retain the original
upstream attribution and historical qualification records. Their `native-ui/`
target paths are relative to the original Reverb checkout. Old temporary source
paths are historical provenance, not current build inputs. Historical validation
statements are not a claim that a new checkout has passed current tests.

First-party code retains Reverb's GPL-3.0-only license in `LICENSE.txt`.
Upstream-derived files retain their existing Apache-2.0 notices and provenance;
the extraction does not relicense them. Dependencies remain AndroidX annotation
and emoji2 internally, with coroutines exposed in the existing public API.
