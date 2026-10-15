package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.access.NaruActorKind;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.access.NaruSubject;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.spawn.NaruSpawnContext;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NLiteral;

import java.util.Map;

/**
 * Makes the access policy manageable: loads the committed project rules,
 * persists the dynamic ones, installs the built-in safe defaults, and reacts to
 * the events that add, remove or rescope a rule.
 *
 * <p>The policy itself lives on the session ({@code NaruSession.accessPolicy()}),
 * because it is a cross-cutting concern the core and every domain consults. This
 * extension is only its <em>management surface</em>: it knows where rules are
 * stored, when to reload them, and it is what the {@code /permission} directive
 * and the {@code permission_*} tools speak to after the policy itself. Removing
 * this jar leaves the enforcement intact and only removes the ability to
 * read and edit rules from the shell.
 *
 * <p><b>Event-driven rules (M5).</b> The extension is also a
 * {@link NaruSessionListener}, so a rule can be injected by firing an event
 * rather than by calling an API or a directive:
 * <ul>
 *   <li>{@code /fire perm.grant id=… type=file pattern=src/** actions=r,w effect=allow}
 *       installs a rule ({@code perm.revoke id=…} removes it,
 *       {@code perm.default effect=deny} flips the fallback).</li>
 *   <li>A task spawned as an <em>agent</em> gets a scoped rule (source
 *       {@code perm:spawn:<id>}) confining it to read-only until an operator
 *       grants more; the rule is dropped when the task dies.</li>
 * </ul>
 * The built-in safe defaults are declared with {@code addBuiltin} and installed
 * on session start; see {@link NaruPermissionsBuiltins}.
 *
 * <p>Because {@code load(state)} runs before {@code onSessionStart()}, a resumed
 * session restores its dynamic rules first and the committed project rules are
 * appended after them. Evaluation is first-match-wins, so the session's rules
 * take precedence: a user may tighten or loosen the committed baseline for one
 * session, while a fresh session starts from the committed file alone. Built-ins
 * sit ahead of both.
 */
public class NaruPermissionsExtension implements NaruSessionExtension, NaruSessionListener {

    public static final String NAME = "permissions";

    /** Rules that ship with the build (the {@link NaruPermissionsBuiltins}). */
    public static final String SOURCE_CORE = NaruAccessPolicy.SOURCE_CORE;
    /** Rules loaded from the project file. */
    public static final String SOURCE_PROJECT = "perm:project";
    /** Rules added at runtime and kept in the session state. */
    public static final String SOURCE_SESSION = "perm:session";
    /** Rules scoped to a spawned task; removed when the task dies. */
    public static final String SOURCE_SPAWN_PREFIX = "perm:spawn:";

    private volatile NaruSession session;
    private volatile NaruPermissionsRuleStore store;
    private volatile boolean registered;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int order() {
        return 200;
    }

    public NaruPermissionsRuleStore store() {
        return store;
    }

    @Override
    public void open(NaruSession session) {
        this.session = session;
        if (this.store == null) {
            this.store = new NaruPermissionsRuleStore(session);
        }
        if (!registered) {
            session.addSessionListener(this);
            registered = true;
        }
        if (isEnabled(session)) {
            installBuiltins(session);
        }
    }

    @Override
    public void load(NaruSession session, NElement state) {
        this.session = session;
        if (this.store == null) {
            this.store = new NaruPermissionsRuleStore(session);
        }
        store.loadSessionRules(state);
    }

    @Override
    public NElement save(NaruSession session) {
        NaruPermissionsRuleStore s = store;
        return s == null ? null : s.saveSessionRules();
    }

    @Override
    public void onSessionStart(NaruSession session) {
        initStore(session);
        if (!isEnabled(session)) {
            return;
        }
        // A session-level default effect is the fallback posture; the project file
        // still wins, because it is applied after this and is the committed policy.
        Object configured = session.getSessionEnv("naru.perm.defaultEffect").orNull();
        if (configured != null) {
            session.accessPolicy().defaultEffect(
                    NaruPermissionsUtil.parseEffect(String.valueOf(configured)));
        }
        installBuiltins(session);
        store.loadProjectRules();
    }

    @Override
    public void onSessionReloaded(NaruSession session) {
        initStore(session);
        if (!isEnabled(session)) {
            return;
        }
        installBuiltins(session);
        store.loadProjectRules();
    }

    @Override
    public void onProjectChanged(NaruSession session, NPath oldProjectDir, NPath newProjectDir) {
        NaruPermissionsRuleStore s = initStore(session);
        if (!isEnabled(session)) {
            return;
        }
        // Relative patterns are resolved against the task's current project dir at
        // check time, so nothing has to be rewritten; only the file location moved.
        s.loadProjectRules();
    }

    @Override
    public void onSpawned(NaruSession session, NaruTask task, NaruSpawnContext context) {
        if (session == null || task == null || !isEnabled(session)) {
            return;
        }
        NaruActor actor = task.currentActor();
        if (actor != null && actor.isAgent()) {
            installSpawnConfinement(session, task, actor);
        }
    }

    @Override
    public void onTaskDeregistered(NaruSession session, long taskId) {
        NaruSession s = session != null ? session : this.session;
        if (s == null) {
            return;
        }
        s.accessPolicy().removeRules(SOURCE_SPAWN_PREFIX + taskId);
    }

    // ── event reactions ─────────────────────────────────────────────────────

    @Override
    public void onEventAppended(NaruEvent event) {
        NaruSession s = this.session;
        if (event == null || s == null || !isEnabled(s)) {
            return;
        }
        switch (event.name()) {
            case NaruPermissionsEvents.GRANT -> handleGrant(s, event.payload());
            case NaruPermissionsEvents.REVOKE -> handleRevoke(s, event.payload());
            case NaruPermissionsEvents.DEFAULT -> handleDefault(s, event.payload());
            default -> {
            }
        }
    }

    @Override
    public void sessionStarted(NaruSession session) {
        // policy loading already happens in onSessionStart; nothing to do here
    }

    @Override
    public void sessionStopped(NaruSession session) {
    }

    private void handleGrant(NaruSession s, Map<String, Object> payload) {
        NaruPermissionsDirectiveProvider.Parsed p = NaruPermissionsDirectiveProvider.Parsed.of(payload);
        String id = p.one("id");
        if (id == null) {
            id = p.one("rule");
        }
        s.accessPolicy().putRule(NaruPermissionsDirectiveProvider.buildRule(id, p, null));
        if (id != null && p.one("index") != null) {
            try {
                s.accessPolicy().moveRule(id, Integer.parseInt(p.one("index")));
            } catch (RuntimeException ignored) {
                // an out-of-range index must not fail the event; the rule is installed
            }
        }
    }

    private void handleRevoke(NaruSession s, Map<String, Object> payload) {
        String id = NaruPermissionsDirectiveProvider.Parsed.of(payload).one("id");
        if (id != null) {
            s.accessPolicy().removeRule(id);
        }
    }

    private void handleDefault(NaruSession s, Map<String, Object> payload) {
        String effect = NaruPermissionsDirectiveProvider.Parsed.of(payload).one("effect");
        if (effect != null) {
            s.accessPolicy().defaultEffect(NaruPermissionsUtil.parseEffect(effect));
        }
    }

    // ── spawn confinement ───────────────────────────────────────────────────

    /**
     * Confines a freshly spawned agent to read-only: write, execute and delete
     * must be granted explicitly. Scoped to the child's own actor and tagged
     * {@code perm:spawn:<taskId>} so it disappears with the task.
     */
    void installSpawnConfinement(NaruSession session, NaruTask task, NaruActor actor) {
        String source = SOURCE_SPAWN_PREFIX + task.id();
        NaruPermissionRule rule = new NaruPermissionRule()
                .id(source)
                .resourceType(NaruResourceType.ANY)
                .actions(NaruAction.WRITE, NaruAction.EXECUTE, NaruAction.DELETE)
                .subjects(NaruSubject.actor(actor.id()))
                .actorKind(NaruActorKind.AGENT)
                .effect(NaruAccessEffect.DENY)
                .source(source)
                .description("spawned agent " + actor.id()
                        + " must be granted write/execute/delete explicitly");
        session.accessPolicy().putRule(rule);
    }

    private void installBuiltins(NaruSession session) {
        if (isBuiltinsEnabled(session)) {
            NaruPermissionsBuiltins.install(session.accessPolicy());
        }
    }

    // ── lifecycle helpers ───────────────────────────────────────────────────

    @Override
    public void close() {
        // Unsubscribe only. The store is deliberately kept: close() runs during the
        // session stop, and a save() that arrives after it must still be able to hand
        // back the rules. The extension is discarded with the registry either way.
        NaruSession s = session;
        if (s != null && registered) {
            try {
                s.removeSessionListener(this);
            } catch (RuntimeException ignored) {
                // a session that is already stopped refuses new listener edits; the
                // listener dies with it, so there is nothing to clean up
            }
        }
        registered = false;
    }

    private NaruPermissionsRuleStore initStore(NaruSession session) {
        this.session = session;
        NaruPermissionsRuleStore s = this.store;
        if (s == null) {
            s = new NaruPermissionsRuleStore(session);
            this.store = s;
        }
        return s;
    }

    /** {@code naru.perm.enabled=false} turns the whole feature off without uninstalling it. */
    private static boolean isEnabled(NaruSession session) {
        Object v = session.getSessionEnv("naru.perm.enabled").orNull();
        return v == null || NLiteral.of(v).asBoolean().orElse(true);
    }

    /** {@code naru.perm.builtins=false} turns off the safe defaults, keeping the rest. */
    static boolean isBuiltinsEnabled(NaruSession session) {
        Object v = session.getSessionEnv("naru.perm.builtins").orNull();
        return v == null || NLiteral.of(v).asBoolean().orElse(true);
    }

    /** The store of the installed extension, or null when the module is absent. */
    public static NaruPermissionsRuleStore store(NaruSession session) {
        if (session == null || session.registry() == null) {
            return null;
        }
        return session.registry().extension(NAME, NaruPermissionsExtension.class)
                .map(NaruPermissionsExtension::store)
                .orNull();
    }
}
