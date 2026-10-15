package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;

/**
 * {@code permission_remove} — the model-callable half of revoking, gated behind
 * the {@code perm} tag for the same reason as {@code permission_add}.
 */
public class PermissionRemoveTool extends DefaultNaruTool {

    public PermissionRemoveTool() {
        super("permission_remove", new String[]{PermissionAddTool.TAG});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Removes an access-control rule by id. Use permission_list to see the ids.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                NaruToolParameter.string("id", "Id of the rule to remove.", true).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String id = context.stringArg("id").orNull();
        if (NBlankable.isBlank(id)) {
            return "ERROR: 'id' is required";
        }
        boolean removed = context.task().session().accessPolicy().removeRule(id);
        return removed ? "rule removed: " + id : "no such rule: " + id;
    }
}
