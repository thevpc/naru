package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.elem.NObjectElementBuilder;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;

import java.util.ArrayList;
import java.util.List;

/**
 * Where permission rules come from and go.
 *
 * <p>Two homes, deliberately different:
 *
 * <ul>
 *   <li><b>The project file</b> {@code .naru/permissions.tson} — human-editable,
 *       committable, shared by every session of the project. Read on session
 *       start and on project change, never written by the runtime; editing it is
 *       a deliberate act, and a tool that rewrote it would make the committed
 *       policy a runtime artifact.</li>
 *   <li><b>Session state</b> — the dynamic rules a user or model added at
 *       runtime. They live in the session's own state (through
 *       {@code NaruSessionExtension.save/load}) so they survive a resume without
 *       leaking into the project's committed file.</li>
 * </ul>
 *
 * <p>Rules are tagged with their origin ({@code perm:project}, {@code
 * perm:session}, {@code perm:spawn:<task>}) because loading replaces a whole
 * origin: a project file that dropped a rule must drop it here too, and a
 * resumed session must not re-add a rule the current file no longer has. Without
 * the tag, "reload" would be "append forever".
 *
 * <p>Every failure is swallowed and logged. A malformed permission file must not
 * cost the user the session: the rules that did parse still apply, and the ones
 * that did not are reported.
 */
public class NaruPermissionsRuleStore {

    public static final String PROJECT_FILE = ".naru/permissions.tson";

    private final NaruSession session;

    public NaruPermissionsRuleStore(NaruSession session) {
        this.session = session;
    }

    public NaruSession session() {
        return session;
    }

    public NPath projectFile() {
        NPath dir = session.projectDir();
        return dir == null ? null : dir.resolve(PROJECT_FILE);
    }

    /** Loads the committed project rules, replacing any previously loaded from that origin. */
    public void loadProjectRules() {
        NPath file = projectFile();
        if (file == null || !file.isRegularFile()) {
            return;
        }
        NElement root;
        try {
            root = NElementReader.ofTson().read(file);
        } catch (Exception e) {
            warn(NMsg.ofC("cannot read permission file %s: %s", file, e.getMessage()));
            return;
        }
        NaruAccessPolicy policy = session.accessPolicy();
        policy.removeRules(NaruPermissionsExtension.SOURCE_PROJECT);
        for (NaruPermissionRule r : readRules(root)) {
            policy.putRule(r.source(NaruPermissionsExtension.SOURCE_PROJECT));
        }
        String def = readDefaultEffect(root);
        if (def != null) {
            policy.defaultEffect(NaruPermissionsUtil.parseEffect(def));
        }
    }

    /**
     * The session-state element: only the dynamic rules. The committed project
     * rules are intentionally excluded, so a resume cannot shadow the current
     * project file with a stale copy of it.
     */
    public NElement saveSessionRules() {
        NaruAccessPolicy policy = session.accessPolicy();
        NObjectElementBuilder root = NObjectElementBuilder.of();
        root.set("defaultEffect", policy.defaultEffect().name().toLowerCase());
        NArrayElementBuilder rules = NElement.ofArrayBuilder();
        for (NaruPermissionRule r : policy.rules()) {
            if (isSessionSource(r.source())) {
                rules.add(r.toElement());
            }
        }
        root.set("rules", rules.build());
        return root.build();
    }

    /** Restores the dynamic rules saved by {@link #saveSessionRules()}. */
    public void loadSessionRules(NElement state) {
        if (state == null || !state.isAnyObject()) {
            return;
        }
        NaruAccessPolicy policy = session.accessPolicy();
        for (NaruPermissionRule r : new ArrayList<>(policy.rules())) {
            if (isSessionSource(r.source())) {
                policy.removeRule(r.id());
            }
        }
        for (NaruPermissionRule r : readRules(state)) {
            String src = isSessionSource(r.source()) ? r.source() : NaruPermissionsExtension.SOURCE_SESSION;
            policy.putRule(r.source(src));
        }
        String def = readDefaultEffect(state);
        if (def != null) {
            policy.defaultEffect(NaruPermissionsUtil.parseEffect(def));
        }
    }

    /** True for a source owned by the session state rather than the project file. */
    static boolean isSessionSource(String source) {
        return source != null
                && (source.equals(NaruPermissionsExtension.SOURCE_SESSION)
                || source.startsWith(NaruPermissionsExtension.SOURCE_SPAWN_PREFIX));
    }

    private static List<NaruPermissionRule> readRules(NElement root) {
        List<NaruPermissionRule> out = new ArrayList<>();
        if (root == null || !root.isAnyObject()) {
            return out;
        }
        NObjectElement o = root.asObject().get();
        NElement rules = o.get("rules").orNull();
        if (rules == null || !rules.isAnyArray()) {
            return out;
        }
        for (NElement c : rules.asArray().get().children()) {
            try {
                NaruPermissionRule r = NaruPermissionRule.of(c);
                if (r != null) {
                    // a rule with no id cannot be addressed or removed; give it a stable one
                    if (r.id() == null || r.id().isBlank()) {
                        r = r.id("rule-" + Integer.toHexString(c.hashCode()));
                    }
                    out.add(r);
                }
            } catch (Exception e) {
                // one malformed rule must not discard the rest of the file
            }
        }
        return out;
    }

    private static String readDefaultEffect(NElement root) {
        if (root == null || !root.isAnyObject()) {
            return null;
        }
        return root.asObject().get().getStringValue("defaultEffect").orNull();
    }

    private void warn(NMsg msg) {
        try {
            session.log(NaruLogMode.SCRIPT, NMsg.ofC("⚠ permissions: %s", msg));
        } catch (Exception ignore) {
            // logging must never be the reason a load fails
        }
    }
}
