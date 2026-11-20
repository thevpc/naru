package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;

/**
 * {@code permission_add} — the model-callable half of granting.
 *
 * <p>It carries the {@code perm} tag on purpose: a model must be granted the
 * right to change permissions before it can, otherwise it could widen its own
 * limits. The rule it writes is tagged {@code perm:session} so it rides the
 * session state and never silently rewrites the project's committed file.
 */
public class PermissionAddTool extends DefaultNaruTool {

    public static final String TAG = "perm";

    public PermissionAddTool() {
        super("permission_add", new String[]{TAG});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Adds or replaces an access-control rule in the session policy. "
                + "Order is precedence, so use index to place a deny ahead of a broader allow. "
                + "Use permission_list to see the current rules.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                NaruToolParameter.string("id", "Stable rule id; adding an existing id replaces it in place.", true).build(),
                NaruToolParameter.string("type",
                        "Resource type: file, dir, tool, directive, url, shell, model, or * for any.", true).build(),
                NaruToolParameter.string("pattern",
                        "One or more glob patterns (comma separated) the rule applies to; omit for any.", false).build(),
                NaruToolParameter.string("actions",
                        "Actions covered: r/read, w/write, x/execute, call, fetch, list (comma separated); omit for any.", false).build(),
                NaruToolParameter.string("subjects",
                        "Subjects: user:alice, group:devs, role:..., actor:..., agent, system, * (comma separated).", false).build(),
                NaruToolParameter.string("kind",
                        "Restrict by actor kind: user, agent, system.", false).build(),
                NaruToolParameter.string("effect", "allow (default) or deny.", false).build(),
                NaruToolParameter.integer("index", "Position in the rule list (0-based); lower wins.", false).build(),
                NaruToolParameter.string("desc", "Human note shown next to the rule.", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String id = context.stringArg("id").orNull();
        if (NBlankable.isBlank(id)) {
            return "ERROR: 'id' is required";
        }
        NaruAccessPolicy policy = context.task().session().accessPolicy();
        NaruPermissionsDirectiveProvider.Parsed p = NaruPermissionsDirectiveProvider.Parsed.of(context.arguments());
        policy.putRule(NaruPermissionsDirectiveProvider.buildRule(id, p, null));
        if (p.one("index") != null) {
            policy.moveRule(id, Integer.parseInt(p.one("index")));
        }
        return "rule added: " + NaruPermissionsUtil.describeRule(policy.rule(id).get());
    }
}
