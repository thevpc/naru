# Upstream fix — NCmdLine quotes inside a word (nuts)

Done 2026-11-01, found while building T3 (`todo-ui-t3-plans.md`).

## The defect

`NCmdLine`'s BASH tokenizer treated a quote as legal only at the start of a token. Measured against
the installed `nuts-runtime#1.1.0.0` with `NCmdLine.parse(line, BASH, lenient)`, and against
`bash -c 'printf "<%s> " …'` for the ground truth:

| Input | nuts strict, before | bash | nuts lenient, before |
| --- | --- | --- | --- |
| `--notes="asked the reviewer"` | error `illegal char "` | one arg `--notes=asked the reviewer` | two args `--notes=` + `asked the reviewer` |
| `--notes='a b'` | error `illegal char '` | `--notes=a b` | two args |
| `a"b c"` | error `illegal char "` | `ab c` | two args |
| `"a b"c` | **two args** `a b`, `c` | one arg `a bc` | two args |
| `x "unterminated` | **succeeds, argument silently dropped** | syntax error | succeeds, dropped |
| `trail\` | `ArrayIndexOutOfBoundsException` | `trail\` | same crash |
| `empty ""` | `empty`, `` | `empty`, `` | ok |

Two of those are worse than a parse error: a valid command line lost an argument (`"a b"c`,
`x "unterminated`), and a trailing backslash crashed instead of reporting anything.

## The fix

`core/nuts-runtime/.../xtra/shell/PosixNShellHelper.parseCmdLineArr` — the states now describe
quoting rather than token boundaries:

- a quote inside a word switches to the quoted state and **keeps the same buffer**, so
  `--opt="a b"` is one argument;
- a closing quote returns to `IN_WORD` instead of committing, so `'a b'c` is one argument;
- `IN_DBQUOTED_WORD` at end of input now throws `expected "` in strict mode — it had no case at all
  and dropped the argument — while lenient mode keeps dropping the half-typed word, as
  `IN_QUOTED_WORD` already did;
- a backslash as the last character appends a literal `\` (bash's behaviour, measured) instead of
  running off the array, in both the plain and the double-quoted escape paths.

Empty quoted arguments are preserved by the state machine itself; an `openToken` flag was written
first and removed again once the closing-quote transition made it redundant.

## Gate

- `test/nuts-runtime-test` `CmdLineTest`: **44 tests, 44 green** — 37 pre-existing plus 7 new
  (`testQuotedOptionValueIsOneArgument`, `testQuoteInMiddleOfWordKeepsTheWordWhole`,
  `testEmptyQuotedArgumentIsPreserved`, `testEscapesInsideDoubleQuotesStillWork`,
  `testUnterminatedQuoteIsAnErrorNotADroppedArgument`, `testTrailingBackslashIsKeptLiterally`, and
  one matcher-level test proving `--notes="asked the reviewer, twice"` reaches `asEntry` as a single
  value).
- Full `mvn -o -pl core/nuts-runtime,test/nuts-runtime-test test` after the change:
  **857 tests, 2 failures, 1 skipped** — `SpringTest.testRunSpring` and
  `HeavyInstallTest.testInstallJedit`. Both reproduce exactly at clean `HEAD` (`e9dea6b6b7`, no
  tokenizer change) in a throwaway worktree: `SpringTest` 1/1 failed, `HeavyInstallTest` 8 tests /
  1 failed at the same line 106. So they are pre-existing end-to-end failures (they spawn the real
  `springnuts-app-jar` and `org.jedit:jedit --version` and assert on their output — line 106 expects
  jEdit's `--version` to print "Apache Maven", which it never does), not fallout from this fix.
- Installed with `mvn -o -pl core/nuts-runtime install`, then refreshed the workspace copy at
  `~/.nuts/ws/default-workspace/lib/id/net/thevpc/nuts/nuts-runtime/1.1.0.0/` (the previous jar is
  backed up at `/tmp/nuts-runtime-1.1.0.0.jar.prepatch.bak`; `nuts install --reinstall` resolved the
  artifact but did not redeploy it, so the file was replaced directly).
- NARU side after the refresh: `extensions/naru-plan` **101 tests, 14 failures** — the same 14 that
  reproduce at clean `HEAD`, +1 new green test — and desktop `PlanPaneTest` + `PlanDirectivesTest`
  **17/17 green**.

## Still open in the same family (not touched)

- Three other copies of this tokenizer carry the old behaviour: `AbstractWinNShellHelper` (the
  WINDOWS family), `DefaultNCmdLine.parseDefaultList` and `NCmdLineUtils.parseCmdLine`. NARU parses
  with BASH, so only the one on its path was patched.
- Whitespace is inconsistent inside the tokenizer: `START` skips tab, CR and LF, but `IN_WORD`
  commits only on a space, so `a<TAB>b` is one argument and a raw newline never splits a token.
  Real bash splits on all of them. Left alone on purpose — splitting on newline could change how a
  multi-line composer input reaches the engine, which is a bigger call than this fix.
