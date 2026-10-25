#!/usr/bin/env bash
# Mutation-testing harness for the Kotlin unit suite.
#
# Breaks one narrow expression in `app/src/main`, runs the unit suite, and
# reports whether a test noticed. A mutant the suite does not notice (SURVIVED)
# names a guard that is not actually guarding anything.
#
#   scripts/mutate.sh control              # just the built-in positive control
#   scripts/mutate.sh one NAME FILE FROM TO [TEST_FILTER]
#   scripts/mutate.sh batch MUTANTS.tsv    # control first, then each mutant
#
# A batch file is tab-separated, `#` comments and blank lines ignored:
#   NAME <TAB> FILE <TAB> FROM <TAB> TO [<TAB> TEST_FILTER]
# FILE is repo-relative. FROM must occur EXACTLY ONCE in it — an ambiguous
# pattern is refused rather than guessed at, because which occurrence you hit
# is not the one you pictured. See scripts/mutants/example.tsv.
#
# ---------------------------------------------------------------------------
# WHY THIS IS A COMMITTED SCRIPT AND NOT A SCRATCH FILE
# ---------------------------------------------------------------------------
# Four merged PRs quoted mutation results produced by a script that lived only
# in /tmp. Every one of those claims is now unreproducible. The script is the
# reproducible artifact; the numbers are not.
#
# 🔴 DO NOT COMMIT A RESULTS TABLE. A mutant table is void the moment the tree
# moves — PR #24's m8/m9/m15 rows were void two commits later, because the
# patterns they name no longer exist. Results belong in a PR body or a session
# log, stamped with the commit they describe. This file holds the MECHANISM.
#
# ---------------------------------------------------------------------------
# HOW TO CHOOSE A MUTANT: ISOLATE IT
# ---------------------------------------------------------------------------
# 🔴 Mutate the NARROWEST expression that can be wrong.
#
# A mutant that deletes a guard TOGETHER WITH ITS ENCLOSING CONDITION proves
# nothing about the guard and dies for the wrong reason: the test goes red
# because the surrounding branch changed shape, so the kill is credited to a
# guard that may never have executed at all. The same trap in reverse: if the
# enclosing condition is removed such that the guarded branch lands exactly on
# its own boundary, the guard never runs, its assertion is unreachable, and the
# mutant is scored SURVIVED while production's values would have executed it.
#
# So:
#   GOOD   `500 -> Disposition.FAIL_FAST`  ->  `500 -> Disposition.RETRY`
#          one enum value; the branch structure is untouched.
#   GOOD   a bound: `20_000.0` -> `21_000.0`
#   BAD    deleting the whole `if (detail != null && ...) { ... }` block
#          — now two things changed and the verdict names neither.
#   BAD    a pattern that occurs more than once (refused by this script).
#
# ---------------------------------------------------------------------------
# WHAT THIS SCRIPT REFUSES TO DO
# ---------------------------------------------------------------------------
#  1. Run on a dirty tree. A mutant landing on top of pre-existing WIP cannot
#     be distinguished from it, and can score a BORROWED KILL — red because of
#     someone else's uncommitted change.
#  2. Leave the tree mutated. A `trap` on EXIT/INT/TERM/HUP restores it.
#     🔴 This is not hypothetical: a previous harness was killed between
#     mutate and restore and left BubbleService.kt dirty with a guard DELETED.
#     Committing that tree would have shipped a removed guard.
#  3. Trust `cmp`. The restore check runs a negative control first, proving the
#     comparison CAN fail, so a silently-always-green `cmp` is impossible.
#  4. Trust Gradle's exit code. The JUnit XML under app/build/test-results is
#     the only authority (via scripts/assert-tests-ran.py). A wrapper's
#     trailing command swallows the status, and "no tests found" exiting 0 is
#     how a false green gets believed. A run that executed ZERO tests is
#     reported LOUDLY and is never scored SURVIVED.
#  5. Report a batch whose POSITIVE CONTROL survived. 🔴 A mutant reported
#     SURVIVED is a claim that the mutant actually RAN. The JVM analogue of
#     the stale-.pyc trap is Gradle's incremental compile/test cache: if the
#     mutated source is not recompiled, the ORIGINAL bytecode is tested and
#     the mutant is scored SURVIVED without ever executing. The control is a
#     mutation known to be caught; if IT survives, the batch measured nothing
#     and no verdict from it is reported.
#
# Env overrides:
#   MUTATE_WORKDIR  scratch dir for backups and per-mutant logs.
#                   Default: a mktemp dir. Never inside the repo — that would
#                   dirty the tree the pre-flight check is reading.
#   MUTATE_MIN_TESTS  floor for the per-mutant test count (default 1).
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSERT="$REPO/scripts/assert-tests-ran.py"
RESULTS="$REPO/app/build/test-results/testDebugUnitTest"
MIN_TESTS="${MUTATE_MIN_TESTS:-1}"

# ---------------------------------------------------------------------------
# THE POSITIVE CONTROL
# ---------------------------------------------------------------------------
# A mutation that MUST be caught, pinned by SttRetryPolicyTest.a500IsNotRetried.
# Deliberately same-length-ish and structurally isolated: one enum value on one
# `when` arm. If this ever legitimately stops being caught (the policy changed,
# the test was renamed), RE-PIN IT here — do not delete the control.
CONTROL_NAME="positive-control-500-failfast"
CONTROL_FILE="app/src/main/java/dev/zacx/dicta/SttRetryPolicy.kt"
CONTROL_FROM="500 -> Disposition.FAIL_FAST"
CONTROL_TO="500 -> Disposition.RETRY"
CONTROL_FILTER="dev.zacx.dicta.SttRetryPolicyTest"

if [ -n "${MUTATE_WORKDIR:-}" ]; then
  WORK="$MUTATE_WORKDIR"; mkdir -p "$WORK"
else
  WORK="$(mktemp -d "${TMPDIR:-/tmp}/dicta-mutate.XXXXXX")"
fi
case "$WORK" in
  "$REPO"|"$REPO"/*)
    echo "FATAL: MUTATE_WORKDIR ($WORK) is inside the repo; it would dirty the tree." >&2
    exit 64 ;;
esac

# --- pre-flight 1: refuse a dirty tree -------------------------------------
preflight_clean_tree() {
  local dirty
  dirty="$(git -C "$REPO" status --porcelain)"
  if [ -n "$dirty" ]; then
    echo "################################################################"
    echo "REFUSED: the working tree is dirty."
    echo "A mutant applied on top of uncommitted changes cannot be told"
    echo "apart from them, and can score a BORROWED KILL. Commit, or move"
    echo "the changes aside (NOT with 'git stash' — that stack is shared"
    echo "repo-globally with every other worktree and session)."
    echo "################################################################"
    echo "$dirty"
    return 3
  fi
  [ -x "$ASSERT" ] || { echo "FATAL: missing $ASSERT" >&2; return 64; }
  return 0
}

# --- pre-flight 2: negative control for the restore comparison -------------
# Prove `cmp` can report a difference before we rely on it to prove a restore.
# Without this, an inert comparison would make every restore look verified.
preflight_cmp_negative_control() {
  local a="$WORK/.cmp-control.a" b="$WORK/.cmp-control.b" rc=0
  printf 'guard\n' > "$a"
  cp -a "$a" "$b"; printf 'mutant\n' >> "$b"
  if cmp -s "$a" "$b"; then
    echo "FATAL: cmp called two DIFFERENT files identical — the restore check is inert." >&2
    rc=4
  elif ! cmp -s "$a" "$a"; then
    echo "FATAL: cmp called a file different from itself — the restore check is unusable." >&2
    rc=4
  else
    echo "pre-flight: cmp negative control OK (differing files -> differ, identical -> same)"
  fi
  rm -f "$a" "$b"
  return $rc
}

# --- restore, idempotent, safe to call from a trap -------------------------
MUT_PATH=""; MUT_BAK=""; MUT_FILE=""; GRADLE_PID=""
restore_tree() {
  local rc=$?
  if [ -n "$MUT_PATH" ] && [ -f "$MUT_BAK" ]; then
    cp -a "$MUT_BAK" "$MUT_PATH"
    if cmp -s "$MUT_BAK" "$MUT_PATH"; then
      echo "    restored $MUT_FILE — byte-identical to the pristine copy (cmp)"
    else
      echo "    !!!!!! RESTORE MISMATCH for $MUT_FILE — THE TREE IS STILL MUTATED !!!!!!"
      rc=5
    fi
  fi
  MUT_PATH=""; MUT_BAK=""; MUT_FILE=""
  return $rc
}

# 🔴 A signalled run must ABORT, not merely restore and carry on.
# Measured while demonstrating the trap: with a bare `trap restore_tree TERM`
# the handler restored the file and then the script CONTINUED to compute and
# print a verdict — a verdict spanning its own restore, which is exactly the
# kind of result that must never be believed. So the signal handler restores
# AND exits. The gradle child is killed by its resolved PID (never a `pkill -f`
# pattern, which would match sibling processes and this script's own cmdline).
on_signal() {
  local sig="$1"
  echo
  echo "!!! received SIG$sig — aborting this run and restoring the tree"
  if [ -n "$GRADLE_PID" ] && kill -0 "$GRADLE_PID" 2>/dev/null; then
    kill -TERM "$GRADLE_PID" 2>/dev/null
  fi
  restore_tree
  echo "!!! ABORTED on SIG$sig — no verdict is reported for this mutant"
  exit $((128 + $(kill -l "$sig" 2>/dev/null || echo 15)))
}

# --- run one mutant --------------------------------------------------------
# echoes a one-line verdict on stdout; returns 0 KILLED, 1 SURVIVED,
# 2 ZERO-TESTS/compile error, >2 harness refusal.
run_mutant() {
  local name="$1" file="$2" from="$3" to="$4" filter="${5:-}"
  local path="$REPO/$file"
  local bak="$WORK/pristine.$name"
  local log="$WORK/mut.$name.log"

  echo "=== MUTANT $name"
  echo "    file:   $file"
  echo "    from:   $from"
  echo "    to:     $to"
  [ -n "$filter" ] && echo "    filter: $filter"

  if [ ! -f "$path" ]; then
    echo "    REFUSED: $file does not exist"
    return 3
  fi

  cp -a "$path" "$bak"
  MUT_PATH="$path"; MUT_BAK="$bak"; MUT_FILE="$file"
  trap 'restore_tree' EXIT
  trap 'on_signal INT' INT; trap 'on_signal TERM' TERM; trap 'on_signal HUP' HUP

  if ! python3 - "$path" "$from" "$to" <<'PY'
import sys
p, a, b = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(p).read()
n = s.count(a)
if n != 1:
    sys.exit(f"    REFUSED: pattern occurs {n} times, not 1 — an ambiguous "
             f"mutation would hit an occurrence you did not picture: {a!r}")
open(p, 'w').write(s.replace(a, b, 1))
PY
  then
    restore_tree; trap - EXIT INT TERM HUP
    return 3
  fi

  # A no-op "mutation" would be scored SURVIVED while changing nothing.
  if cmp -s "$bak" "$path"; then
    echo "    REFUSED: the file is unchanged — the mutant never existed"
    restore_tree; trap - EXIT INT TERM HUP
    return 3
  fi

  # Delete the XML so a stale result from a previous run cannot be read as
  # this mutant's. Counting leftover files is how a cached verdict is believed.
  rm -rf "$REPO/app/build/test-results"

  local gradle_args=(:app:testDebugUnitTest)
  [ -n "$filter" ] && gradle_args+=(--tests "$filter")
  # 🔴 `< /dev/null` is load-bearing, not tidiness. Gradle reads stdin, and
  # when this ran inside a `while read` loop over the batch file it CONSUMED
  # THE REMAINING MUTANTS: a 3-mutant batch silently ran 1 and reported a
  # clean summary for it. The batch loop no longer streams stdin either (it
  # reads the file into an array first) and asserts the planned count — but a
  # command that eats stdin must not be left able to do that at all.
  # Backgrounded and `wait`ed rather than run in the foreground: bash defers a
  # trap until the current FOREGROUND command returns, so a foreground gradle
  # meant a SIGTERM sat unhandled for the whole build. `wait` is interruptible,
  # so the abort is prompt.
  ( cd "$REPO" && nix-shell ./android.nix --run \
      "./gradlew --offline ${gradle_args[*]@Q} -Pandroid.aapt2FromMavenOverride=\$ANDROID_HOME/build-tools/34.0.0/aapt2" \
  ) > "$log" 2>&1 < /dev/null &
  GRADLE_PID=$!
  wait "$GRADLE_PID"
  local gradle_rc=$?
  GRADLE_PID=""

  # The XML is the authority. Branch on assert-tests-ran.py's exit code:
  #   0 = tests ran, all green -> the mutant SURVIVED
  #   1 = something failed     -> the mutant was KILLED
  #   2 = nothing ran/parsed   -> the result is about the HARNESS
  local assert_out assert_rc
  assert_out="$(python3 "$ASSERT" --min "$MIN_TESTS" "$RESULTS" 2>&1)"
  assert_rc=$?

  # Parsing another tool's output makes its format a dependency. Pin it: the
  # TOTAL line must be there, or we say so rather than quoting a silent zero.
  local total
  total="$(printf '%s\n' "$assert_out" | command grep -oE '^TOTAL tests=[0-9]+ failures=[0-9]+ errors=[0-9]+' | head -1)"
  if [ -z "$total" ] && [ "$assert_rc" -ne 2 ]; then
    echo "    !!! HARNESS ERROR: no TOTAL line in assert-tests-ran.py output"
    echo "        (its format changed; this verdict cannot be trusted)"
    printf '%s\n' "$assert_out" | tail -5
    restore_tree; trap - EXIT INT TERM HUP
    return 3
  fi

  local compile_err=""
  if command grep -qE '^e: |: error: |Compilation error' "$log"; then
    compile_err=1
  fi

  local verdict rc
  case "$assert_rc" in
    0) verdict="SURVIVED"; rc=1 ;;
    1) verdict="KILLED";   rc=0 ;;
    *) verdict="NO-RESULT"; rc=2 ;;
  esac

  if [ "$assert_rc" -eq 2 ]; then
    echo "    !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
    echo "    !!! ZERO TESTS RAN — gradle rc=$gradle_rc"
    echo "    !!! This result is about the HARNESS, not the mutant, and is"
    echo "    !!! NOT a SURVIVED verdict. Nothing was measured."
    echo "    !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
    if [ -n "$compile_err" ]; then
      echo "    cause: the mutant DOES NOT COMPILE — not a behavioural result."
      command grep -E '^e: |: error: ' "$log" | head -3 | sed 's/^/        /'
    elif command grep -q 'No tests found for given includes' "$log"; then
      echo "    cause: the TEST FILTER matched nothing — this is the exact"
      echo "           false-green shape the XML check exists for."
      command grep 'No tests found for given includes' "$log" | head -2 | sed 's/^/        /'
    else
      echo "    cause: unknown. Check the test filter and $log"
      tail -8 "$log" | sed 's/^/        /'
    fi
  else
    echo "    $verdict  ($total, gradle rc=$gradle_rc)"
    if [ -n "$compile_err" ]; then
      echo "    !!! COMPILE ERRORS in the log — this verdict may not be behavioural"
      command grep -E '^e: |: error: ' "$log" | head -3 | sed 's/^/        /'
    fi
    if [ "$assert_rc" -eq 1 ]; then
      # The per-class summary lines also start '  dev.' — exclude them, or a
      # class row gets reported as the test that did the killing.
      printf '%s\n' "$assert_out" | command grep -A20 'failing tests:' \
        | command grep -E '^  dev\.' | command grep -v ': tests=' \
        | head -6 | sed 's/^/    killed-by: /'
    fi
  fi
  echo "    log: $log"

  restore_tree; trap - EXIT INT TERM HUP
  return $rc
}

# --- prove the tree came back ----------------------------------------------
# Tree-level, not per-file: `git status --porcelain` sees a file the per-mutant
# restore never knew about. Reported, so the claim carries its own evidence.
verify_tree_pristine() {
  local dirty
  dirty="$(git -C "$REPO" status --porcelain)"
  if [ -n "$dirty" ]; then
    echo "!!!!!! TREE NOT PRISTINE after the run — DO NOT COMMIT !!!!!!"
    echo "$dirty"
    return 5
  fi
  echo "tree verified pristine (git status --porcelain empty) at $(git -C "$REPO" rev-parse --short HEAD)"
  return 0
}

verdict_word() {
  case "$1" in 0) echo KILLED ;; 1) echo SURVIVED ;; 2) echo NO-RESULT ;; *) echo REFUSED ;; esac
}

cmd_control() {
  run_mutant "$CONTROL_NAME" "$CONTROL_FILE" "$CONTROL_FROM" "$CONTROL_TO" "$CONTROL_FILTER"
}

cmd_batch() {
  local tsv="$1"
  [ -f "$tsv" ] || { echo "FATAL: no such batch file: $tsv" >&2; exit 64; }

  echo "=== positive control (runs FIRST; the batch is void if it survives) ==="
  cmd_control
  local crc=$?
  if [ "$crc" -ne 0 ]; then
    echo
    echo "################################################################"
    echo "BATCH ABORTED: the positive control was $(verdict_word $crc), not KILLED."
    echo
    echo "A mutant can only be reported SURVIVED if it actually RAN. The"
    echo "control is a mutation known to be caught, so a control that is"
    echo "not caught means this batch measured nothing — most likely the"
    echo "mutated source was never recompiled and the ORIGINAL bytecode"
    echo "was tested. NO VERDICTS ARE REPORTED."
    echo "################################################################"
    verify_tree_pristine
    exit 6
  fi
  echo "positive control KILLED — mutants in this batch do reach the compiler."
  echo

  # Read the whole batch into an array BEFORE running anything. Streaming the
  # file into the loop's stdin let gradle eat the un-read mutants.
  local lines=() line
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    lines+=("$line")
  done < "$tsv"
  local planned="${#lines[@]}"
  if [ "$planned" -eq 0 ]; then
    echo "FATAL: $tsv defines no mutants (only comments/blanks)." >&2
    verify_tree_pristine; exit 64
  fi
  echo "batch: $planned mutant(s) planned from $tsv"
  echo

  local names=() rcs=() n=0
  for line in "${lines[@]}"; do
    IFS=$'\t' read -r name file from to filter <<< "$line"
    run_mutant "$name" "$file" "$from" "$to" "${filter:-}"
    rcs+=("$?"); names+=("$name"); n=$((n+1))
    echo
  done

  # 🔴 A batch that ran fewer mutants than it planned is a HARNESS failure
  # reported as a clean result. This is the guard for the stdin-eating bug.
  if [ "$n" -ne "$planned" ]; then
    echo "!!!!!! HARNESS FAILURE: planned $planned mutants, ran $n. Verdicts incomplete. !!!!!!"
    verify_tree_pristine; exit 7
  fi

  echo "=== batch summary ($n/$planned mutants ran, control KILLED) ==="
  local i surv=0
  for i in "${!names[@]}"; do
    printf '    %-10s %s\n' "$(verdict_word "${rcs[$i]}")" "${names[$i]}"
    [ "${rcs[$i]}" -eq 1 ] && surv=$((surv+1))
  done
  echo
  echo "🔴 These verdicts describe commit $(git -C "$REPO" rev-parse --short HEAD) ONLY."
  echo "   They go stale the moment the tree moves. Do not commit them."
  verify_tree_pristine || exit 5
  [ "$surv" -eq 0 ] || echo "note: $surv mutant(s) SURVIVED — each names a guard worth a look."
  return 0
}

main() {
  local mode="${1:-}"
  case "$mode" in
    control|one|batch) ;;
    ''|-h|--help) sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "FATAL: unknown mode '$mode' (control|one|batch)" >&2; exit 64 ;;
  esac
  shift

  preflight_clean_tree || exit $?
  preflight_cmp_negative_control || exit $?
  echo "pre-flight: tree clean at $(git -C "$REPO" rev-parse --short HEAD); workdir $WORK"
  echo

  case "$mode" in
    control)
      cmd_control; local rc=$?
      echo; verify_tree_pristine || exit 5
      [ "$rc" -eq 0 ] || { echo "the positive control did not die — the harness is not measuring anything"; exit 6; }
      ;;
    one)
      [ "$#" -ge 4 ] || { echo "FATAL: one NAME FILE FROM TO [FILTER]" >&2; exit 64; }
      run_mutant "$1" "$2" "$3" "$4" "${5:-}"; local rc=$?
      echo
      echo "🔴 A single mutant has NO positive control. A SURVIVED verdict here"
      echo "   is unproven — it may simply never have run. Use 'batch' to get"
      echo "   the control, or treat this as exploratory only."
      verify_tree_pristine || exit 5
      exit "$rc"
      ;;
    batch) cmd_batch "$1" ;;
  esac
}

main "$@"
