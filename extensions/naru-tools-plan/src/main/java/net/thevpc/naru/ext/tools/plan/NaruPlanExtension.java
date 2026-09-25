package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Makes the planning feature available to the session, without the core knowing that
 * plans exist.
 * <p>
 * The extension owns the plan graph, contributes it to task prompts, and persists it
 * under the session's own {@code ext/} folder. Removing {@code naru-tools-plan} from the
 * classpath removes planning entirely; nothing in {@code naru-api} or {@code naru-impl}
 * refers to it.
 */
public class NaruPlanExtension implements NaruSessionExtension {

    public static final String NAME = "plan";

    /**
     * Tells the model how to interact with the plan, given the tools it has. Kept here
     * rather than in a core prompt mode so the instructions disappear with the feature.
     */
    private static final String PROGRESS_RULES =
            "Report progress with plan_update(item, status, notes) as you start, block on, or finish work. "
                    + "You cannot mark an item done yourself: completion goes through the item's validator, "
                    + "so finish the work and let the validator judge it. The plan's shape is fixed for this "
                    + "session; if it turns out to be wrong, say so in your report and let the user re-plan.";

    private final NaruPlanManagerImpl plans = new NaruPlanManagerImpl();

    @Override
    public String name() {
        return "plan";
    }

    @Override
    public Set<NaruSource> sources() {
        return EnumSet.of(NaruSource.SYSTEM);
    }

    /**
     * The plan graph, shared with the tools and directives of this extension.
     */
    public NaruPlanManager plans() {
        return plans;
    }

    /**
     * The session's plan graph, for the tools and directives of this feature.
     * <p>
     * The lookup cannot fail in practice: these tools ship in the same jar as the
     * extension that provides the graph, and both are registered together.
     */
    public static NaruPlanManager plans(NaruSession session) {
        return session.registry().extension(NAME, NaruPlanExtension.class)
                .map(NaruPlanExtension::plans)
                .orElseThrow(() -> new IllegalStateException(
                        "the plan extension is not installed in this session"));
    }

    @Override
    public boolean isRelevant(NaruTask task) {
        return true;
    }

    @Override
    public List<NaruMessage> contribute(NaruTask task) {
        NaruPlan plan = plans.activePlan().orNull();
        if (plan == null || plan.status() == NaruPlanStatus.COMPLETED) {
            return Collections.emptyList();
        }
        return List.of(NaruMessage.system(
                "### ACTIVE PLAN:\n" + plan.render() + "\n" + PROGRESS_RULES));
    }

    @Override
    public NOptional<NElement> load(NaruSession session, NPath file) {
        plans.loadFrom(file);
        return NOptional.ofNamedEmpty(NMsg.ofC("no plan state at %s", file));
    }

    @Override
    public NElement save(NaruSession session) {
        return plans.toElement();
    }

    @Override
    public void close() {
        plans.clear();
    }
}
