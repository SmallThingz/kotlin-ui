# Owned outline cache coherence correction

Unpublished host-only correction of candidate `93bea5f2c412aadb600b06f97063a70dc39367d6`.
Own branch `codex/outline-coherence-20261125`, worktree
`/dev/shm/codex-outline-coherence-20261125/worktree`. Halley's checkout is read-only
and unchanged. Root reviews this candidate before any future build or integration.

Planck found a concrete stale delivered-outline defect: with radiusDp8 and fixed
320x96 bounds, layout at density1 caches radius8; an explicit owned outline refresh
at density2 delivers radius16; returning to density1 before the next layout matches
the old key and skips the refresh. A failed custom-to-owned provider setter can
similarly leave a custom outline installed while the old owned key still matches.
The original finding is retained unchanged in `planck-original-finding.json`.

## Minimal correction

Six lines in `NativeMaterialButtonView.kt` clear the matching target's validity at
the start of the owned provider's `getOutline`, before reading density or computing
the radius. Referential identity selects only the button or its content; an
unrelated View passed to that provider affects neither cache. The existing layout
helper alone commits a key after successful invalidation. External refreshes and
failed refreshes therefore cause one conservative refresh on the next layout.
Even direct provider sampling must dirty the key: a sampled Outline is not proof
that Android installed it in the RenderNode.

The source-bound host runner proves all other production bytes identical to93bea5f:
custom/null fallback, finite checks, provider identity, separate keys, success-only
commit, child and role layout, input.measured, clipping, input, semantics and draw
are untouched. No new allocation, API, field, provider wrapper or testing hook is
introduced. This is correctness work, not a measured performance improvement.

The Android test retains its original three methods and adds two for the external
density round trip and failed custom-to-owned return, each covering both targets.
Its existing direct-provider sampling and explicit-invalidation calibration now
assert one refresh per dirtied target before the subsequent stable-layout check.
Neither stable checks nor custom/null/identity scenarios were removed.

## Focused evidence

| Check | Actual result | Scope / limit |
| --- | --- | --- |
| Corrected source, delivered-outline host model | 15 cases, 70 assertions, zero failures | Exact production provider/cache/helper/onLayout/radius text; JVM View/Input doubles |
| Exact93bea5f parent against same cases | Compiled; ten failed cases | Both targets retain16 instead of8 after density round trip, and3 instead of8 after failed custom return |
| Clear-after-callback mutation | Compiled; two failed cases | Both custom-return exceptions retain3 instead of8; confirms reset must precede the failing read |
| Selected production and mounted test source compile | Exit0; source hashes unchanged | Retained hash-verified compiler/dependencies; historical e17 APIs; not a whole-library/APK build |
| Five mounted Android test methods | COMPILED, NOT_RUN | No device, emulator, rendering, frame, CPU or Android acceptance |

The host model follows the retained Android16 source's synchronous provider callback
before RenderNode assignment: a throwing callback leaves the prior delivered value.
It includes same-key layout suppression, independent targets, exact provider identity
even with permissive custom equals, custom/null fallback, dimensions, radius, density
including clamping, nonfinite bypass/recovery, input/child/role work, failed invalidation,
direct sampling, and attachment-style rebuild. It does not simulate Android scheduling,
RenderNode native code, graphics, subclass overrides or arbitrary reentrant getters.
Source URL/hash/line bindings are in `source-binding.json`.

Original Halley32-assertion/six-mutant results remain historical and were not rerun.
The new parent-negative run is a targeted regression, not renewed acceptance of93bea5f.
No runtime timeout, replay, Android input, APK packaging, publication or main edit occurred.
Compiler heap sizing is ordinary host tooling configuration, not a test runtime budget.

## Reproduction and retained outputs

From this worktree, use new output paths; existing output directories are rejected:

```sh
python -B scripts/test_button_outline_coherence_host.py --out /dev/shm/OWN_NEW_ATTEMPT/host --tool-receipt /dev/shm/kotlin-ui-owned-outline-halley-20261125/.tmp/outline-layout-review/compile-02/result.json
python -B scripts/compile_button_outline_focus_host.py --out /dev/shm/OWN_NEW_ATTEMPT/compile
```

The exact first-run logs, generated candidate/negative Kotlin fixtures and result
receipts are archived here. Class outputs and scratch remain under the owned RAM
directory `/dev/shm/codex-outline-coherence-20261125`; they are not committed.
The selected compile retains six warnings (deprecated recycle and unnecessary
non-null assertions), including the unchanged shared Activity dependency.

Coordination: `ui-library`25476/25486 ownership/approach, Planck25479/25485 finding
and approach review, Newton25497 exact source and new results. Final source review
is separate from these host results. No claim is made about Android runtime or
performance qualification.
