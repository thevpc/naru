package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.nuts.elem.NArrayElementBuilder;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementFormatterStyle;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NElementWriter;
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
        NElement root = readRoot(file);
        if (root == null) {
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
     * The rules in the committed file, in file order, without touching the policy. A missing file is
     * an empty list; a rule that will not parse is skipped by {@link #readRules}, so one bad line
     * does not cost the user the rest of the file.
     */
    public List<NaruPermissionRule> readProjectRules() {
        NPath file = projectFile();
        if (file == null || !file.isRegularFile()) {
            return new ArrayList<>();
        }
        NElement root = readRoot(file);
        return root == null ? new ArrayList<>() : readRules(root);
    }

    /** The fallback effect recorded in the committed file, or {@code fallback} when it states none. */
    public NaruAccessEffect readProjectDefaultEffect(NaruAccessEffect fallback) {
        NPath file = projectFile();
        if (file == null || !file.isRegularFile()) {
            return fallback;
        }
        NElement root = readRoot(file);
        String def = root == null ? null : readDefaultEffect(root);
        if (def == null) {
            return fallback;
        }
        try {
            return NaruPermissionsUtil.parseEffect(def);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Write the committed file: the given rules in order, plus the fallback effect they end on.
     *
     * <p>This is the counterpart of {@link #loadProjectRules()}, and it is deliberately not called
     * anywhere inside the extension. The file is the reviewed, committable policy; a runtime that
     * rewrote it as a side effect would turn a human artifact into session state. It exists because
     * a human pressing Save in a host is the deliberate act that rule allows — and because that host
     * must write through this parser rather than keep a second copy of the format.
     *
     * @return {@code null} on success, otherwise the message to show the user
     */
    public String writeProjectRules(List<NaruPermissionRule> rules, NaruAccessEffect defaultEffect) {
        NPath file = projectFile();
        if (file == null) {
            return "this session has no project directory, so there is no project policy file";
        }
        NObjectElementBuilder root = NObjectElementBuilder.of();
        root.set("defaultEffect", (defaultEffect == null ? NaruAccessEffect.ALLOW : defaultEffect)
                .name().toLowerCase());
        NArrayElementBuilder elements = NElement.ofArrayBuilder();
        for (NaruPermissionRule r : rules == null ? List.<NaruPermissionRule>of() : rules) {
            elements.add(r.toElement());
        }
        root.set("rules", elements.build());
        try {
            NPath parent = file.parent();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.PRETTY)
                    .write(root.build(), file);
            return null;
        } catch (Exception e) {
            return "cannot write " + file + ": " + e.getMessage();
        }
    }

    /** Parse the file, or null when it cannot be read; a read failure is logged, not thrown. */
    private NElement readRoot(NPath file) {
        try {
            return NElementReader.ofTson().read(file);
        } catch (Exception e) {
            warn(NMsg.ofC("cannot read permission file %s: %s", file, e.getMessage()));
            return null;
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
