# Native browser address-pill regression

Opt-in Android SDK36 / Robolectric4.18.1 native-graphics fixture. Existing tests in
`tests/browser_address_surface` and `tests/browser_tabs_count_header` stay intact.
These two Java files preserve the qualified source03 bytes, including the public
framework input route. No additional Gradle source set or dependency is required.

## Qualified selection

`BrowserRegressionOneCase BrowserPillRegressionTest.attachedPillTargetsAndTouchVersusKeyboardFocus`

Actual native host PASS: one JUnit case, eight toolbar images. Covers light/font1
LTR and dark/font2 RTL, 48dp independent site/tabs/menu owners, shared address
ancestor and text padding, 36px optical glyph separation at mdpi, nine independent
root-dispatched target probes per direction, touch editing without an accent
outline, and keyboard focus with the outline fixed to the viewport during scroll.
The framework keyboard route enters the attached ViewRootImpl, matching the
retained Home keyboard probe. Global Instrumentation.setInTouchMode alone did not
change the already-attached window; the prior failure is retained externally.

`actualControlPillRetainsCompositionAcrossDirectionAndWidth` remains in the test
class unchanged. Its source02 execution separately passed on actual Control
PhoneBrowserView with a native editor across320/380 LTR/RTL. It is deliberately
not selected by the single-case source03 runner. Neither case qualifies a physical
IME, a real WebView provider/network, PhoneActivity, or OS permission delivery.

The qualified binary basis was Control c1e85eb482d99594753dfb9d66c2a891656c77ea,
shared201fe55073ba9f09136d73280e4e029a13b7ebd3 plus the reviewed Chrome938eae55
and Tabs1673b715 overlay. This is not evidence that a subsequent API patch or full
library rebuild was tested. PNG visual review is separate from JUnit acceptance;
a far-left partial glyph in the keyboard-focus scroll40 image remains under review.

## Retained host dependencies and explicit binding

This is an integration fixture, not an independently executable library unit test.
The admitted host supplies these existing compiled inputs; do not replace them
with stub classes or relax their assertions:

- Default-package `PhoneBrowserSnapshotTest` and `PhoneBrowserSnapshotTest$Fixture`
  from the retained Browser05 source (SHA256
  `2dbe68663e1fc1f8721608ff29603c12c5e9f063bb24ac425ab6e1f50839e50d`).
- Its existing host adapter classes, app/R classes, binary Android resources,
  manifest and test_config.properties; Kotlin/AndroidX/JUnit dependencies.
- Actual app JAR and shared JAR (or changed-class overlay before the full parent
  shared JAR), SDK36 Robolectric instrumented Android and native runtime/assets.

Browser05's Fixture constructor does not call the historical shared201-literal
VerifyHome15Freeze. That class may remain among retained compile dependencies but
its old qualification method is not invoked here. Root binds the complete input
freeze outside the JVM. Inside the test, class-resource URLs and bytes must match
the exact sealed app/shared JAR paths, whole-JAR SHA256s and class-entry SHA256s.
Robolectric's instrumented CodeSource.location was observed null; it is not used.

The harness expects these system properties:

| Property | Value supplied by the admitted host |
| --- | --- |
| control.actualActivity.rootAdmitted | true, only after external admission |
| address.test.output | fresh existing output directory |
| browser.regression.sharedJar | absolute JAR owning ReverbBrowserChrome.class |
| browser.regression.sharedSha256 | SHA256 of that JAR |
| browser.regression.sharedClassSha256 | SHA256 of smallthingz/ui/reverb/ReverbBrowserChrome.class inside it |
| browser.regression.appJar | absolute actual Control app JAR |
| browser.regression.appSha256 | SHA256 of that JAR |
| browser.regression.appClassSha256 | SHA256 of dev/aicore/app/phone/browser/PhoneBrowserView.class inside it |

Compile only these two Java sources once using the admitted host's existing javac
argv: `--release17 -implicit:none -proc:none -sourcepath ''`, its exact compile
classpath and a fresh output classes directory. The actual separate argv tokens
are `--release`, `17`. Runtime classpath starts with the newly compiled fixture,
then the sealed overlay if present, then unchanged parent host/app/library inputs.
Execute the one selector above using the existing JUnit/native host stage. Use the
existing serial locks, epoch, SDK restoration checks and fresh resource admission;
the property alone is not authorization. Preserve failures; no automatic retries.
The retained profile uses javac384MiB heap/192MiB metaspace and host768/256, one VM.
No script here downloads dependencies, restores SDKs, or dispatches a VM.

## Still unqualified: native first tap and later cursor placement

Proposed separate selector:
`BrowserAddressTouchSelectionTest.firstTapSelectsAllThenLaterTapPlacesCursor`.
Do not include it in the passing selection until implemented and actually run.

1. Reuse Browser05 actual Control fixture; leave its initial overview normally.
   Use the existing committed URL, an unfocused native editor, touch mode and
   stable attached geometry. Cover LTR and RTL in separate fresh fixtures.
2. Send finger DOWN/UP through the real window's framework root to an editable
   interior point outside the site48dp owner. Do not call requestFocus,
   setSelection, selectAll, an accessibility click, or an editor callback.
3. Assert editor gained focus and the complete unchanged URL is selected: lower
   endpoint0, upper endpoint text.length. Verify no navigation operation changed.
4. Wait beyond ViewConfiguration.getDoubleTapTimeout so the next tap is a distinct
   tap, not a double tap. Choose a visible non-edge glyph boundary from the real
   TextView Layout, accounting for compound padding and scroll. Record the native
   getOffsetForPosition prediction before dispatch, requiring an interior offset.
5. Deliver the second finger DOWN/UP through the same framework route. Assert
   selectionStart==selectionEnd==the predicted interior offset, still focused,
   unchanged text/input type and no navigation. Preserve the same Editable.
6. Commit a single sentinel through its real InputConnection and assert insertion
   exactly at that offset (prefix+sentinel+suffix), without submitting. Capture the
   actual parent toolbar and selection/coordinates before and after.

The qualified source03 pointer helper enters touch mode but never asserts the
first selection endpoints or the later collapsed cursor. Composition retention
from source02 also does not cover this gap. Long-press word selection, handles,
physical keyboard behavior and real IME dismissal are separate, unclaimed scopes.
