package net.thevpc.naru.api.access;

import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.nuts.io.NPath;

import java.util.List;

/**
 * The management surface of the access policy: where committed rules live, how they are read,
 * and how a host writes them back.
 *
 * <p>{@link NaruAccessPolicy} is enforcement and lives on the session, so it works with no
 * extension installed. This is the part that knows about a <em>file</em> — the committed
 * {@code .naru/permissions.tson} of a project — and about the origin tags that make a reload
 * replace a set of rules instead of appending to it. The implementation ships with the
 * {@code naru-permissions} extension, which is the component that owns that format.
 *
 * <p><b>Why this is an interface in the API and not a class in the extension.</b> A host that
 * edits rules — the desktop's Permissions tab, or any future GUI — must not put the extension
 * jar on its compile classpath: Nuts resolves extensions in their own classloaders, and pinning
 * one there has produced a provider compiled against an older {@code naru-api}. So the host asks
 * the registry for it by name and type, which is the door
 * {@link net.thevpc.naru.api.registry.NaruRegistry#extension(String, Class)} already opens:
 *
 * <pre>{@code
 * NaruPermissionStore store = session.registry()
 *         .extension(NaruPermissionStore.EXTENSION, NaruPermissionStore.class).orNull();
 * if (store != null) {
 *     store.writeProjectFile(store.projectFile() ... );
 * }
 * }</pre>
 *
 * <p>It extends {@link NaruSessionExtension} for that reason only — that lookup is typed over
 * session extensions, and the implementing extension already is one. Nothing here is a new
 * lifecycle hook.
 *
 * <p><b>It is optional.</b> A profile without {@code naru-permissions} has no store: enforcement
 * keeps working, the live policy stays editable through {@link NaruAccessPolicy}, and only the
 * committed file — reading, reloading, saving — is unavailable. A host must say so rather than
 * write the file itself with a parser of its own, because two parsers of one format drift.
 *
 * <p>Every method reports failure as a message rather than an exception, because the caller is
 * usually a status line: {@code null} means it worked.
 */
public interface NaruPermissionStore extends NaruSessionExtension {

    /** The {@link #name()} of the extension that provides this, for the registry lookup. */
    String EXTENSION = "permissions";

    /** Rules that ship with the build; the same tag as {@link NaruAccessPolicy#SOURCE_CORE}. */
    String SOURCE_CORE = NaruAccessPolicy.SOURCE_CORE;

    /** Rules loaded from {@link #PROJECT_FILE}; a reload replaces exactly this set. */
    String SOURCE_PROJECT = "perm:project";

    /** Rules added at runtime and persisted in the session's own state. */
    String SOURCE_SESSION = "perm:session";

    /** Prefix of the rules scoped to one spawned task, dropped when that task dies. */
    String SOURCE_SPAWN_PREFIX = "perm:spawn:";

    /** The committed policy file, relative to the project directory. */
    String PROJECT_FILE = ".naru/permissions.tson";

    /**
     * The committed file for this session's project, or {@code null} when the session has no
     * project directory. Absent is normal — it means "no committed rules", not an error.
     */
    NPath projectFile();

    /**
     * The rules in the committed file, in file order, without touching the policy. A missing file
     * is an empty list; a rule that will not parse is skipped, because one bad line must not cost
     * the user the rest of the file.
     */
    List<NaruPermissionRule> readProjectRules();

    /** The fallback effect recorded in the committed file, or {@code fallback} when it states none. */
    NaruAccessEffect readProjectDefaultEffect(NaruAccessEffect fallback);

    /**
     * Replace the rules loaded from the committed file with the ones the file holds now — the same
     * contract the extension applies at session start, and the same one the {@code /permission
     * reload} directive uses.
     *
     * @return {@code null} on success, otherwise the reason it failed
     */
    String reloadProjectRules();

    /**
     * Write the committed file: the project-scoped rules in order, plus the fallback effect they
     * end on. Only {@link #SOURCE_PROJECT} rules belong in it — session and spawn rules are session
     * state, and a runtime that rewrote the committed file would turn a reviewed policy into an
     * artifact. This is why the method exists for a <em>human</em> pressing Save, and why the
     * extension itself never calls it.
     *
     * @param rules          the project rules, in the order they should be evaluated
     * @param defaultEffect  the fallback effect to record, or {@code null} for {@code ALLOW}
     * @return {@code null} on success, otherwise the reason it failed
     */
    String writeProjectRules(List<NaruPermissionRule> rules, NaruAccessEffect defaultEffect);

    /**
     * The session's own store, or {@code null} when the {@code naru-permissions} extension is not
     * installed. A host that gets null keeps the live policy — {@link NaruAccessPolicy} is core and
     * always there — and must say that the committed file is unavailable rather than parse it alone.
     */
    static NaruPermissionStore of(net.thevpc.naru.api.agent.NaruSession session) {
        if (session == null || session.registry() == null) {
            return null;
        }
        try {
            return session.registry().extension(EXTENSION, NaruPermissionStore.class).orNull();
        } catch (RuntimeException e) {
            // an extension that is present but half-initialised must not cost the host its session
            return null;
        }
    }
}
