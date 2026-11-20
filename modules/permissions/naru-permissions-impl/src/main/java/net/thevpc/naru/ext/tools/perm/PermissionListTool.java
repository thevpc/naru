package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;

import java.util.List;

/**
 * {@code permission_list} — read the access policy back.
 *
 * <p>It carries no tag and is essential: knowing your own limits is the
 * precondition for working within them, and a model that can see the rules can
 * explain a refusal instead of retrying blindly. Reading is not a capability
 * worth gating; writing is, and that is {@code permission_add}/{@code
 * permission_remove}.
 */
public class PermissionListTool extends DefaultNaruTool {

    public PermissionListTool() {
        super("permission_list", new String[0]);
    }

    @Override
    public boolean isEssential() {
        return true;
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Lists the access-control rules in evaluation order, with the default effect. "
                + "Order is precedence: the first rule that matches a request decides it. "
                + "Optionally filter by source (perm:project, perm:session, core).");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                NaruToolParameter.string("source", "Only show rules with this source.", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        NaruAccessPolicy policy = context.task().session().accessPolicy();
        String source = context.stringArg("source").orNull();
        List<NaruPermissionRule> rules = NBlankable.isBlank(source) ? policy.rules() : policy.rules(source);
        if (rules.isEmpty()) {
            return "No rules"
                    + (NBlankable.isBlank(source) ? "" : " for source " + source)
                    + " (default " + policy.defaultEffect().name().toLowerCase() + ").";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(rules.size()).append(" rule(s), default ")
                .append(policy.defaultEffect().name().toLowerCase()).append(":\n");
        int i = 1;
        for (NaruPermissionRule r : rules) {
            sb.append("  ").append(i++).append(". ").append(NaruPermissionsUtil.describeRule(r)).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
