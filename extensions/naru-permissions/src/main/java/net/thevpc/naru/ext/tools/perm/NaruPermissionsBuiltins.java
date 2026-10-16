package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruActorKind;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResourceType;

import java.util.List;

/**
 * The safe defaults that ship with the feature: rules tagged
 * {@link NaruAccessPolicy#SOURCE_CORE} that are present in every session whether
 * or not a project file or a user ever wrote one.
 *
 * <p>A built-in is a <em>guarantee</em>, not a preference — it is installed at the
 * front of the policy and re-seeded by {@code clearRules()} — so the three here are
 * deliberately narrow and agent-scoped, and none can surprise a human acting
 * directly:
 *
 * <ul>
 *   <li>{@link #NO_AGENT_POLICY_DIRECTIVE} keeps an agent from editing the access
 *       policy through the human {@code /permission} directive. A model's route
 *       to policy is the two {@code permission_*} mutators gated behind the
 *       {@code perm} tag, which an operator grants on purpose.</li>
 *   <li>{@link #NO_AGENT_SECRET_FILES} keeps an agent out of personal secrets and
 *       private keys. The check answers {@code DENY} today; the file/url/shell
 *       guards that make it bite at the resource itself land with the per-domain
 *       enforcement work.</li>
 *   <li>{@link #NO_AGENT_BUDGET_WRITE} keeps an agent from setting prices or
 *       labels through {@code /budget}. It may still <em>read</em> the report — a
 *       model needs to know what it has spent — but an agent that can price its
 *       own calls can rewrite its own bill, so the mutation path answers
 *       {@code DENY}.</li>
 * </ul>
 *
 * <p>Installation is idempotent, so an extension may call {@link #install} on
 * every session start without accumulating duplicate rules.
 */
public final class NaruPermissionsBuiltins {

    /** Deny id: an agent may not edit the policy through the human {@code /permission} directive. */
    public static final String NO_AGENT_POLICY_DIRECTIVE = "core:no-agent-policy-directive";
    /** Deny id: an agent may not read or write personal secrets or private keys. */
    public static final String NO_AGENT_SECRET_FILES = "core:no-agent-secret-files";
    /** Deny id: an agent may read {@code /budget} but may not rewrite its own bill. */
    public static final String NO_AGENT_BUDGET_WRITE = "core:no-agent-budget-write";

    /** The resource {@code /budget} writes to; it matches the budget extension's own type by id. */
    public static final NaruResourceType BUDGET_RESOURCE = NaruResourceType.of("budget");
    /** Changing a price or a label is an update of the accounting config. */
    public static final NaruAction BUDGET_UPDATE = NaruAction.of("update");

    private NaruPermissionsBuiltins() {
    }

    /** The built-in rules, freshly built. Immutable once installed. */
    public static List<NaruPermissionRule> rules() {
        return List.of(
                new NaruPermissionRule()
                        .id(NO_AGENT_POLICY_DIRECTIVE)
                        .resourceType(NaruResourceType.DIRECTIVE)
                        .patterns("permission", "permissions")
                        .actions(NaruAction.CALL)
                        .actorKind(NaruActorKind.AGENT)
                        .effect(NaruAccessEffect.DENY)
                        .source(NaruAccessPolicy.SOURCE_CORE)
                        .description("an agent may not edit the access policy through /permission; "
                                + "the gated permission_* tools are its path"),
                new NaruPermissionRule()
                        .id(NO_AGENT_SECRET_FILES)
                        .resourceType(NaruResourceType.ANY)
                        .patterns("**/.env", "**/.env.*", "**/id_rsa", "**/id_ed25519", "**/*.pem",
                                "**/.ssh/**", "**/.aws/credentials", "**/.netrc")
                        .actions(NaruAction.READ, NaruAction.WRITE)
                        .actorKind(NaruActorKind.AGENT)
                        .effect(NaruAccessEffect.DENY)
                        .source(NaruAccessPolicy.SOURCE_CORE)
                        .description("an agent may not read or write personal secrets or private keys"),
                new NaruPermissionRule()
                        .id(NO_AGENT_BUDGET_WRITE)
                        .resourceType(BUDGET_RESOURCE)
                        .patterns("*", "**")
                        .actions(BUDGET_UPDATE)
                        .actorKind(NaruActorKind.AGENT)
                        .effect(NaruAccessEffect.DENY)
                        .source(NaruAccessPolicy.SOURCE_CORE)
                        .description("an agent may read /budget but may not set prices or labels: "
                                + "an agent that can price its own calls can rewrite its own bill")
        );
    }

    /**
     * Declares every built-in on {@code policy}. Idempotent: a second call with the
     * same ids replaces the earlier declarations in place.
     *
     * @return how many built-ins were declared
     */
    public static int install(NaruAccessPolicy policy) {
        if (policy == null) {
            return 0;
        }
        int n = 0;
        for (NaruPermissionRule r : rules()) {
            policy.addBuiltin(r);
            n++;
        }
        return n;
    }
}
