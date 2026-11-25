# Runtime sRGB channel table: isolated host experiment

Base: `d3aced695a49d193f32edfb9abb21236c5147bfc`. Branch: `codex/srgb-channel-table`.
Own RAM repository: `/dev/shm/codex-srgb-channel-table-20261125/repo`; worktree:
`/dev/shm/codex-srgb-channel-table-20261125/worktree`.
The source checkout `/dev/shm/kotlin-ui-segmented-spacing-halley-20261125` was read only.
Exact ownership was announced on `ui-library` as message25272.

This is a review candidate, with no production integration, publication, APK build,
installation, device action, or Android performance acceptance. Root's b4 performance
review remains pending. The motivating b13 sample reported 19.569 ms inclusive sampled
wall time for selection preparation and 3.980 ms self sampled wall time for channel
conversion; these are user-supplied profile observations, not CPU costs or an estimate
of savings. The existing four-input selection cache is unchanged.

## Change and equivalence argument

`SelectionContrast.kt` now initializes one private static `DoubleArray(256)` with the
original runtime transfer formula. Each masked RGB byte indexes that table. The three
weights, multiplication/addition order, alpha handling, contrast thresholds, integer
compositing, indication opacity-prefix scans, and APIs are unchanged. No literals
approximating the transfer function are stored. [source-delta.json](source-delta.json)
records the exact one-block replacement and source hashes; the rest of that file is
byte-identical to the base.

For each possible channel, the same integer divided by `255.0` takes the same branch
and arithmetic expression. All 256 results match the original formula bit-for-bit on
the tested JVM. Since each index is masked to `0..255` and the weighted expression
retains its evaluation order, this finite basis establishes equivalence for every
RGB input on that runtime without enumerating 16,777,216 colors. Direct full-ARGB
checks additionally exercise lookup positions, weights, signed inputs, and ignored
alpha. This does not assert that different JVM/ART math implementations produce
identical floating-point bits to one another.

Initialization occurs on first use of the Kotlin file facade, once per classloader
(shared process-wide in the normal app classloader), with JVM class-initialization
publication. The array is private and never mutated after initialization. It retains
2,048 bytes of numeric payload plus runtime-specific array overhead. First use pays
for all 256 conversions, including 245 `pow` evaluations, even if only one color is
requested. Warm calls do three array reads and preserve the existing arithmetic.
The [compiled bytecode](tests/bytecode.log) shows a private static array, one `newarray`
in the initializer, `Math.pow` in that initializer, and no allocation in the luminance
lookup path. This is JVM bytecode evidence, not an Android allocation measurement.

## Focused checks

| Check | Actual result | Scope / gap |
| --- | --- | --- |
| All 256 unweighted table entries | Exact raw `Double` bits equal the frozen formula | Host JVM; reflection only in test preserves private API |
| Full ARGB outputs | 10,096 exact raw-bit comparisons passed | 6,000 threshold/extreme combinations and 4,096 deterministic mixed ARGB values |
| Alpha ignored | 4,096 comparisons passed | All 256 alpha prefixes across 16 representative RGB colors |
| Existing `SelectionContrastTest` | 7 tests passed | Opacity transitions, palette reassignment, overlapping states, transparent selection, full qualified indication prefixes |
| Existing `TextSurfaceContrastTest` | 4 tests passed | Theme transition, textured range, readable ink preservation, unknown/translucent inputs |
| Combined focused JUnit run | 14 tests, zero failures; exit 0 | One run; no full-suite repeat |
| Source boundary | Exact single-block replacement passed | No algorithm, cache, API, policy, or other production file edits |
| Phone/ART/startup/frame performance | Not tested | No device actions; requires separately coordinated actual candidate evaluation |

The [test receipt](tests/result.json) retains commands, source/compiler/dependency
hashes, generated class hashes, terminal exits, and before/after input equality.
[JUnit output](tests/tests.log) and [JVM version](tests/java-version.log) are copied
verbatim. Compilation used cached Kotlin 2.4.21 on Temurin JDK 21.0.13.1, targeting
JVM 17 bytecode, with actual current
`SelectionContrast`, `TextSurfaceContrast`, and `ColorTransition` source. The retained
e17 runtime jar supplies unchanged supporting graphics/easing dependencies; it does
not supply the three classes under test because freshly compiled classes come first
on the runtime classpath. This is a focused host test, not a whole-library build.

Reproduction uses `scripts/test_srgb_luminance_host.py` with the retained compiler
classpath, test classpath, and Java home in `tests/result.json`, and a **new** owned
RAM `--output` directory. The runner makes no downloads and refuses output reuse.
Original classes/logs remain at `/dev/shm/codex-srgb-channel-table-20261125/test-01`.

## Android numeric qualification source: execution pending

Root requested the follow-up source after reviewing `b0d867b`. The new fixture is
`src/androidTest/java/smallthingz/ui/controls/SrgbLuminanceAndroidTest.kt`, using the
existing `@RunWith(AndroidJUnit4::class)` style and configured
`androidx.test.runner.AndroidJUnitRunner`. Its exact SHA-256 is
`2efa7da5c3497a78508858be114be232669b1ca9a4ebee8a20c509a0c796f14f`.
Ownership was announced on `ui-library` as message25309.

The three methods compare every runtime table entry against the original exact
formula, check 10,096 weighted ARGB outputs, and check every alpha prefix across 16
RGB samples (4,096 comparisons). Test-only reflection checks that the table is a
private static final primitive `DoubleArray(256)` in the production library
classloader and that its identity survives a luminance call. It never mutates the
table. The expected weighted calculation is copied from d3aced with its operation
order intact; comparisons use raw bits, with no epsilon or timing limits.

**Prepared source only: no compilation, APK build, installation, or Android execution
was performed for this follow-up. Android numeric qualification remains pending.**
The fixture creates no views, requests no input, and changes no global settings.
Production and existing test sources are unchanged; the 14 JVM tests were not rerun.
Root will decide build/integration and actual execution after the b4 comparison.

## Cost evidence

The separate bounded host benchmark records the actual baseline and candidate source
pins, cold initialization and allocation observations, checksums, and run limits under
[`benchmark/`](benchmark/). The [terminal result](benchmark/run-01/result.json) reports
all eight integrity checks true. Three fresh JVM forks per version each used eight
warmup batches and seven measured batches for each workload; version order alternated.
These are amortized batch timings, including loop/checksum overhead, with no overhead
subtraction or confidence claim:

| Warm host workload | Baseline median ns/call | Candidate median ns/call |
| --- | ---: | ---: |
| `srgbLuminance` | 46.550 | 0.913 |
| `selectionForegroundWithIndication` | 2,757.008 | 509.260 |
| `SelectionContrast.foreground` | 158.218 | 21.902 |

All measured warm batches recorded zero current-thread Java heap allocation for both
versions. Instrumentation measured the candidate table at **2,064 bytes** on this JVM
(2,048 payload + 16 overhead), excluding static reference/class metadata.

Five fresh JVMs per version and cold mode measured a metadata-preloaded initialization
boundary of median **34.281 microseconds** for the candidate (range 31.847–51.997),
versus 2.363 for baseline, and **2,064 allocated bytes** versus zero. This boundary
includes residual `ensureInitialized` overhead; the separately reported initialized
class control is noisy and is not subtracted. First use including lazy loading/linkage
and the first formula call measured medians 575.932 microseconds candidate versus
585.967 baseline, with overlapping ranges. **No cold-start improvement is established.**
These measurements exclude process startup, native/metaspace allocation, and other
threads. They describe this HotSpot host only and cannot establish Android latency,
startup, energy, frame, or production acceptance.
