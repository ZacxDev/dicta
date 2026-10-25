# Mutation testing

`scripts/mutate.sh` breaks one narrow expression in `app/src/main`, runs the
Kotlin unit suite, and reports whether a test noticed.

A mutant the suite does **not** notice (`SURVIVED`) names a guard that is not
guarding anything. That is the whole point: a green suite is a claim, and a
mutation sweep is the cheapest way to find out whether a particular assertion
is load-bearing or decorative.

## Why this is committed

It used to live in a scratch directory. Four merged PRs quoted mutation
results from it, and when the scratch directory went away **every one of those
claims became unreproducible**. The script is the reproducible artifact.

## Usage

```bash
scripts/mutate.sh control                      # just the positive control
scripts/mutate.sh batch scripts/mutants/example.tsv
scripts/mutate.sh one NAME FILE FROM TO [TEST_FILTER]
```

Everything runs through `nix-shell ./android.nix`, which pins the toolchain —
see the header of `android.nix` for why that matters.

`MUTATE_WORKDIR` overrides the scratch directory for backups and per-mutant
logs (default: a `mktemp -d`). It is **refused if it points inside the repo**,
because that would dirty the tree the pre-flight check reads.

### Batch files

Tab-separated, `#` comments and blank lines ignored:

```
NAME <TAB> FILE <TAB> FROM <TAB> TO [<TAB> TEST_FILTER]
```

`FROM` must occur **exactly once** in `FILE`. An ambiguous pattern is refused
rather than guessed at — which occurrence a `count=1` replace hits is not the
one you pictured.

`TEST_FILTER` is an optimisation (the full suite is ~12 min; the pure-JVM
classes are seconds). It is only safe because the harness refuses a run that
executed zero tests; without that guard, a typo'd filter reads as `SURVIVED`.

## 🔴 Choosing a mutant: isolate it

**Mutate the narrowest expression that can be wrong.**

A mutant that removes a guard *together with its enclosing condition* proves
nothing about the guard and dies for the wrong reason: the test goes red
because the branch changed shape, so the kill is credited to a guard that may
never have executed. The mirror-image failure is worse — if the enclosing
condition is changed such that the guarded branch lands exactly on its own
boundary, the guard never runs, its assertion is unreachable, and the mutant is
scored `SURVIVED` while production's values would have executed it.

| | |
|---|---|
| ✅ | `500 -> Disposition.FAIL_FAST` → `500 -> Disposition.RETRY` — one enum value, branch structure untouched |
| ✅ | a bound: `20_000.0` → `21_000.0`; a comparison: `remaining > floor` → `remaining >= floor` |
| ❌ | deleting a whole `if (detail != null && …) { … }` block — two things changed, the verdict names neither |
| ❌ | a pattern occurring more than once (refused by the script) |

Also pick fixtures/values that **overshoot** a boundary rather than landing on
a power-of-two multiple of the step, or the mutated branch never executes.

## 🔴 Never commit a results table

A mutant table is void the moment the tree moves. PR #24's `m8`/`m9`/`m15`
rows were void two commits later — the patterns they named no longer existed.

Results belong in a **PR body or a session log**, stamped with the commit they
describe and with a note that they go stale. `scripts/mutants/*.tsv` holds
mutant *definitions*, which are reusable; verdicts are not.

## What the harness refuses to do

Each of these is a measured failure mode, not a hypothetical.

1. **Run on a dirty tree.** A mutant applied on top of uncommitted changes
   cannot be told apart from them, and can score a **borrowed kill** — red
   because of someone else's WIP. (Move changes aside by copying, *not* with
   `git stash`: that stack lives in the common git dir and is shared
   repo-globally with every other worktree and session.)

2. **Leave the tree mutated.** A `trap` on `EXIT`/`INT`/`TERM`/`HUP` restores
   it. 🔴 A previous harness was killed between mutate and restore and left
   `BubbleService.kt` dirty **with a guard deleted**. Committing that tree
   would have shipped a removed guard.

   A signalled run also **aborts and reports no verdict**. That is a separate
   requirement from restoring, and it was found by demonstrating the trap: a
   bare `trap restore_tree TERM` restored the file and then let the script
   *carry on and print a verdict spanning its own restore*. The gradle child is
   backgrounded and `wait`ed (bash defers a trap until the current foreground
   command returns, so a foreground gradle left a `SIGTERM` unhandled for the
   whole build) and is killed by **resolved PID**, never a `pkill -f` pattern.

3. **Trust `cmp`.** The restore is verified against a `cp -a` pristine copy —
   but only after a **negative control** proves `cmp` can report a difference
   (and does report identity for a file against itself). Without that, an
   inert comparison would make every restore look verified. The run also ends
   with a tree-level `git status --porcelain` check, which sees files the
   per-mutant restore never knew about.

4. **Trust Gradle's exit code.** The JUnit XML under
   `app/build/test-results/` is the only authority, read via
   `scripts/assert-tests-ran.py` (exit 0 = ran and green, 1 = something
   failed, 2 = nothing ran). A wrapper's trailing command swallows the status,
   and "no tests found" exiting 0 is how a false green gets believed. **A run
   that executed zero tests is reported loudly and is never scored
   `SURVIVED`** — that result is about the harness, not the mutant. The
   `TOTAL` line must also be present in the assert script's output; if its
   format changed, the harness says so rather than quoting a silent zero.

5. **Report a batch whose positive control survived.** 🔴 A mutant reported
   `SURVIVED` is a claim that the mutant actually **ran**. The JVM analogue of
   the stale-`.pyc` trap is Gradle's incremental compile/test cache: if the
   mutated source is not recompiled, the *original* bytecode is tested and the
   mutant is scored `SURVIVED` without ever executing. So every batch runs a
   mutation **known to be caught** first, and if that control is not `KILLED`
   the batch aborts and **no verdicts are reported**.

   The control is `500 -> Disposition.FAIL_FAST` → `RETRY` in
   `SttRetryPolicy.kt`, pinned by `SttRetryPolicyTest.a500IsNotRetried`. If it
   ever legitimately stops being caught, **re-pin it** in `mutate.sh` — do not
   delete it.

   `mutate.sh one` has no control by construction, and says so: a `SURVIVED`
   verdict from it is unproven and exploratory only.

## Is Gradle's cache actually safe here?

Measured, not assumed. Gradle's up-to-date checks hash input file *contents*
rather than comparing mtime+size, so the same-length-same-second edit that
defeats CPython's `.pyc` cache should not defeat it. `m1` in
`scripts/mutants/example.tsv` is exactly that shape — `20_000.0` → `21_000.0`,
eight bytes to eight bytes, written immediately after the previous run — and it
is kept in the batch so the harness keeps re-measuring the claim instead of
inheriting it. See the PR that added this file for the measurement, and
re-measure after any Gradle or AGP upgrade.
