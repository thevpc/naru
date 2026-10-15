# Permissions and actors

A **permission system** for Naru: an ordered, actor-aware rule set that answers
one question everywhere in the runtime — *may this actor perform this action on
this resource, right now?* — and an event-driven extension that mints and revokes
rules while a session runs.

This document is a design + implementation plan, not a description of shipped
code. It names the classes, fields and method signatures to add, the minimal
changes to existing core types, the single enforcement choke points, and an
incremental delivery plan.

---

## 1. Goals and non-goals

### Goals

1. Every instruction (`NaruStatement`) carries **who created it**: an actor /
   principal. Statements can be injected on behalf of a specific user or agent.
2. A transversal **access policy** service, consultable by every extension (FS,
   Web, Tools, Directives, Shell, …), with rules of the shape
   `(resource type, pattern, actions, subjects, role context, effect, order, source)`.
3. A full management API: **list** rules with their fields, **add/replace one**
   rule (by id, or find by pattern), **reorder** rules (first match wins),
   **query effective permission** for `resource + action + actor`.
4. Rules can be added dynamically by an **event-driven extension** reacting to
   `SESSION_START`, `TASK_SPAWNED`, `PROJECT_CHANGE`, `TASK_TERMINATED`, and
   custom permission events.
5. Enforcement lives in core seams that every feature already flows through, so
   FS/Web/Tools/Directives are covered by a small number of hooks.
6. Backward compatible by default: with no rules configured, behaviour is
   unchanged.

### Non-goals (initial cut)

- Authentication / login. A session has an **owner** principal that the host
  supplies (default: OS user); there is no credential exchange.
- Remote policy servers, ACL replication, cryptographic signing of rules.
- Replacing tool **tags**. Tags remain the coarse capability vocabulary; the
  policy is a finer, actor-aware layer that can *also* consult tags.

---

## 2. Vocabulary

| Term | Meaning |
|---|---|
| **Principal** | A durable identity: a user, a group, an agent, or the system. |
| **Actor** | The principal acting *now*, plus the **role context** (is it a user, or an agent running for a user) and, for an agent, the **owner** user. |
| **Resource** | The thing being accessed: a typed value — `file /a/b`, `url https://…`, `tool web_fetch`, `directive /cd`, `model qwen`. |
| **Action** | What is done to the resource: `read`, `write`, `execute`, `call`, `delete`, `list`, `fetch`, … |
| **Rule** | `(id, resourceType, patterns, actions, subjects, actorKind, effect, order, source, enabled, createdAt)`. |
| **Effect** | `ALLOW` or `DENY`. |
| **Policy** | The ordered rule list + a fallback default effect. First matching rule wins. |
| **Decision** | `(allowed, effect, matchedRule, reason, trace)`. |

---

## 3. Core API (new, `net.thevpc.naru.api.access`)

### 3.1 Principals

```java
package net.thevpc.naru.api.access;

public enum NaruPrincipalType { USER, AGENT, GROUP, SYSTEM }

public final class NaruPrincipal {                 // immutable value
    String id;                                     // "alice", "agent:reviewer", "devs", "system"
    NaruPrincipalType type;
    String displayName;
    String ownerId;                                // for AGENT: owning USER id; else null
    Set<String> groups;                            // direct memberships
    Set<String> roles;                             // role labels

    // accessors
    public String id();
    public NaruPrincipalType type();
    public String displayName();
    public NOptional<String> ownerId();
    public Set<String> groups();
    public Set<String> roles();

    // factories
    public static NaruPrincipal user(String id);
    public static NaruPrincipal user(String id, String displayName);
    public static NaruPrincipal agent(String id, String ownerId);
    public static NaruPrincipal group(String id);
    public static NaruPrincipal system();

    // value semantics + persistence
    public NaruPrincipal withGroup(String g);
    public NaruPrincipal withRole(String r);
    public NElement toElement();
    public static NaruPrincipal of(NElement element);
    // equals/hashCode on id+type
}
```

### 3.2 Actors

```java
public enum NaruActorKind { USER, AGENT, SYSTEM }   // the "role context"

public final class NaruActor {                      // immutable value
    NaruPrincipal principal;                        // who acts
    NaruActorKind kind;
    NaruPrincipal owner;                            // resolved owning USER for an agent; else null
    long taskId = -1;                               // provenance, -1 when unbound

    // accessors
    public NaruPrincipal principal();
    public NaruActorKind kind();
    public NaruPrincipal owner();                   // nullable
    public String id();                             // principal.id
    public long taskId();
    public boolean isUser();
    public boolean isAgent();
    public boolean isSystem();
    public Set<String> groups();                    // principal.groups (+ owner.groups when agent)
    public Set<String> roles();                     // principal.roles (+ owner.roles when agent)
    public NOptional<String> ownerId();

    // factories
    public static NaruActor user(NaruPrincipal user);
    public static NaruActor user(String userId);
    public static NaruActor agent(NaruPrincipal agent, NaruPrincipal owner);
    public static NaruActor agent(String agentId, NaruPrincipal owner);
    public static NaruActor system();

    public NaruActor withTaskId(long taskId);
    public NElement toElement();
    public static NaruActor of(NElement element);
}
```

A subject matches an actor by walking `principal` and, when the actor is an
agent, also its `owner` — that is the "agent runs on behalf of a user"
semantics.

### 3.3 Resource types (extensible)

```java
public final class NaruResourceType {               // open value type, keyed by id
    // well-known constants
    public static final NaruResourceType FILE      = of("file");
    public static final NaruResourceType DIR       = of("dir");
    public static final NaruResourceType URL        = of("url");
    public static final NaruResourceType HOST      = of("host");
    public static final NaruResourceType TOOL      = of("tool");
    public static final NaruResourceType DIRECTIVE = of("directive");
    public static final NaruResourceType MODEL     = of("model");
    public static final NaruResourceType MCP_TOOL  = of("mcp-tool");
    public static final NaruResourceType SHELL     = of("shell");
    public static final NaruResourceType PROCESS   = of("process");
    public static final NaruResourceType ENV       = of("env");
    public static final NaruResourceType TASK      = of("task");
    public static final NaruResourceType SESSION   = of("session");
    public static final NaruResourceType SKILL     = of("skill");
    public static final NaruResourceType ANY       = of("*");

    public String id();                             // lower-kebab
    public static NaruResourceType of(String id);
    // equals/hashCode on id, toString = id
}
```

`NaruResourceType.of(...)` lets any extension invent a type (`mcp-tool`) without
a core change. A tiny optional registry (`NaruResourceTypes.register/known()`)
lets `/permission` autocomplete and diagnostics list the declared vocabulary;
enforcement never requires registration.

### 3.4 Actions (extensible)

```java
public final class NaruAction {
    public static final NaruAction READ    = of("read",    "r");
    public static final NaruAction WRITE   = of("write",   "w");
    public static final NaruAction EXECUTE = of("execute", "x");
    public static final NaruAction CALL    = of("call");
    public static final NaruAction DELETE  = of("delete",  "d");
    public static final NaruAction LIST    = of("list",    "ls");
    public static final NaruAction FETCH   = of("fetch");
    public static final NaruAction APPEND  = of("append",  "a");
    public static final NaruAction CREATE  = of("create",  "c");
    public static final NaruAction ANY     = of("*");

    public String id();
    public List<String> aliases();                 // e.g. "r"
    public static NaruAction of(String id);
    public boolean matches(NaruAction other);      // this==ANY || equals(other)
}
```

### 3.5 Resource value

```java
public final class NaruResource {
    NaruResourceType type;
    String locator;                                // absolute/normalized where applicable
    String scope;                                  // project-relative display / base, nullable
    Map<String,Object> attributes;                 // e.g. tags, size, scheme, method

    public NaruResourceType type();
    public String locator();
    public NOptional<String> scope();
    public Map<String,Object> attributes();
    public NElement toElement();
    public static NaruResource of(NaruResourceType type, String locator);

    // convenience factories used by the enforcement hooks
    public static NaruResource file(NPath path, String projectRel);
    public static NaruResource tool(String toolName, Set<String> toolTags);
    public static NaruResource directive(String directiveName);
    public static NaruResource url(String url);
    public static NaruResource model(String provider, String model);
    public static NaruResource shell(String command);
}
```

### 3.6 Subjects and matching

```java
public enum NaruSubjectType { ANY, USER, GROUP, ROLE, ACTOR, AGENT, SYSTEM }

public final class NaruSubject {
    NaruSubjectType type;
    String id;                                     // null for ANY

    public static NaruSubject any();
    public static NaruSubject user(String id);
    public static NaruSubject group(String id);
    public static NaruSubject role(String id);
    public static NaruSubject actor(String principalId);   // matches agents too
    public static NaruSubject agents();
    public static NaruSubject system();

    public boolean matches(NaruActor actor);
    public NElement toElement();
    public static NaruSubject of(NElement element);
}
```

Matching table:

| Subject | Matches when |
|---|---|
| `ANY` | always |
| `USER(id)` | `actor.isUser() && principal.id==id`, **or** `actor.isAgent() && owner.id==id` |
| `GROUP(id)` | `actor.groups().contains(id)` |
| `ROLE(id)` | `actor.roles().contains(id)` |
| `ACTOR(id)` | `actor.principal().id().equals(id)` (agent or user) |
| `AGENT` | `actor.isAgent()` |
| `SYSTEM` | `actor.isSystem()` |

### 3.7 The rule

```java
public enum NaruAccessEffect { ALLOW, DENY }

public final class NaruPermissionRule {
    String id;                                     // stable; "" or null = auto id "rule-N"
    NaruResourceType resourceType;                 // null == ANY
    List<String> patterns;                         // globs over locator; empty == any
    Set<NaruAction> actions;                       // empty == any
    List<NaruSubject> subjects;                    // OR-ed; empty == anyone
    NaruActorKind actorKind;                       // null == any role context
    NaruAccessEffect effect = NaruAccessEffect.ALLOW;
    int order;                                     // sort key; equal -> insertion order
    String source;                                 // "core", "perm:project", "perm:spawn:42", "user:/permission"
    boolean enabled = true;
    Instant createdAt;
    String description;

    // accessors + fluent setters (builder style)
    public String id();                    public NaruPermissionRule id(String id);
    public NaruResourceType resourceType();public NaruPermissionRule resourceType(NaruResourceType t);
    public List<String> patterns();        public NaruPermissionRule patterns(String... globs);
    public Set<NaruAction> actions();      public NaruPermissionRule actions(NaruAction... a);
    public List<NaruSubject> subjects();   public NaruPermissionRule subjects(NaruSubject... s);
    public NaruActorKind actorKind();      public NaruPermissionRule actorKind(NaruActorKind k);
    public NaruAccessEffect effect();      public NaruPermissionRule effect(NaruAccessEffect e);
    public int order();                    public NaruPermissionRule order(int order);
    public String source();                public NaruPermissionRule source(String s);
    public boolean enabled();              public NaruPermissionRule enabled(boolean b);
    public String description();           public NaruPermissionRule description(String d);

    public NElement toElement();
    public static NaruPermissionRule of(NElement element);

    /** Whether this rule matches the request (order and enabled are NOT considered here). */
    public boolean matches(NaruResource resource, NaruAction action, NaruActor actor);
}
```

**Matching order inside one rule** (all must hold):

1. `enabled`
2. `resourceType == null || resourceType == ANY || resourceType.equals(resource.type())`
3. `patterns` empty, or any glob matches `resource.locator()` (see §5.2)
4. `actions` empty, or any `action.matches(request.action())`
5. `actorKind == null || actorKind == actor.kind()`
6. `subjects` empty, or any subject matches the actor

### 3.8 Request / decision

```java
public final class NaruAccessRequest {
    NaruResource resource;
    NaruAction action;
    NaruActor actor;
    NaruTask task;                                 // nullable, for project-relative patterns
    Map<String,Object> context;                    // extra attributes

    public static NaruAccessRequest of(NaruResource r, NaruAction a, NaruActor actor);
    public NaruAccessRequest task(NaruTask task);
    public NaruAccessRequest context(String k, Object v);
}

public final class NaruAccessDecision {
    boolean allowed;
    NaruAccessEffect effect;
    NaruPermissionRule matchedRule;                // nullable
    String reason;
    List<NaruAccessTrace> trace;                   // populated by explain(...)

    public boolean allowed();
    public NOptional<NaruPermissionRule> matchedRule();
    public String reason();
    public List<NaruAccessTrace> trace();
    public static NaruAccessDecision allow(NaruPermissionRule r, String reason);
    public static NaruAccessDecision deny(NaruPermissionRule r, String reason);
}

public final class NaruAccessTrace {
    NaruPermissionRule rule;
    boolean matched;
    String detail;                                 // why it matched / did not
}
```

### 3.9 The policy service

```java
package net.thevpc.naru.api.access;

public interface NaruAccessPolicy {

    // ── read ────────────────────────────────────────────────────────────────
    List<NaruPermissionRule> rules();                         // evaluation order
    List<NaruPermissionRule> rules(String source);
    NOptional<NaruPermissionRule> rule(String id);
    List<NaruPermissionRule> findRulesByPattern(String glob); // rules with a matching pattern
    NaruAccessEffect defaultEffect();

    // ── write ───────────────────────────────────────────────────────────────
    NaruPermissionRule putRule(NaruPermissionRule rule);       // add, or replace in place by id
    boolean removeRule(String id);
    int removeRules(String source);                            // bulk, e.g. ephemeral spawn rules
    NaruAccessPolicy clearRules();                             // keep built-ins? -> setBuiltins(false) to drop

    // ── order (first match wins) ─────────────────────────────────────────────
    NaruAccessPolicy reorder(List<String> orderedIds);         // must be a permutation of current ids
    NaruAccessPolicy moveRule(String id, int newIndex);
    NaruAccessPolicy moveRuleBefore(String id, String beforeId);
    NaruAccessPolicy moveRuleAfter(String id, String afterId);

    // ── default effect ───────────────────────────────────────────────────────
    NaruAccessPolicy defaultEffect(NaruAccessEffect effect);

    // ── evaluation ───────────────────────────────────────────────────────────
    NaruAccessDecision check(NaruAccessRequest request);
    NaruAccessDecision check(NaruResource resource, NaruAction action, NaruActor actor);
    NaruAccessDecision explain(NaruAccessRequest request);     // fills decision.trace()
    boolean isAllowed(NaruResource resource, NaruAction action, NaruActor actor);
    void requireAccess(NaruResource resource, NaruAction action, NaruActor actor)
            throws NaruAccessDeniedException;

    // ── observation ──────────────────────────────────────────────────────────
    void addListener(NaruAccessPolicyListener listener);
    void removeListener(NaruAccessPolicyListener listener);
}

public interface NaruAccessPolicyListener {
    default void onRuleAdded(NaruPermissionRule rule) {}
    default void onRuleRemoved(NaruPermissionRule rule) {}
    default void onRuleUpdated(NaruPermissionRule oldRule, NaruPermissionRule newRule) {}
    default void onRulesReordered(List<String> ids) {}
    default void onDefaultEffectChanged(NaruAccessEffect oldEffect, NaruAccessEffect newEffect) {}
    default void onDecision(NaruAccessRequest request, NaruAccessDecision decision) {}
}

public class NaruAccessDeniedException extends RuntimeException {
    public NaruAccessDeniedException(NaruAccessDecision decision);
    public NaruAccessDecision decision();
}
```

`NaruAccessPolicyListener` is the seam the event-driven extension uses both to
emit permission events and to audit decisions.

---

## 4. Minimal changes to existing core types

These are the only edits to already-shipped types. Each is additive.

### 4.1 `NaruStatement` — creator actor

`core/naru-api/.../api/stmt/NaruStatement.java`

```java
public NaruActor creatorActor;                       // new field

public NaruActor creatorActor() { return creatorActor; }
public NaruStatement creatorActor(NaruActor a) { this.creatorActor = a; return this; }

// base toElement(): also emit when present
if (creatorActor != null) a.set("actor", creatorActor.toElement());

// base clone()/copy(): shallow copy is fine (NaruActor is immutable)
```

Read-back single choke point: `NaruStatementHelper.of(NElement)` in
`core/naru-impl/.../engine/stmt/shared/NaruStatementHelper.java` is the sole
reconstructor used by `NaruTaskFrameImpl` and by every nested branch, so:

```java
public static NaruStatement of(NElement element) {
    NaruStatement s = doOf(element);                     // existing switch
    if (s != null) {
        s.creatorActor(readActor(element));              // reads "actor"
    }
    return s;
}
```

Persisting `injected` is already write-only for the base; the actor is written
and read symmetrically through the same base `toElement` + helper read.

### 4.2 `NaruTask` — current actor + effective actor

`core/naru-api/.../api/task/NaruTask.java`

```java
/** The actor in effect for this task. Never null after task creation. */
NaruActor currentActor();
NaruTask currentActor(NaruActor actor);

/** The actor accountable for one statement: its creator if set, else the task actor. */
default NaruActor effectiveActor(NaruStatement stmt) {
    return (stmt != null && stmt.creatorActor() != null) ? stmt.creatorActor() : currentActor();
}
default NaruActor effectiveActor() { return currentActor(); }

/** Add a statement authored by a specific actor. */
default NaruTask addStatement(NaruStatement stmt, NaruActor actor) {
    return addStatement(stmt.creatorActor(actor));
}
```

### 4.3 `NaruTaskSpec` — initial actor

`core/naru-api/.../api/task/NaruTaskSpec.java`

```java
private NaruActor actor;

public NOptional<NaruActor> actor();
public NaruTaskSpec actor(NaruActor actor);

/** Convenience: set the actor for all statements declared in this spec. */
public NaruTaskSpec statements(String[] lines, NaruActor actor);
```

`NaruTaskSpec.ext(...)` already exists and is currently unused; the `actor` gets
a first-class field because the actor is load-bearing, not incidental.

### 4.4 `NaruSession` — owner principal + policy

`core/naru-api/.../api/agent/NaruSession.java`

```java
/** The human (or service) principal that owns this session. Never null. */
NaruPrincipal user();
NaruActor userActor();

/** The transversal access policy for this session. Never null. */
NaruAccessPolicy accessPolicy();
```

### 4.5 `NaruSessionBuilder` — bind the owner

`core/naru-api/.../api/agent/NaruSessionBuilder.java`

```java
NaruSessionBuilder user(NaruPrincipal user);         // default: OS user (System.getProperty("user.name"))
NaruSessionBuilder userActor(NaruActor actor);
```

### 4.6 Impl wiring (`core/naru-impl`)

- `NaruSessionImpl` gains `private final NaruPrincipal user; private final NaruAccessPolicy accessPolicy;`
  constructed in the ctor (`new NaruAccessPolicyImpl(this)`), plus `user()`, `userActor()`, `accessPolicy()`.
  Default user = `NaruPrincipal.user(System.getProperty("user.name", "local"))`; role `admin` when the
  session is created in-process (host may override).
- `NaruSessionBuilderImpl` stores `user`/`userActor` and passes them through.
- `NaruSessionImpl.newTask(NaruTaskSpec)`:
  - after creating `natuTask`, set the actor:
    ```java
    NaruActor actor = spec.actor().orNull();
    if (actor == null) {
        actor = parent != null ? parent.currentActor() : userActor();
    }
    natuTask.currentActor(actor.withTaskId(id));
    ```
  - when parsing spec statements, stamp the creator:
    ```java
    .map(x -> natuTask.parseStatement(x).get()
            .creatorActor(spec.actor().orNull())   // may stay null -> task actor is used
            .injected(true))
    ```
- `NaruTaskImpl` gains `private volatile NaruActor currentActor;` + accessors, persists it in
  `toElement()` (`o.set("actor", currentActor.toElement())`) and reads it in `load(...)`.
- `NaruTaskImpl._exeRollableStmt(NaruStatement op)` sets the effective actor for the duration of the
  statement, so nested tool/directive/model calls see the right identity without threading a parameter:
  ```java
  private void _exeRollableStmt(NaruStatement op) {
      NaruActor prev = currentActor;
      if (op.creatorActor() != null) currentActor = op.creatorActor();
      doing = op;
      try { op.exec(this); }
      finally { doing = null; currentActor = prev; fireChanged(); }
  }
  ```
- `NaruTaskImpl.invokeDirective(...)` and `NaruRegistryImpl.dispatch(...)` read the ambient
  `task.currentActor()` — no signature change.

---

## 5. Policy implementation (`core/naru-impl`, `net.thevpc.naru.impl.access`)

### 5.1 `NaruAccessPolicyImpl`

```java
public class NaruAccessPolicyImpl implements NaruAccessPolicy {
    private final NaruSession session;
    private final List<NaruPermissionRule> ordered = new ArrayList<>();   // source of truth
    private final Map<String,Integer> indexById = new HashMap<>();
    private NaruAccessEffect defaultEffect = NaruAccessEffect.ALLOW;      // compat first cut
    private final List<NaruAccessPolicyListener> listeners = new CopyOnWriteArrayList<>();
    private final List<NaruPermissionRule> builtins = new ArrayList<>();  // "core" source, re-seeded on clear
}
```

- **Thread-safety.** Rules live in a small ordered list; mutation is rare and
  reads are frequent. Guard mutation with a `synchronized` block over `ordered`
  and publish an immutable snapshot (`List.copyOf`) that `check(...)` iterates
  without locking. A `volatile` cached snapshot is invalidated on every write.
- **Built-ins.** A small set (source `core`) of safe defaults that is present in
  every session. A built-in is declared by an extension through
  `NaruAccessPolicy.addBuiltin(rule)`, which force-tags it `core`, installs it at
  the front of the list (a baseline guarantee must not be shadowed by a broad
  rule added later) and remembers it so `clearRules()` re-seeds it. The
  permissions extension declares two: an agent may not edit policy through
  `/permission`, and an agent may not read or write personal secrets. See
  `NaruPermissionsBuiltins`.
- **Default effect.** Starts at `ALLOW` so enabling the feature changes nothing.
  A host flips it to `DENY` for a fail-closed posture; rules then grant.

### 5.2 Glob matching

A small `NaruGlob` (or reuse a Nuts path matcher) supporting `*`, `**`, `?`,
`{a,b}` and `[abc]`, with `/` as the separator for file/dir/url resources.

- Locator normalization: FS resources normalize to an absolute, `/`-separated
  path (`task.resolve(path).normalize().toString()`); URL resources keep
  `scheme://host/path`; tool/directive resources use the bare name.
- Patterns that are **not absolute** are resolved against the request's
  `task.projectDir()` before matching. A pattern starting with `~` expands to the
  user home. A pattern starting with `**/` matches anywhere.
- `resource.scope()` carries the project-relative rendering so `/permission list`
  can show friendly rules while matching happens on absolute locators.

### 5.3 Evaluation algorithm

```
check(req):
  snapshot = currentSnapshot()          // immutable, ordered
  for rule in snapshot:
     if !rule.enabled continue
     if rule.matches(req.resource, req.action, req.actor)
        effect = rule.effect
        return decision(effect==ALLOW, effect, rule)
  return decision(defaultEffect==ALLOW, defaultEffect, null)   // "no rule matched"
```

`explain(req)` runs the same loop but records a `NaruAccessTrace` per rule
(matched + why), which powers `/permission check --explain`.

### 5.4 Reorder invariants

- `putRule`: replace in place when the id already exists (keeping its slot);
  otherwise append (order defaults to `Integer.MAX_VALUE` then appended last).
- `reorder(ids)`: validate ids are a permutation of the current ids; otherwise
  throw `NIllegalArgumentException`.
- `moveRule(id, index)`: remove and re-insert; clamp index.
- `moveRuleBefore/After`: resolve the anchor, then move.
- `order` int is a sort key; when two rules share it, the insertion order in
  `ordered` breaks the tie. `reorder` rewrites positions directly.

---

## 6. New extension: `naru-permissions`

Module `extensions/naru-permissions`, package `net.thevpc.naru.ext.tools.perm`,
depending only on `naru-api` (like `naru-tags`/`naru-plan`). Registered through
`META-INF/services/net.thevpc.nuts.spi.NComponent`.

```
extensions/naru-permissions/
  pom.xml
  src/main/resources/META-INF/services/net.thevpc.nuts.spi.NComponent
  src/main/java/net/thevpc/naru/ext/tools/perm/
      NaruPermissionsExtension.java          // NaruSessionExtension (load/save, lifecycle)
      NaruPermissionsBuiltins.java           // the source=core safe defaults
      NaruPermissionsRuleStore.java          // project file + session state serialization
      NaruPermissionsUtil.java               // parse/format helpers shared by directive + tools
      NaruPermissionsEvents.java             // event name constants + payload keys
      NaruPermissionsToolsetProvider.java    // tool registration
      PermissionListTool.java                // read-only, untagged
      PermissionCheckTool.java               // read-only, untagged
      PermissionAddTool.java                 // gated by "perm" tag
      PermissionRemoveTool.java              // gated by "perm" tag
      NaruPermissionsDirectiveProvider.java  // /permission directive (nested NaruPermissionDirective)
      NaruPermConfigKeysProvider.java        // naru.perm.* settings
      NaruPermToolTagProvider.java           // declares the "perm" tag
```

**Precedence: a session's own rules override the project file.** Extensions are
brought up in the order `load(state)` then `onSessionStart()`
(`NaruSessionImpl.openSessionExtensions` / `loadSessionExtensions`, then
`start()`): a resumed session restores its dynamic rules first, and
`loadProjectRules()` appends the committed rules after them. Because evaluation
is first-match-wins, the session's rules are weighed first — a user may tighten
or loosen the committed baseline for one session without editing a shared file,
while a fresh session always starts from the committed file alone. Within either
home, list order is evaluation order, so a narrow `DENY` belongs ahead of the
broad `ALLOW` it carves out of.

Only `list`/`check` are exposed to the model by default; `add`/`remove` are the
mutators and carry the `perm` tag. The `update`, `move` and `default`
subcommands exist only on the human directive (`/permission`), because changing
precedence or the fallback posture is a decision the operator makes, not one a
model should be able to make about itself.

### 6.1 `NaruPermissionsExtension`

```java
public class NaruPermissionsExtension implements NaruSessionExtension, NaruSessionListener {
    public static final String NAME = "permissions";

    private NaruSession session;
    private NaruPermissionsRuleStore store;

    // NaruSessionExtension
    public String name() { return NAME; }
    public int order() { return 200; }
    public void open(NaruSession session) {
        this.session = session;
        this.store = new NaruPermissionsRuleStore(session);
        session.addSessionListener(this);                 // <-- event subscription
        session.accessPolicy().addListener(new AuditListener(session));
    }
    public void onSessionStart(NaruSession session) {
        store.loadProjectRules();                         // .naru/permissions.tson
        session.fireEvent(NaruPermissionsEvents.POLICY_LOADED, Map.of(...));
    }
    public void onProjectChanged(NaruSession s, NPath oldDir, NPath newDir) {
        store.rebase(newDir);                             // re-scope relative patterns
    }
    public void load(NaruSession session, NElement state) { store.loadSessionRules(state); }
    public NElement save(NaruSession session) { return store.saveSessionRules(); }
    public void onTaskDeregistered(NaruSession session, long taskId) {
        session.accessPolicy().removeRules("perm:spawn:" + taskId);   // ephemeral rules die with the task
    }
    public void close() {
        if (session != null) session.removeSessionListener(this);
    }

    // NaruSessionListener
    public void onEventAppended(NaruEvent e) { ... }     // see §6.2
    public void sessionStarted(NaruSession s) {}
    public void sessionStopped(NaruSession s) {}
    public void onSessionReloaded(NaruSession s) { store.loadProjectRules(); }
}
```

### 6.2 Event reactions

Lifecycle reactions use the extension hooks — they run at a point the core
already provides, with the real task in hand, instead of re-deriving it from a
payload:

| Hook | Reaction |
|---|---|
| `onSessionStart` | apply `naru.perm.defaultEffect`; install the built-ins; load `.naru/permissions.tson`. |
| `onProjectChanged` | reload project rules from the new root. Relative patterns re-resolve at check time, so only the file location moved. |
| `onSpawned(session, task, ctx)` | when the child's `currentActor()` is an **agent**, install a scoped rule confining it to read-only: write/execute/delete must be granted explicitly. `source="perm:spawn:<taskId>"`. |
| `onTaskDeregistered(session, taskId)` | drop every `perm:spawn:<taskId>` rule. |
| `onSessionReloaded` | re-install the built-ins and reload project rules. |

Custom events ride `onEventAppended(NaruEvent)` (the extension registers itself
as a `NaruSessionListener`), so a host or a hook can inject a permission by
firing one:

| Event | Reaction |
|---|---|
| `perm.grant` (custom) | payload `{id, type, pattern, actions, subjects, kind, effect, source, index}` → `putRule` (+ optional `moveRule`). Covers the same fields as `/permission add`. |
| `perm.revoke` (custom) | payload `{id}` → `removeRule`. |
| `perm.default` (custom) | payload `{effect}` → `defaultEffect(...)`. |

Deliberately deferred to M6/M7: `TASK_TERMINATED` as a second teardown path
(`onTaskDeregistered` already covers it), `ttlMs` scheduled revocation, and
firing `POLICY_LOADED`/`DENIED`.

Custom event names are declared as constants in `NaruPermissionsEvents`:

```java
public final class NaruPermissionsEvents {
    public static final String POLICY_LOADED = "perm.policy-loaded";
    public static final String GRANT         = "perm.grant";
    public static final String REVOKE        = "perm.revoke";
    public static final String DEFAULT       = "perm.default";
    public static final String DENIED        = "perm.denied";
}
```

`/fire perm.grant id=... type=file pattern=... actions=r,w` is the event-driven
path the requirement asks for; `/on perm.denied` lets a task react to its own
denials (the event itself lands in M7).

### 6.3 Tools (model-facing)

| Tool | Gate | Purpose |
|---|---|---|
| `permission_list` | read-only, essential-ish | list rules as text/JSON |
| `permission_check` | read-only | explain effective permission for a resource/action |
| `permission_add` | tag `perm` (denied by default) | add/update one rule |
| `permission_remove` | tag `perm` (denied by default) | remove by id |
| `permission_reorder` | tag `perm` (denied by default) | move a rule |

The mutating tools carry the `perm` tag and are hidden unless granted, exactly
like `tag_add`/`tag_remove` today.

### 6.4 Directives (user-facing)

```
/permission list [--source=<s>] [--json]
/permission add <id> --type=file --pattern=src/** --actions=r,w \
             [--subjects=user:alice,group:devs] [--kind=user|agent] \
             [--effect=allow|deny] [--index=N] [--desc=...]
/permission update <id> [same flags]
/permission remove <id>
/permission move <id> --index=N | --before=<id> | --after=<id>
/permission default allow|deny
/permission check --type=file --resource=src/A.java --action=w [--actor=user:alice|agent:x]
/permission explain --type=file --resource=... --action=...
```

### 6.5 Persistence

- **Project file** `.naru/permissions.tson` — human-editable, committable,
  shared across sessions of the project. Loaded on `SESSION_START` and
  `PROJECT_CHANGE`. Rules get `source="perm:project"`.
- **Session state** `ext/permissions.tson` (via `save`/`load`) — dynamic rules
  added at runtime with `source="perm:session"` or `perm:spawn:<task>`.
- **Built-ins** live in code (`source="core"`), always present.

TSON shape:

```tson
{
  defaultEffect: "allow",
  rules: [
    {
      id: "deny-secrets",
      resourceType: "file",
      patterns: ["**/.env", "**/id_rsa", "~/.ssh/**"],
      actions: ["read", "write"],
      subjects: [{ type: "ANY" }],
      actorKind: "agent",
      effect: "deny",
      order: 10,
      source: "perm:project",
      enabled: true,
      description: "agents never read credentials"
    },
    {
      id: "devs-write-src",
      resourceType: "file",
      patterns: ["src/**"],
      actions: ["read", "write"],
      subjects: [{ type: "GROUP", id: "devs" }],
      effect: "allow",
      order: 100,
      source: "perm:project"
    }
  ]
}
```

---

## 7. Enforcement hooks

All hooks read the **ambient actor** from `task.currentActor()` (set by
`_exeRollableStmt` to the executing statement's creator when present). The check
is one line; there is no signature churn.

| # | Location | Call | Resource | Action | Actor source |
|---|---|---|---|---|---|
| 1 | `NaruRegistryImpl.dispatch(String name, Map args, NaruTask task)` (`core/naru-impl`) | `check(tool(name), CALL, task.currentActor())` before `tool.execute(...)` | `tool` | `call` | task |
| 2 | `NaruRegistryImpl.dispatch(NaruToolCall, NaruTask)` | delegates to #1 | `tool` | `call` | task |
| 3 | `NaruTaskImpl.invokeDirective(NaruDirective dir, NaruDirectiveCallContext ctx)` (`core/naru-impl`) | `check(directive(dir.name()), CALL, currentActor())` before `dir.execute(ctx)` | `directive` | `call` | task |
| 4 | `NaruTaskImpl.findTools()` (`core/naru-impl`) | filter `t.getDefinition` when `!isAllowed(tool(t.name(), t.tags()), CALL, currentActor())` | `tool` | `call` | task |
| 5 | `FileToolHelper.*` (`extensions/naru-fs`) | new `NaruFsGuard.check(task, resolvedPath, action)` at every entry (`fileRead`, `fileWrite`, `fileAppend`, `fileEdit*`, `fileCreate`, `fileGrep`, `folderFind`, `filesDiff`, `snippet`) | `file`/`dir` | `read`/`write`/`delete`/`list` | task |
| 6 | `NaruFileDirective`, `NaruCatDirective`, `NaruLsDirective`, `NaruCdDirective` (`extensions/naru-fs`) | same guard for path arguments | `file`/`dir` | `read`/`list` | task |
| 7 | `WebToolHelper.webFetch(...)` (`extensions/naru-web`) | `check(url(urlStr), FETCH, task.currentActor())` | `url` | `fetch` | task |
| 8 | `WebToolHelper.searchWeb(...)` (`extensions/naru-web`) | `check(url(searchHost), FETCH, actor)` | `url`/`host` | `fetch` | task |
| 9 | `RunShellTool` / `NaruShDirective` (`extensions/naru-shell`) | `check(shell(cmd), EXECUTE, actor)` | `shell`/`process` | `execute` | task |
| 10 | MCP tool bridge (`extensions/naru-mcp`) | `check(mcpTool(name), CALL, actor)` | `mcp-tool` | `call` | task |
| 11 | Optional: `NaruTaskImpl.chat(...)` / provider dispatch | `check(model(provider,model), CALL, actor)` | `model` | `call` | task |
| 12 | Optional: `NaruReadlineStmt` (user input) | the readline statement is `creatorActor = session.userActor()` | — | — | user |

Hook #1 and #3 are the **central gates** for tools and directives; #4 makes the
denial invisible to the model rather than a runtime error; #5–#10 are the
fine-grained per-domain gates. Adding a new domain means one new guard call, not
a core change.

Suggested denial shape (stable, model-readable):

```
ERROR: permission denied: actor agent:reviewer (for alice) may not write file /proj/src/A.java
  matched rule: deny-secrets (source=perm:project, effect=deny)
```

---

## 8. Evaluation examples

**Rule list (in order):**

1. `deny-secrets` — file `**/.env`, actions r/w, actorKind `agent`, effect DENY (`order 10`)
2. `devs-write-src` — file `src/**`, actions r/w, subjects GROUP devs, effect ALLOW (`order 100`)
3. `any-read-project` — file `**`, action read, subjects ANY, effect ALLOW (`order 900`)

**Queries:**

| Actor | Resource | Action | Result |
|---|---|---|---|
| `user alice` (group devs) | `file /proj/src/A.java` | write | ALLOW (`devs-write-src`) |
| `agent:reviewer` owner alice | `file /proj/.env` | read | DENY (`deny-secrets`) |
| `agent:reviewer` owner alice | `file /proj/src/A.java` | write | no rule matches → default (`allow` in compat mode, `deny` when hardened) |
| `user bob` (no devs) | `file /proj/src/A.java` | write | no rule matches → default |
| anyone | `tool web_fetch` | call | no rule matches → default |

`/permission add deny-agents-write --type=file --pattern=src/** --actions=w --kind=agent --effect=deny --before=devs-write-src`
inserts a new first-match denial ahead of the allow.

---

## 9. Incremental implementation plan

Each milestone is independently shippable and leaves the system green.

### M0 — Value types (no behaviour change)
- Add `net.thevpc.naru.api.access`: `NaruPrincipal(Type)`, `NaruActorKind`,
  `NaruActor`, `NaruResourceType(s)`, `NaruAction(s)`, `NaruResource`,
  `NaruSubject(Type)`, `NaruAccessEffect`, `NaruPermissionRule`,
  `NaruAccessRequest`, `NaruAccessDecision`, `NaruAccessTrace`, `NaruGlob`.
- Unit tests for subject/rule matching and glob semantics. Nothing consumes them yet.

### M1 — Policy service + session wiring (no enforcement)
- `NaruAccessPolicy`, `NaruAccessPolicyListener`, `NaruAccessDeniedException`.
- `NaruAccessPolicyImpl` in `core/naru-impl`.
- `NaruSession.user()/userActor()/accessPolicy()`, `NaruSessionBuilder.user()`,
  `NaruSessionImpl`/`NaruSessionBuilderImpl` wiring. Default user = OS user.
- Default effect `ALLOW`; built-in rules empty. Nothing changes behaviour.

### M2 — Actor threading
- `NaruStatement.creatorActor` + base `toElement`/read in `NaruStatementHelper.of`.
- `NaruTask.currentActor()/effectiveActor()/addStatement(stmt, actor)`.
- `NaruTaskSpec.actor(...)`; `NaruSessionImpl.newTask` seeds and stamps actors.
- `NaruTaskImpl` field + persistence + `_exeRollableStmt` save/restore.
- Tests: statements round-trip their actor; nested/injected statements inherit.

### M3 — Enforcement hooks (central first)
- Hook #1 (`NaruRegistryImpl.dispatch`) and #3 (`invokeDirective`): TOOL/DIRECTIVE `call`.
- Hook #4 (`findTools`) visibility filter.
- With default effect `ALLOW` and no rules, all existing behaviour is preserved;
  tests assert allow-by-default and that a deny rule blocks a tool call.

### M4 — `naru-permissions` extension (management surface)
- Module + `NaruPermissionsExtension` (no event reactions yet).
- Policy service accessor, project file + session state persistence.
- `/permission list|add|update|remove|move|default|check|explain`.
- Tools `permission_list`/`permission_check`; `perm` tag + `permission_add`/
  `permission_remove` gated behind it.
- Register the module in `extensions/pom.xml` and `app/naru/pom.xml`.

### M5 — Event-driven rules
- Implement `NaruSessionListener` in the extension; react to `onSpawned`,
  `onTaskDeregistered`, `onSessionStart`, `onProjectChanged`, `onSessionReloaded`
  and the custom `perm.*` events.
- Ephemeral spawn rules with `source="perm:spawn:<id>"`, dropped on
  `onTaskDeregistered`.
- Custom `perm.grant`/`perm.revoke`/`perm.default` events + `/fire` examples.
- `NaruAccessPolicy.addBuiltin` + `NaruPermissionsBuiltins` installing the safe
  defaults on session start (re-seeded by `clearRules()`).

### M6 — Per-domain fine-grained guards
- FS: `NaruFsGuard` in `FileToolHelper` and FS directives (#5, #6).
- Web: `WebToolHelper` (#7, #8).
- Shell: `RunShellTool`/`NaruShDirective` (#9).
- MCP: bridge (#10).
- Optional model gate (#11).

### M7 — Hardening (opt-in)
- `defaultEffect=deny` via `naru.perm.defaultEffect` config; built-in safe rules.
- Audit log via `NaruAccessPolicyListener` → `perm.denied` events.
- `/permission doctor` to report denied-recently and unconfigured domains.

---

## 10. Risks and open questions

- **Default effect.** Ships `ALLOW` for compatibility, which means enforcement
  is inert until rules exist. Operators wanting fail-closed flip one setting.
  Decide before M7 whether the default should change for agent actors only.
- **Actor provenance vs. truth.** A statement's creator is only as trustworthy
  as the component that constructed it. Engine-injected statements must inherit
  the spawning statement's actor (handled in `_exeRollableStmt`), and the tool
  call the LLM emits is stamped by the turn, not by the model's claim.
- **Workflow/async tasks.** A statement may resume after a hold/await; the saved
  `currentActor` is serialized on the task so a reload restores identity.
- **Policy vs. tags.** Tags gate *visibility*; the policy gates *execution*.
  Both may hide a tool. Keeping tag behaviour intact avoids a second, conflicting
  visibility rule — the policy filter (#4) is additive.
- **Pattern base.** Project-relative patterns are resolved at check time against
  `task.projectDir()`; a rule added under one project and evaluated under another
  must re-resolve (handled by `PROJECT_CHANGE` rebase).
- **Performance.** `check` is called on every tool/directive/FS/web/shell call.
  The snapshot-iteration design keeps it O(rules) with no allocation beyond the
  decision object; rules are expected to number in the tens.
