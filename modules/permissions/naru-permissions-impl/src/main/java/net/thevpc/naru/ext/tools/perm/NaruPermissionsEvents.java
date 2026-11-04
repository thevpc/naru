package net.thevpc.naru.ext.tools.perm;

/**
 * Event names the permissions feature fires and reacts to.
 *
 * <p>They are declared once here so a rule change made through a directive, a
 * tool or an event all surface under the same name, and so a task can react to
 * its own denials with {@code /on perm.denied} without knowing which tool
 * produced them.
 *
 * <p>{@code GRANT}, {@code REVOKE} and {@code DEFAULT} are now delivered to
 * {@link NaruPermissionsExtension}, which turns them into policy edits; fire one
 * with {@code /fire perm.grant id=… type=file pattern=src/** actions=r,w}.
 * {@code POLICY_LOADED} and {@code DENIED} are declared for hosts and future
 * reactions and are not yet fired.
 */
public final class NaruPermissionsEvents {

    /** Fired after the project rule file is (re)loaded. */
    public static final String POLICY_LOADED = "perm.policy-loaded";

    /** Payload {@code {id, type, pattern, actions, subjects, effect, ttlMs}}; installs a rule. */
    public static final String GRANT = "perm.grant";

    /** Payload {@code {id}}; removes a rule. */
    public static final String REVOKE = "perm.revoke";

    /** Payload {@code {effect}}; changes the fallback effect. */
    public static final String DEFAULT = "perm.default";

    /** Fired for every denied check, so a task can observe and report its own denials. */
    public static final String DENIED = "perm.denied";

    private NaruPermissionsEvents() {
    }
}
