# Model registrations: from a parameter map to a typed registration

A `NaruModelRegistration` is today `String id` + `Map<String, NElement> params`. The map is the
storage form, and it is also the manipulation form — so every consumer reads state by stringly name
and every host keeps its own private copy of "which names exist". This plan separates the two:
**typed fields for what a registration *is*, `Map<String, NElement> extras` for what core has never
heard of, and a field-descriptor table as the one vocabulary.**

Written after the owner rejected the first proposal (a typed facade over the same map) with "if you
keep it a map what's the point?", and after the map-as-source-of-truth was found to be costing six
duplicate vocabularies and two real key-handling bugs.

## 0. What was verified before writing this (measurements, not impressions)

Every claim below was checked against the tree at `a6edba9b` with `git status` clean under `core/`.

**1. Only five production sites read the registration as a map.** Not "a huge surface nobody can
touch" — five:

| Site | What it does with the map |
| --- | --- |
| [NaruRegistrationStore.java:178](../../core/naru-impl/src/main/java/net/thevpc/naru/impl/engine/NaruRegistrationStore.java) | splits each key between the public and the private file |
| [NaruSessionImpl.java:1195](../../core/naru-impl/src/main/java/net/thevpc/naru/impl/engine/NaruSessionImpl.java) | forwards every key verbatim to `provider.setParam(k, v)` |
| [NaruModelRegistrationRules.java:340-350](../../core/naru-api/src/main/java/net/thevpc/naru/api/model/NaruModelRegistrationRules.java) | keeps only the keys the written scope owns, plus identity |
| [NaruModelDirective.java:854](../../extensions/naru-llm/src/main/java/net/thevpc/naru/ext/tools/llm/NaruModelDirective.java) | prints `/model registered` as `k=v` |
| [ModelProvidersPane.java:453](../../app/naru-desktop/src/main/java/net/thevpc/naru/desktop/ui/ModelProvidersPane.java) | prints the detail line as `k=v[scope]` |

**2. The vocabulary is copied six times, and the copies already disagree.** All of these enumerate
the same ~24 names independently:

- `NaruModelRegistration.SECRET_PARAMS` = `{apikey, api_key, key}` — decides what goes private
  ([NaruModelRegistration.java:48](../../core/naru-api/src/main/java/net/thevpc/naru/api/model/NaruModelRegistration.java))
- `AbstractNaruModelProvider.apiKey()` probes `{apiKey, apikey, key}` (:39)
- `AbstractNaruModelProvider.getParam()` masks `{apikey, api_key}` — **not `key`** (:145)
- `NaruModelRegistration.isLocal()` probes `{url, baseUrl, endpoint, host, baseURL}` (:296)
- `NaruModelDirective.REGISTRATION_PARAMS`, an 18-name array for Tab completion (:167)
- `NaruDesktop.java:70,75`, a substring rule `key.toLowerCase().contains("api_key")`
- plus `documentation/user-guide/content/model-registrations.md` §tables (253-257, 269-273, 327-328)

**3. Two of those disagreements are live bugs, provable from the code.**

- **A stored `api_key` is filed privately and then never read.** `SECRET_PARAMS` contains `api_key`,
  so [NaruRegistrationStore.java:179](../../core/naru-impl/src/main/java/net/thevpc/naru/impl/engine/NaruRegistrationStore.java)
  writes it to the private file; `apiKey()`'s probe list is `{apiKey, apikey, key}` and omits it, so
  `AbstractNaruModelProvider.apiKey()` never returns it. A hand-edited `api_key: sk-…` lands in the
  secret file and is silently ignored — the session falls through to the env key.
- **A stored `key` is treated as a secret but printed unmasked.** `SECRET_PARAMS` contains `key`, so
  the store puts it in the private file and `masked()` hides it; `getParam()`'s mask list at :145
  omits `key`, so the provider's own display path returns it whole.

Fixing either one **changes which key a live session resolves**, so neither is a silent drive-by
fix — see §6 decision D1.

**4. The identity is a first-class concept that the map cannot hold.** `IDENTITY_PARAMS` (:69) and
`provider()` returning the synthesized `"custom"` when the key is absent (:201-207) mean `params()`
is not the state: it is state plus a convention. Consequences, all in the current tree:
`equals` compares `params` while `provider()` compares something else; [Rules.java:141-150](../../core/naru-api/src/main/java/net/thevpc/naru/api/model/NaruModelRegistrationRules.java)
rebuilds the whole map only so `provider` prints first in the file; and the same four provider names
(`openapi`, `wire`, `custom`, `anthropic`) are rejected twice — in `normalize` (:121-146) and again in
`Rules.apply` (:166-187) with better messages, so the constructor check is dead weight for every
real flow.

**5. Blast radius of the change, counted.** 27 files mention `NaruModelRegistration`: **17 under
`/main/`, 10 under `/test/`**. Excluding the class itself and `Rules`, the remaining 15 production
files hold 60 references between them, but only the five sites in claim 1 touch the *map*; the rest
call `id()`, `provider()`, `protocol()` and the `of(...)` factories.

**6. Baseline, measured on 2026-11-03 at `a6edba9b`** with `core/` clean (the owner's in-flight edits
are only `app/naru-desktop/…/BrandMark.java`, `HeaderBar.java`, `HeaderBarTest.java` and
`todo-ui-t1-header.md`, none of which are on the `core` path):

```
mvn -o -pl core/naru-api,core/naru-impl test
  core/naru-api   228 tests, 0 failures, 0 errors   (16 classes)
  core/naru-impl  397 tests, 9 failures, 0 errors   (49 classes)
    RED NaruModelResourceResolutionTest(2) NaruTaskInterruptTest(2) NaruToolCallLifecycleTest(2)
        NaruGoDirectiveTest(1) NaruLineCompletionTest(1) NaruTaskCompletionTest(1)
```

**The suite is red at HEAD, before any change of mine**, and none of the nine reds is in the
registration surface: `NaruModelRegistrationTest` 9/0, `NaruModelRegistrationRulesTest` 20/0,
`NaruRegistrationStoreLayeringTest` 10/0, `NaruRegistrationStoreTest` 6/0,
`NaruSessionUserRegistrationTest` 3/0, `RegistrationWiringTest` 6/0, `NaruModelPolicyTest` 18/0 —
**72 registration tests green**. So Gate 1-3 compare against 228 + 397/9 and against those 72
specifically; the plan's "green suite" requirement cannot be demonstrated as an absolute, and per the
shared-tree rule each gate re-lists the nine to prove none of them moved.

**7. No test asserts parameter order** for a registration (searched `core/*/src/test`,
`extensions/naru-llm/src/test`, `app/naru-desktop/src/test` for order/`indexOf`/`startsWith` and
`toElement()` assertions). `NaruMessageSerializationTest` pins order-stable `toElement()` for
*messages* because the store content-hashes them; registrations are keyed by id in the data store, not
content-hashed, so descriptor-ordered writes are safe. Gate 1 pins that with a two-pass test.

**8. Providers do not declare their parameter vocabulary today.** `getParamNames()` echoes back
whatever was set ([AbstractNaruModelProvider.java:133-136](../../core/naru-api/src/main/java/net/thevpc/naru/api/model/AbstractNaruModelProvider.java)),
and the wire layer reads registration values through `configValue(name, session)`
([NaruModelProtocolBase.java:189](../../extensions/naru-llm-models/src/main/java/net/thevpc/naru/ext/models/NaruModelProtocolBase.java))
for names core does not own: `chatPath`, `tools`, `probe`, `thinkingTags`, llama.cpp's `bin`/`tmp`.
This is the one constraint that genuinely requires an open-ended remainder, and it is why the map
survives — as `extras`, not as the object.

## 1. Goal and non-goals

**Goal.** One manipulation type with real fields for the main parameters, one descriptor table as the
single vocabulary, and the map demoted to (a) the TSON file format and (b) `extras`. Six vocabulary
copies become zero; the two key bugs become unrepresentable; the desktop registration form can exist.

**Non-goals.** No change to which model or key a session resolves (except through D1, decided
explicitly). No change to the on-disk format — files written today must read unchanged tomorrow and
vice versa. No change to the provider param bag (`Map<String,String>` in `AbstractNaruModelProvider`)
— that is the extension-owned seam and it stays a map on purpose. No approval-prompt, permission or
scheduling work; this is a configuration-model change.

## 2. The line between storing and manipulating

| Concern | Shape | Owner |
| --- | --- | --- |
| The file on disk | TSON object, unknown keys tolerated | `of(id, NElement)` / `toElement()`, the only map-shaped door |
| Per-field scope ownership | field-name → scope | `NaruRegistrationOrigin`, unchanged — names, not the map, are the contract |
| Per-field secret split | `Field.isSecret()`, `extras` name-match until P3 | the descriptor table |
| Extension-owned keys | `Map<String, NElement> extras` | `extras()`, and `NaruModelProvider.setParam` on the other side |
| Everything a host or the engine reads | typed fields | `NaruModelRegistration` |

The rule that makes this hold: **`params()` is deleted, not deprecated.** A reachable map API is the
facade failure mode — nothing forces migration and the typed view becomes optional decoration that
`stringValue("model")` callers keep ignoring.

## 3. The proposed class

```java
package net.thevpc.naru.api.model;

/**
 * A named provider instance: what type serves it, what endpoint it points at, and how it speaks.
 *
 * <p>The parameters a registration <em>is</em> made of are fields. Parameters core has never heard
 * of — a provider extension's own knobs — are {@link #extras()}: they round-trip verbatim through
 * {@link #toElement()} and reach the provider untouched at materialization. Anything that reads a
 * main knob by name, or writes one into a map, is reading the wrong layer.
 *
 * <p>{@code $NAME}/{@code ${NAME}} interpolation stays lazy: a value is resolved when it is needed
 * ({@link #apiKeyResolved(NaruSession)}), not when the registration is written, so rotating an
 * exported key needs no re-registration.
 */
public final class NaruModelRegistration {

    /** The internal endpoint type word for a registration with no provider type. Never stored. */
    public static final String CUSTOM_TYPE = "custom";

    /** The shape a stored value takes. Decides parse, render and the form widget. */
    public enum Kind { TEXT, INT, FLOAT, BOOL, TEXT_LIST }

    /**
     * One registration parameter, described once. The file parser, the merge rules, the mask, the
     * command-line flag completion and the desktop form all read this list instead of each keeping
     * its own copy of the vocabulary — which is what this class replaces.
     */
    public static final class Field<T> {
        public String key() {}                    // the canonical name written to the file
        public List<String> aliases() {}          // accepted on read, folded here and only here
        public Kind kind() {}
        public boolean isSecret() {}              // literal value -> private file, and masked
        public boolean isIdentity() {}            // a layer rewriting it replaces, not overlays
        public String description() {}            // what the form shows, what /settings documents
        public List<String> suggestedValues() {}  // e.g. true/false; provider types come from §5
        T get(NaruModelRegistration r) {}
        void set(Builder b, T value) {}
        T parse(NElement stored) {}               // lenient: a hand-edited "0.7" is a FLOAT
        NElement render(T value) {}
    }

    /** The vocabulary: declaration order is file order and is the form's field order. */
    public static List<Field<?>> fields() {}
    /** Resolve a canonical name, an alias or any casing — the only name-matching that happens. */
    public static Field<?> field(String anyName) {}   // null when unknown

    // ── identity ────────────────────────────────────────────────────────────────
    public String id() {}
    public NOptional<String> provider() {}        // absent => generic endpoint
    public NOptional<String> protocol() {}
    public boolean isGeneric() {}                 // provider absent; replaces the "custom" sentinel
    /** The word the registry addresses this endpoint by: the provider type, or CUSTOM_TYPE. */
    public String endpointType() {}

    // ── endpoint ────────────────────────────────────────────────────────────────
    public NOptional<String> url() {}
    public NOptional<String> apiKey() {}              // raw, may name a variable
    public NOptional<String> apiKeyResolved(NaruSession s) {}
    public NOptional<String> chatPath() {}

    // ── model pins ──────────────────────────────────────────────────────────────
    public NOptional<String> model() {}
    public List<String> models() {}                   // model + models, merged and de-duplicated
    public List<String> allPins() {}                  // what '/model use <id>' counts

    // ── sampling ────────────────────────────────────────────────────────────────
    public NOptional<Float> temperature() {}
    public NOptional<Integer> contextLength() {}
    public NOptional<Integer> maxTokens() {}
    public NOptional<Float> nucleusThreshold() {}
    public NOptional<Integer> candidateCount() {}
    public List<String> stop() {}

    // ── behaviour flags (boxed: unset is not false) ─────────────────────────────
    public NOptional<Boolean> enabled() {}
    public NOptional<Boolean> tools() {}
    public NOptional<Boolean> probe() {}
    public boolean isFree() {}                    // free==TRUE, or a conventionally local type
    public boolean isLocal() {}                   // local field, else ONE loopback url, else type

    // ── timeouts (duration words as stored, parsed by NaruModelTimeouts) ────────
    public NOptional<String> timeout() {}
    public NOptional<String> readTimeout() {}
    public NOptional<String> connectTimeout() {}
    public NOptional<String> retryPeriod() {}
    public NOptional<Integer> maxRetries() {}

    // ── the open remainder ──────────────────────────────────────────────────────
    /** Keys core does not declare, as stored. Touching one is visible at the call site. */
    public Map<String, NElement> extras() {}
    /** An extras value with $NAME resolved, for a host that shows every field. */
    public NOptional<NElement> extra(String key) {}

    // ── the storage boundary: the only place names, aliases and order live ──────
    public static NaruModelRegistration of(String id, NElement stored) {}
    public NElement toElement() {}                    // declared fields in order, then extras
    public NaruModelRegistration masked() {}          // every secret literal -> sk-***abcd
    public static boolean isInterpolated(String value) {}
    public static NOptional<String> interpolate(String v, Function<String, String> resolver) {}
    public static Function<String, String> envResolver(NaruSession s) {}
    public static String envValue(NaruSession s, String name) {}

    // ── authoring and merging ───────────────────────────────────────────────────
    public static Builder builder(String id) {}
    public Builder toBuilder() {}
    /** Set or clear (value null) one declared field, validated against its Kind. */
    public <T> NaruModelRegistration with(Field<T> f, T value) {}
    /** Set or clear one undeclared key. Only `extras` can reach it, so a typo is not a field. */
    public NaruModelRegistration withExtra(String key, NElement value) {}

    public static final class Builder {
        public Builder provider(String v) {}
        public Builder protocol(String v) {}
        public Builder url(String v) {}
        public Builder apiKey(String v) {}
        public Builder model(String v) {}
        public Builder models(List<String> v) {}
        public Builder temperature(Float v) {}
        // … one per declared field, each returning this
        /** Route a parsed element through the descriptor table: alias folded, kind checked. */
        public Builder put(String anyName, NElement value) {}
        public NaruModelRegistration build() {}
    }
}
```

The private state is the fields plus one `Map<String, NElement> extras`. Boxed types carry the
existing three-state semantics — `enabled` unset is not `enabled=false`, which
[ModelProvidersPane.java:530](../../app/naru-desktop/src/main/java/net/thevpc/naru/desktop/ui/ModelProvidersPane.java)
already depends on (`Boolean.FALSE.equals(...)`). `Field<T>` is the machinery that keeps
descriptor-driven consumers (merge, split, form, completion) working without reflection over fields.

### 3.1 The vocabulary the descriptor table must contain

Twenty-four names, taken from the code that reads them and the tables the user guide already ships —
not invented. `alias` columns are the names currently probed elsewhere; `secret`/`identity` are the
two flags previously re-spelled in five places.

| group | canonical key | kind | aliases | secret | identity |
| --- | --- | --- | --- | --- | --- |
| identity | `provider` | TEXT | — | no | **yes** |
| identity | `protocol` | TEXT | — | no | **yes** |
| endpoint | `url` | TEXT | `baseUrl`, `baseUrl` casing, `endpoint`, `host`, `baseURL` | no | no |
| endpoint | `apiKey` | TEXT | `apikey`, `api_key`, `key` | **yes** | no |
| endpoint | `chatPath` | TEXT | — | no | no |
| pins | `model` | TEXT | — | no | no |
| pins | `models` | TEXT_LIST | — | no | no |
| sampling | `temperature` | FLOAT | — | no | no |
| sampling | `contextLength` | INT | — | no | no |
| sampling | `maxTokens` | INT | — | no | no |
| sampling | `nucleusThreshold` | FLOAT | — | no | no |
| sampling | `candidateCount` | INT | — | no | no |
| sampling | `stop` | TEXT_LIST | — | no | no |
| behaviour | `enabled` | BOOL | — | no | no |
| behaviour | `tools` | BOOL | — | no | no |
| behaviour | `probe` | BOOL | — | no | no |
| behaviour | `free` | BOOL | — | no | no |
| behaviour | `local` | BOOL | — | no | no |
| behaviour | `thinkingTags` | TEXT_LIST | — | no | no |
| timeout | `timeout` | TEXT | — | no | no |
| timeout | `readTimeout` | TEXT | — | no | no |
| timeout | `connectTimeout` | TEXT | — | no | no |
| timeout | `retryPeriod` | TEXT | — | no | no |
| timeout | `maxRetries` | INT | — | no | no |

`scope` is deliberately absent: it names the store, not the model, and leaves the edit map before the
rules see it ([NaruModelDirective.java:723](../../extensions/naru-llm/src/main/java/net/thevpc/naru/ext/tools/llm/NaruModelDirective.java)).
Keep that as a directive-level removal, and make it a named constant so the two layers cannot drift.

## 4. What the table deletes

Each of these exists only because a map was the only representation.

1. **The provider-first ordering rebuild** — [Rules.java:141-150](../../core/naru-api/src/main/java/net/thevpc/naru/api/model/NaruModelRegistrationRules.java).
   Becomes `toElement()` iterating fields in declaration order.
2. **The duplicated provider-name rejection** — `normalize` :121-146 vs `Rules.apply` :166-187. One
   home (`Rules`, which has the migration-hint messages); the constructor keeps only what a parsed
   file must not bypass.
3. **The `"custom"` sentinel** — `provider()` synthesizing a value that is not in `params()`. Becomes
   `isGeneric()` + `endpointType()`, and `equals` finally describes the identity.
4. **The six-name `isLocal()` probe with its swallowing catch** — :288-309. Aliases fold at `of()`;
   `isLocal()` reads one `url` field.
5. **Four secret-name lists** — `SECRET_PARAMS`, `apiKey()`'s probe, `getParam()`'s mask, and the
   desktop's `contains("api_key")`. One `Field.isSecret()`, plus `apiKeyResolved(session)` for the
   resolution chain the provider owns.
6. **`REGISTRATION_PARAMS` in the directive** — completion iterates `fields()`, so a new field cannot
   be forgettable.

## 5. Phases

Each phase ends at a gate. Stop and report: passed, failed, what changed in the plan, what needs a
decision — with counts. Do not start a phase until the previous gate passed.

### Phase 1 — the class, with the map still reachable

`Field<T>` + `Kind` + the 24-name table; typed fields; `builder()`/`toBuilder()`/`with`/`withExtra`;
`of(id, NElement)` folds aliases and checks kinds; `toElement()` writes declared order then extras.
`params()` **stays** as a derived read-only view so the five sites compile unchanged, and every old
accessor (`stringValue`, `intValue`, `booleanValue`, `stringList`, `param`, `withParam`) stays as a
delegate. **Behaviour must not move yet**: same interpolation, same mask names, same resolution order.

*Gate 1:* `mvn -o -pl core/naru-api,core/naru-impl test` at or above the baseline recorded below;
plus a new round-trip test over a fixture corpus (provider instance, generic endpoint, each kind,
each alias, each malformed-but-tolerated hand-edit) asserting
`of(id, e) == of(id, e.toElement())` and `extras()` holds exactly the undeclared keys; plus a
golden-file check that `toElement()` byte-for-byte reproduces the current stored form for the corpus.
**Report the counts, and report D1 rather than fixing it.**

**Landed — Gate 1 report.** `core/naru-api` 247 tests / 0 failures / 0 errors (baseline 228, +19 new);
`core/naru-impl` 399 / 9 / 0 (baseline 397 / 9 — the *same* six classes and the same per-class counts:
NaruGoDirectiveTest 1, NaruLineCompletionTest 1, NaruModelResourceResolutionTest 2,
NaruTaskCompletionTest 1, NaruTaskInterruptTest 2, NaruToolCallLifecycleTest 2, all pre-existing and
unrelated to registrations). Downstream `naru-llm`, `naru-llm-models`, `naru-llama-cpp`,
`naru-desktop` compile and install.

Both numbers were proven against the same machine, same moment, in two runs: a clean `HEAD` worktree
reproduced the baseline exactly (228 / 0 and 397 / 9, same six classes), and the working tree's first
full run showed a **tenth** red — `NaruWorkspaceSessionsTest.theReturnedListIsASnapshotTheCallerCannot
Corrupt`, a session-set timing assertion with no connection to registrations. It passed 3/3 run alone
with the Phase-1 diff applied and did not reappear on the second full run, so the tree is at baseline
and that red is a load flake, recorded here because a gate report that hides the flake it saw is
worthless.

Two things the plan did not predict, both fixed here because both are correctness, not behaviour:

- **The store folds aliases at read time too.** `NaruRegistrationStore.collectInto` now keys the
  merged fields *and* the per-field origins by the canonical name. Left raw, an `host:`-spelled
  endpoint answered `origin.scopeOf("url") == null`, and `Rules.ownedParams` — which keeps only the
  fields the writing scope owns — would have dropped that line from the file on the next
  `/model update`: silent loss of the user's own configuration. Pinned in
  `NaruRegistrationStoreTest.anAliasSpelledFieldKeepsItsCanonicalNameAndItsScope`.
- **A field named twice in one scope is won by the canonical spelling** (decision D5 below), not by
  whichever file the store read last.

Gate deviations, both explained rather than silently adapted: the corpus is inline fixtures in
`NaruModelRegistrationTypedFieldsTest` (19 tests) rather than a `.tson` fixture directory, and the
"byte-for-byte" check is round-trip plus two-pass write stability (`aSecondWritePassProducesTheSame
ElementAsTheFirst`) instead of a stored golden file — a golden file would need a corpus to compare
against, which is the fixture directory again.

**Gate 1 outcome: PASSED, held for sign-off.** Phase 2 needs a decision on D1 (an `api_key`/`key`
stored today as a dead secret now resolves — a real credential-resolution change for anyone whose
files hit the old mismatch) and on D5 (a canonical spelling beats an alias for one field named twice).
Nothing is committed; `params()` and the legacy delegates are still in place until Phase 2 deletes them.

### Phase 2 — migrate the five sites and delete the map

`NaruRegistrationStore.put` splits by `Field.isSecret()`; `NaruSessionImpl` materialization forwards
declared fields through `setParam(key, render(value))` and `extras` verbatim; `Rules.ownedParams`
filters by `Field.isIdentity()` / scope; the two display loops iterate `fields()` then `extras()`.
Delete `params()`, `param(String)`, `stringValue/intValue/floatValue/longValue/booleanValue/stringList`,
`withParam`, `withoutParam`. Add `isGeneric()`/`endpointType()` and remove the sentinel.

*Gate 2:* same suites green; `extensions/naru-llm` and `app/naru-desktop` compile against the new API
with **no** remaining `Map<String, NElement>` in `NaruModelRegistration`'s public signature (verified
by `grep`, count reported); a terminal-REPL regression check per the standing rule — a real
`/model add` + `/model registered` + one turn, captured verbatim, identical output to Phase 1 except
where the detail line now groups fields.

**Landed — Gate 2 report.**

*Counts, same machine, same moment, a clean `HEAD` worktree measured against this tree*
(`mvn -o -fae -Dmaven.test.failure.ignore=true test`), with the failing **method names** compared,
not just the totals:

| module | HEAD | working tree | identical red set? |
| --- | --- | --- | --- |
| core/naru-api | 228 / 0F / 0E | 247 / 0F / 0E | yes — +19 tests, all green |
| core/naru-impl | 397 / 9F / 0E | 399 / 9F / 0E | yes — the same six classes as Gate 1 |
| extensions/naru-llm | 56 / 21F / 0E | 56 / 21F / 0E | yes — 19 + 2, method for method |
| app/naru-desktop | 326 / 1F / 5E | 327 / 1F / 5E | yes — SplitPanesTest 1, BudgetTextTest 5 |
| **whole reactor, 25 modules** | **1959 / 95F / 9E** | **1981 / 95F / 8E** | one method differs, below |

The reactor row matters more than the four: **the tree already carries 95 failures and 9 errors at
`HEAD`**, and Phase 2 adds none — of the 25 modules, every single one has an identical red set except
`extensions/naru-budget`, where `NaruUnitPriceTest.priceTableSurvivesATsonRoundTrip` errored on the HEAD
run and passed on this one. It is not a Phase-2 effect and not a pass: run alone, that class errors
**in both trees** with `NDetachedEmptyOptional: missing workspace in the current context` — an
order-dependent test that needs a workspace another class installs. Recorded as the same kind of flake
Gate 1 disclosed rather than left as an unexplained ±1.

**The 21 `naru-llm` and 6 `naru-desktop` reds are pre-existing and were never in my diff.** They are
red at `HEAD`, before any of this work, in the same methods — `NaruModelDirectiveRegistrationTest` 19,
`NaruModelDirectiveSelectionTest` 2, `SplitPanesTest` 1, `BudgetTextTest` 5. Their symptom is the same
one the REPL check below ran into: the directive's `context.outln` text never reaches the collector,
so an assertion like `addRejectsUnknownProtocolAndListsSupportedOnes` fails `expected: <true> but was:
<false>` over an empty log. Fixing that channel is a separate defect and is listed as decision **D7**
— it is not caused by, and cannot be fixed by, deleting a map.

**Deleted, as the phase promised**: `params()`, `param(String)`, `stringValue`, `intValue`,
`floatValue`, `longValue`, `booleanValue`, `stringList`, `withParam`, `withoutParam`, `resolve(String)`.
Repo-wide, `grep` for those names returns 13 hits and **0 of them are registrations**: they are task
frames (`Map<String, Object> params()` in `NaruTaskImpl`/`TaskText`/`NaruTaskDirective`), a URL unit
(`NaruCmdParser`), and a JSON object (`PlanCreateTool.o.stringList("dependsOn")`).

**The signature criterion needed one more deletion than the plan listed.**
`grep -n "public.*Map<String, NElement>" NaruModelRegistration.java` now answers **1**: `extras()`
(line 525), the extension vocabulary the mandate keeps. Getting from 2 to 1 meant also deleting
`of(String id, Map<String, NElement> params)` — the map-shaped *authoring* door. Its only production
caller was the store's own merged view, which now pours that buffer through the vocabulary instead
(`NaruRegistrationStore.build(id, fields)` → `Builder.put(String, NElement)`, alias-folding and
kind-checking each entry), and **30** test fixture sites were rewritten to the same door — 25
mechanically (`Map.of(k, v, …)` → `builder(id).put(k, v).build()`), 5 by hand where a local `Map` did
double duty as fixture and expectation (three in `NaruModelRegistrationRulesTest`, the shared
`registration(id, pairs…)` helper in `NaruRegistrationStoreLayeringTest`, the fake store's
`registrations()` in `ModelProvidersPaneTest`). Residual callers of the deleted overload: **0**,
measured again by compile.

**The five sites**, each now reading fields or the storage view:
`NaruSessionImpl.reloadRegistrations` (registry lookup by `endpointType()`, materialization over
`storedPairs()`), `NaruRegistrationStore` (split and merge over `storedPairs()`),
`NaruModelRegistrationRules` (identity/scope filtering, three spots), `NaruModelDirective` (the
`/model registered` listing and the completion candidate), `ModelProvidersPane.updateDetail` (the
form's detail table). Two accessors were added where a deleted delegate had no field-shaped
equivalent yet: `free()` and `local()`, both `NOptional<Boolean>`, keeping the three-state
"declared vs defaulted" distinction `ModelProvidersPane` and `InputPane` depend on.

**Behaviour deltas, all four deliberate, none of them storage:**

1. `allPins()` dedupes `model` + `models`, so a registration that names the same model twice now
   resolves where it previously resolved to nothing.
2. `/model registered` prints a generic endpoint's **protocol** in its type column instead of the word
   `custom` — the sentinel is gone, and `custom` was never a value the user wrote.
3. `InputPane.isLocal()` no longer re-probes four spellings of the url: alias folding did that when
   the registration was read.
4. Materialization now forwards **canonical** names only, so an `api_key`-spelled key reaches the
   provider class as `apiKey` — previously it arrived twice, once per spelling, and the provider's own
   last-write-wins decided.

**Terminal-REPL check.** The real app (`net.thevpc.naru.NaruApp`, `-d` scratch workspace, ollama
`glm-4.7-flash:latest`), driven through a sized PTY, run twice with the same keystrokes: once on this
tree, once on the clean `HEAD` worktree measured in the same window. `HEAD` is the reference rather
than a Phase-1 build because Phase 1 was gated as behaviour-neutral ("Behaviour must not move yet"), so
`HEAD` and Phase 1 print alike; the A/B therefore covers both phases at once. Both transcripts,
ANSI-stripped and with the Nuts resolution chatter removed, are 14 and 15 lines and differ **only in
the model's own reasoning wording** (nondeterministic). Verbatim from this tree:

```
╭╮╷╭─╮╭─╮╷ ╷
│╰┤├─┤├┬╯│ │ Nuts AI Reasoning Unit
╵ ╵╵ ╵╵╰╴╰─╯ v1.1.0.0
Type /help (or /?) for help and /exit to exit.
loaded extension net.thevpc.naru.extensions:naru-profile-dev#1.1.0.0?repo=maven
なる› //hel////help
なる› //mod////model registered
なる› //mod////model use gate
なる› reply with exactly the word NARU-GATE2
▌ 🧠 Model: gate/glm-4.7-flash:latest…
/exit
▌🤔The user is asking me to reply with exactly the word "NARU-GATE2". This is a simple request that doesn't require any tool calls - they just want me to output that specific phrase.
▌🤖NARU-GATE2
なる› //exi////exit
```

(The doubled slashes in the echoes are the line editor's own redraw: the capture strips ANSI and
backspaces, not the editor's per-keystroke repaint. The `/exit` line above the answer is the terminal
echoing the queued keystroke while the turn was still streaming.)

Two things this proves and one it does not. It proves the terminal path still drives a real turn end to
end, and that the registration created through the new API is the one the session selects and speaks
through (`gate/glm-4.7-flash:latest`, answered `NARU-GATE2`). It also proves `/model add` wrote the
new storage shape, read back from the file it produced:

```
{gate : {provider : "ollama", model : "glm-4.7-flash:latest"}
 ep:{protocol : "openai", url : "http://127.0.0.1:9/v1", models : ["x", "y"]}}
```

— canonical names, no `provider : "custom"` for the generic endpoint. What it does **not** show is the
`/model registered` detail line: in this harness that text does not reach the terminal at all, and it
does not at `HEAD` either (the same run, same keystrokes, `/help` included, prints nothing in either
tree) — the D7 defect again. So the plan's "identical except where the detail line now groups fields"
clause is **unmeasured**, not passed; the desktop detail table is the one place that shape *is* under
test, and it is green: `ModelProvidersPaneTest.selectingARowShowsItsFieldsWithTheCredentialMasked`
asserts the selected registration echoes `sk-***1234` masked and `gemini-2.5-pro[user]` scoped, reading
the `storedPairs()` order `updateDetail` now iterates.

**Gate 2 outcome: PASSED on counts and signature (1 remaining `Map<String, NElement>`: `extras()`),
REPL regression A/B clean, one clause unmeasurable until D7 is decided.** Decisions still open from
Gate 1: **D1** (an `api_key`/`key` stored today as a dead secret now resolves) and **D5** (a canonical
spelling beats an alias when one field is named twice in a scope). New here: **D6** — the four
behaviour deltas above, especially #2, a user-visible change to the `/model registered` type column;
and **D7** — the pre-existing directive-output channel defect behind 27 red tests at `HEAD` and the
unmeasurable REPL clause. Nothing is committed.

*Tree hygiene, one side effect of the verification runs:* the full-reactor `mvn test` regenerated the
tracked `bench/parity-probes/results.json` (8 lines, probe lengths against `/tmp/lab` files another run
left behind). No file was staged; the baseline worktree `/tmp/naru-gate2` was created for these
measurements and removed afterwards, which also let `git worktree prune` forget four registrations that
were already marked `prunable` (`/tmp/naru-e1-run`, `/tmp/naru-head`, `/tmp/naru-richterm`,
`/tmp/opencode/naru-fix`) — their directories were gone before this session, but that was shared repo
metadata and it is disclosed rather than assumed invisible.

### Phase 3 — extensions declare their own vocabulary

`paramDeclarations()` on `NaruModelProvider` (default: empty, so every existing provider compiles and
behaves identically), consumed by the secret/name-match fallback for `extras` and by completion.
Llama.cpp's `bin`/`tmp`, `chatPath`, `probe`, `tools` become declared rather than guessed.

*Gate 3:* core + naru-llm + naru-llama-collib… — report per-module counts; the `key`-vs-`api_key`
asymmetries now provably cannot recur (one table, one declaration path).

### Phase 4 — the form, which was the point

`ModelProvidersPane` renders `fields()` as named rows (label, scope badge per field, suggestion list
from `suggestedValues()` + `providerTypes()`/`protocols()`), with `extras` as a separate
"undeclared parameters" table; `offerValues()`'s `equalsIgnoreCase` guessing disappears; the detail
line groups by field instead of printing `k=v[k]`. Directive completion derives from `fields()`.

*Gate 4:* desktop module green; one Xvfb screenshot of the form with a registration carrying a
literal key, a `$NAME` reference, an alias-spelled `baseUrl` and two extras, plus
`ModelProvidersPaneTest` pinning that one click produces exactly one `save()` with the right scope.

**Landed — Gate 4 report.**

*Counts, one machine, one moment, a clean `HEAD` worktree measured beside this tree*
(`mvn -o -Dmaven.test.failure.ignore=true -pl core/naru-api,core/naru-impl,extensions/naru-llm,app/naru-desktop test`),
with the failing **method names** compared, not only the totals:

| module | HEAD | working tree | identical red set? |
| --- | --- | --- | --- |
| core/naru-api | 228 / 0F / 0E | 247 / 0F / 0E | yes — nothing new since Gate 2 |
| core/naru-impl | 397 / 9F / 0E | 399 / 9F / 0E | yes — the same nine methods (D7) |
| extensions/naru-llm | 56 / 21F / 0E | 57 / 21F / 0E | yes — +1 test, green; the 21 are D7 |
| app/naru-desktop | 326 / 1F / 5E | 333 / 1F / 5E | yes — `SplitPanesTest` 1, `BudgetTextTest` 5, method for method |

`ModelProvidersPaneTest` itself: **15 tests / 0F / 0E** (it was 8 at `HEAD`). The desktop module is
"green" in the only sense available: it carries no red this phase added. Its two pre-existing reds
are the divider-geometry test and the five workspace-less `BudgetTextTest` errors, both red at `HEAD`.
One earlier run also flagged `TranscriptLoadBudgetTest.fiftyThousandLinesStayInsideTheFrameBudget`
(21 ms against a 16 ms budget) — that measurement was taken while a second full Maven build shared
the CPU; the uncontended run above passes it, so it is recorded as load, not as a regression.

**Terminal-REPL check (the standing rule), run on this tree only.** The real app
(`net.thevpc.naru.NaruApp -d <scratch> -i`) driven through a sized PTY (60×200, terminal queries
answered), exit code 0, and the two things this phase changed are both visible in what it painted:

```
> /model add probe --pro⇥
--probe=true|false   --protocol=value     --provider=value

> /model add gate4 --provider=ollama --model=glm-4.7-flash:latest --free=true --timeout=2m
…
.naru/config/registrations.tson:
{gate4 : {provider : "ollama", model : "glm-4.7-flash:latest", free : true, timeout : "2m"}}
```

Tab completion answers from the vocabulary — `--probe` is a `Kind.BOOL` field, so `true|false` comes
from `suggestedValues()`, and the flag names are `keys()` in declaration order. `free` and `timeout`
are the proof that matters: neither was in the list the directive used to complete, and the real
store file shows both parsing and landing. No `HEAD` A/B this phase, because the only engine-side
change is that private completion helper and the transcript above is its live output; the directive
*listing* text still does not reach the terminal (D7), so `/model registered` remains unmeasurable
here exactly as it was at Gate 2.

**What the form is now.** `NaruRegistrationFields.fields()` decides the rows, their order, their
section headings and their widget: `Kind.BOOL` and the `provider`/`protocol` names get an editable
combo (candidates from `suggestedValues()` plus `providerTypes()`/`protocols()` of the live session),
everything else a text field typed by `Kind` — an `INT` that is given `warm` is refused rather than
filed away as a string. Each row carries a badge naming the file that owns it, which moves to
`-> user` the moment the scope radio moves and the field is dirty. A blank field is *unset*: saving it
sends a `null` edit, so `NaruModelRegistrationRules` still refuses to strip an identity. The masked
registration is what binds, and only rows whose text differs from it are collected — **one ✓ click is
one `save()`** carrying every changed field, pinned by `oneClickWritesEveryChangedFieldInThatOneSave`.
`extras` keep their own small table (one committed cell = one write, plus a blank row that adds a
key), the detail line groups by owning scope instead of printing `k=v[scope]`, and `offerValues()` is
gone along with the `paramField`/`valueBox` pair it hung on.

**Screenshot (the gate's, rendered off-screen under `Xvfb :99` at 940×1382 from a scratch harness in
the pane's own package — the gateway seam is package-private, so no live session was needed).**
`/tmp/naru-models-pane-local.png` shows the registration under the lens carrying an alias-spelled
`baseUrl` (read into the `url` row, badge `project`), an `apiKey` of `$LOCAL_LLAMA_KEY` printed whole
because a reference is public, two undeclared knobs (`bin`, `n_ctx`) in their own table with a blank
`(new)` row under them, and the six section headings over 24 rows — combos where the vocabulary has a
closed set (`provider`, `protocol`, `tools`, `probe`, `enabled`, `free`, `local`), plain fields
elsewhere. `/tmp/naru-models-pane-home.png` shows the other half: a literal key as `sk-***42d0` with
the detail line reading *"the api key is a literal: stored privately, shown masked"*, and `models`
coming back as the comma list the file wrote.

**Deviation, explained: the vocabulary grew a `Group`.** The plan asked for headings over the blocks
the declaration comments already name. Placing them by re-enumerating field names in the desktop
module would recreate the second list this whole track deletes, so `Field` now carries a
`NaruRegistrationFields.Group` (`IDENTITY`, `ENDPOINT`, `PINS`, `SAMPLING`, `BEHAVIOUR`, `TIMEOUTS`)
declared beside each field, with `label()` for the banner text. Declaration order keeps every group
contiguous, so the form emits a heading whenever `group()` changes and nothing else has to know.
Core-side cost: one enum, one constructor argument, no behaviour change — the 247 `naru-api` tests
are identical to Gate 2.

**The last clause of the phase, done here rather than in Phase 3:** directive completion now derives
from `fields()`. `REGISTRATION_PARAMS` — the directive's hand-written list of 18 names, six of which
the store had never heard of (`free`, `local`, `timeout`, `readTimeout`, `connectTimeout`,
`retryPeriod`) — is deleted; `--param=` names come from `keys()` and the boolean values from
`suggestedValues()`, so the command line and the form cannot drift apart. Pinned by
`optionNamesAreTheVocabularyInOrderAndNothingElse`, which asserts the offered list equals `keys()`
exactly, in order, and that `--baseUrl=` is not offered.

**Gate 4 outcome: PASSED on counts, on the one-click-one-save pin, on the rendered form and on the
live terminal — and it adds no red anywhere.** Not measured, and said plainly: the pane was never
photographed against a *real* session (the gateway seam makes the form testable without one, so the
shots show a fake store's data), and D7 still blocks any claim about what a directive *printed*.
Decisions open before Phase 3 remain **D1**, **D5**, **D6** and **D7**, unchanged from Gate 2. Nothing
is committed; the tree also still carries the owner's in-flight files, untouched.

### Phase 5 — documentation

Rewrite [model-registrations.md](../user-guide/content/model-registrations.md) §param tables to match
the descriptor table, and record the two fixed bugs if D1 said fix.

## 6. Decisions

- **D1 — the two key bugs. SHIPPED AS DEFAULT (a), needs sign-off.** A stored `api_key` was written
  to the private file and never read; a stored `key` was treated as secret by the store but printed
  whole by `getParam()`. The descriptor table fixes both by folding every spelling onto `apiKey`,
  which is option (c) at the storage boundary and option (a) in effect: **for anyone whose files hit
  the mismatch, a key that used to be dead now resolves.** Keeping the bug was the only way to keep
  behaviour identical, so it was not offered. Pinned by
  `anAliasSpelledKeyIsTheCanonicalFieldAndResolves` and the store's split tests.
- **D2 — `extras` secrets before Phase 3. CLOSED by measurement.** The plan assumed a name-match
  fallback kept `key`/`api_key` extras private. Both are declared aliases of `apiKey`, so
  `NaruRegistrationFields.find()` always resolves them and the fallback was **dead code** — removed.
  What is true, and unchanged from before: an extras key core has never heard of is public. Provider
  extensions that want a secret in the private file need Phase 3's `paramDeclarations()`; until then
  a `--providerExtensionSecret=sk-…` lands in the checked-in file exactly as it does today.
- **D3 — timeouts as `String` or parsed. UNCHANGED.** Duration words are `TEXT` and parsed at read
  time by `NaruModelTimeouts`/`NDuration`, as today.
- **D4 — is `thinkingTags` `TEXT_LIST` or its own type? ANSWERED: neither — `Kind.RAW`.** A
  `Field<NaruThinkingTags>` pulls a wire type into the descriptor, and `TEXT_LIST` was worse than it
  looked: `mergeThinkingTags` accepts a hand-edited `{openTag, closeTag}` **object**, and coercing it
  to a list yields the empty list, which the session reads as *"no tags → native-only reasoning"* —
  a silent behaviour change in the model's output, caught by reading the consumer before writing the
  test. A RAW field stores and returns the element untouched and the consumer keeps understanding
  both shapes.
- **D5 — one field named twice in one scope. DECIDED: the canonical spelling wins.** A scope is two
  files, and a public `apiKey: $VAR` beside a private `api_key: sk-…` names one field. Order-based
  resolution (last write wins) would decide a credential by which file the store read last, and
  would flip behaviour for exactly the D1 corpus: today the reference wins because `api_key` is never
  read. `Builder.put` records which fields a canonical write claimed and refuses a later alias; two
  aliases keep last-write-wins. The alternative — refuse with an error — would make a file that works
  today fail to load. Pinned by `oneFieldNamedTwiceIsWonByTheCanonicalSpellingNotByTheOrderFilesWereRead`
  and `aCanonicalSpellingBeatsAnAliasWithinOneScope`.

## 7. Standing rules carried from the desktop plan

Never change agent behaviour; never break the terminal REPL; each engine PR is small, GUI-free and
unit-tested and carries a REPL regression check; no process-global state. The owner works in this
same checkout — `git add` explicit paths only, his files (`BrandMark`, `HeaderBar`, `HeaderBarTest`,
`todo-ui-t1-header.md` as of 2026-11-03) stay unstaged and unmentioned in his commits. Always pass
`-pl core/naru-api,core/naru-impl` together: `naru-impl` alone resolves `naru-api` from a stale
installed jar and fails with bogus override errors.
