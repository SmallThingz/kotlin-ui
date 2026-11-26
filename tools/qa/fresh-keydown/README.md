# Fresh key DOWN ownership regression

This host regression compiles the repository's actual `ViewClickController.kt`
with three small JVM fixtures: `Os.kt`, `View.kt`, and `RepeatControls.kt`.
Keep these four QA files together in `tools/qa/fresh-keydown/`, outside every
production and Gradle test source set. No build configuration or public API
change is required. The fixtures declare fake `android.*` classes and must not
be packaged in the library or added to Android/Gradle source sets.

Use a local JDK 17 or newer, `java`, and `kotlinc` on PATH. The retained
experiment used Kotlin 2.4.10 targeting JVM 17. These portable CLI instructions
are supplied for future runs; they were not executed as an additional run.
They require no Gradle invocation or Android SDK and download nothing.

From within the library Git checkout, run:

```sh
(
  set -eu
  repo_root="$(git rev-parse --show-toplevel)"
  qa_dir="$repo_root/tools/qa/fresh-keydown"
  controller_rel='src/main/kotlin/smallthingz/ui/interaction/view/ViewClickController.kt'
  run_dir="$(mktemp -d "${TMPDIR:-/tmp}/fresh-keydown.XXXXXXXX")"
  trap 'rm -rf -- "$run_dir"' EXIT

  kotlinc -jvm-target 17 -include-runtime \
    "$repo_root/$controller_rel" \
    "$qa_dir/Os.kt" "$qa_dir/View.kt" "$qa_dir/RepeatControls.kt" \
    -d "$run_dir/candidate.jar"
  java -cp "$run_dir/candidate.jar" RepeatControlsKt
)
```

The fixed controller should exit 0 and report:

```text
RESULT cases=41 failures=0; real-controller/JVM-fixtures, not Android runtime
```

For an optional parent comparison, add the following inside the same subshell,
after the candidate invocation. It reads an existing local committed object;
do not fetch a missing baseline implicitly. `baseline_ref` must identify the
unfixed controller whose SHA-256 is
`68e8621aa6d2d2f3e16148af6ac3f86b00747fc007fcbae1f43518e442caed14`.

```sh
baseline_ref='6ef55ae2dd4b34fad92ff48ea5eff8d8f712e409'
git -C "$repo_root" show "$baseline_ref:$controller_rel" > "$run_dir/ParentController.kt"
kotlinc -jvm-target 17 -include-runtime \
  "$run_dir/ParentController.kt" \
  "$qa_dir/Os.kt" "$qa_dir/View.kt" "$qa_dir/RepeatControls.kt" \
  -d "$run_dir/parent.jar"
if java -cp "$run_dir/parent.jar" RepeatControlsKt; then
  echo 'Unexpected baseline success; inspect source identity and coverage.' >&2
  exit 1
else
  baseline_exit=$?
  test "$baseline_exit" -eq 1
fi
```

An exit code alone does not establish the expected regression. Inspect the
case labels: the retained parent run had 28 failed cases and 13 passed cases,
ending `RESULT cases=41 failures=28; real-controller/JVM-fixtures, not Android runtime`.
All 28 failures concern a repeat DOWN acquiring new ownership without an
initial DOWN, or after focus loss/regain, cancellation, owner replacement,
disable/re-enable, or detach/rebind, for each of the four activation keys.

The remaining cases preserve owned repeats, duplicate initial DOWN rejection,
same-owner callback rebinding, repeated owned UP acceptance, long-click
delivery and click suppression, canceled UP, and unrelated-key rejection.
The candidate also checks the original canceled token identity and successful
fresh DOWN/UP after retirement. Assertions call the complete production
controller; they do not reimplement its admission predicate.

The actual retained experiment compiled and executed each parent/candidate
once with these exact three fixtures: parent 28 failures/41 cases, candidate
0 failures/41 cases. Its commands, logs, hashes and `RESULT.json` are separate
review evidence. The experiment's workspace-specific `run_host.py` stays with
that evidence and must not be committed into this library.

These are host JVM results. `KeyEvent`, `View`, `Handler`, and `Looper` here
are API fixtures with a deterministic queue, not Android runtime objects.
They do not establish framework dispatch, ancestor/window fallback behavior,
device event delivery, real timing, rendering or pointer behavior. Host
return contracts were reviewed in source separately. No Android/device pass
should be inferred from the 41 host cases.
