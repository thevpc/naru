package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessDecision;
import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruAccessRequest;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.access.NaruResource;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;

/**
 * {@code permission_check} — answer "may this actor do this to this resource?"
 * without attempting it.
 *
 * <p>It exists so a model can plan around a limit instead of discovering it by
 * being refused mid-task, and so a host can ask the same question the
 * enforcement path asks, with the same answer.
 */
public class PermissionCheckTool extends DefaultNaruTool {

    public PermissionCheckTool() {
        super("permission_check", new String[0]);
    }

    @Override
    public boolean isEssential() {
        return true;
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Checks whether an actor may perform an action on a resource. "
                + "Answers with the effect and the rule that decided it. "
                + "Omit --actor to check the current actor.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                NaruToolParameter.string("type",
                        "Resource type: file, dir, tool, directive, url, shell, model, or * for any.", false).build(),
                NaruToolParameter.string("resource",
                        "The resource locator: a path, a tool name, a directive name, a URL, ...", true).build(),
                NaruToolParameter.string("action",
                        "Action: r/read, w/write, x/execute, call, fetch, list, or * for any.", false).build(),
                NaruToolParameter.string("actor",
                        "Actor to check: user:alice, agent:reviewer, agent:reviewer@alice, system. Defaults to the current actor.", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String locator = context.stringArg("resource").orNull();
        if (NBlankable.isBlank(locator)) {
            return "ERROR: a resource locator is required";
        }
        NaruResource resource = NaruPermissionsUtil.resourceOf(context.stringArg("type").orNull(), locator);
        String actionArg = context.stringArg("action").orNull();
        NaruAction action = NBlankable.isBlank(actionArg) ? NaruAction.ANY : NaruAction.parse(actionArg);
        String actorArg = context.stringArg("actor").orNull();
        NaruActor actor = NBlankable.isBlank(actorArg)
                ? context.task().currentActor()
                : NaruPermissionsUtil.parseActor(actorArg);
        NaruAccessPolicy policy = context.task().session().accessPolicy();
        NaruAccessRequest request = NaruAccessRequest.of(resource, action, actor).task(context.task());
        NaruAccessDecision d = policy.check(request);
        StringBuilder sb = new StringBuilder();
        sb.append(d.allowed() ? "ALLOW" : "DENY")
                .append(' ').append(resource.type().id()).append(' ').append(resource.locator())
                .append(' ').append(action.id()).append(" for ").append(actor);
        if (d.matchedRule().isPresent()) {
            sb.append(" [rule ").append(d.matchedRule().get().id()).append(']');
        } else {
            sb.append(" [default ").append(d.effect().name().toLowerCase()).append(']');
        }
        if (!NBlankable.isBlank(d.reason())) {
            sb.append(" - ").append(d.reason());
        }
        return sb.toString();
    }
}
